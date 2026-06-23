package com.chatsummary.bot.scheduler;

import com.chatsummary.bot.service.AdService;
import com.chatsummary.bot.service.AdminNotificationService;
import com.chatsummary.bot.service.ChatConfigService;
import com.chatsummary.bot.service.GeminiSummaryService;
import com.chatsummary.bot.service.MessageService;
import com.chatsummary.bot.telegram.ChatSummaryBot;
import com.chatsummary.bot.util.TelegramLinks;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class DailySummaryScheduler {

    private static final long GEMINI_THROTTLE_MILLIS = 2_000L * 60L;

    private final MessageService messageService;
    private final GeminiSummaryService geminiSummaryService;
    private final ChatSummaryBot chatSummaryBot;
    private final ChatConfigService chatConfigService;
    private final AdminNotificationService adminNotificationService;
    private final AdService adService;

    @Scheduled(fixedRate = 60_000)
    public void sendScheduledSummaries() throws InterruptedException {
        log.debug("Checking for scheduled summaries...");

        var now = ZonedDateTime.now();
        var activeChatIds = messageService.getAllActiveChatIds();

        if (activeChatIds.isEmpty()) {
            return;
        }

        for (var chatId : activeChatIds) {
            var config = chatConfigService.getChatConfig(chatId);
            var cron = CronExpression.parse(config.getCron());
            var lastProcessedInstant = config.getLastProcessedAt() == null
                    ? LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
                    : config.getLastProcessedAt();
            var lastProcessed = lastProcessedInstant.atZone(ZoneId.systemDefault());

            log.debug(
                    "Checking chat {} for scheduled summary (last processed: {}, cron: {})",
                    chatId,
                    lastProcessed,
                    config.getCron()
            );

            var nextExecution = cron.next(lastProcessed);
            if (nextExecution != null && !nextExecution.isAfter(now)) {
                var processed = processSummary(
                        chatId,
                        lastProcessedInstant,
                        config.getLanguage(),
                        config.getCustomPrompt()
                );
                if (processed) {
                    chatConfigService.updateLastProcessedAt(chatId, now.toInstant());
                }
                Thread.sleep(GEMINI_THROTTLE_MILLIS);
            }
        }
    }

    private boolean processSummary(long chatId, Instant since, String language, String customPrompt) {
        try {
            var messages = messageService.getMessagesSince(chatId, since);
            if (messages.isEmpty()) {
                log.info("No new messages for chat {} since {}, skipping scheduled summary.", chatId, since);
                return true;
            }

            log.info("Sending scheduled summary for chat {} since {}...", chatId, since);
            var summary = geminiSummaryService.summarize(messages, language, customPrompt);

            var config = chatConfigService.getChatConfig(chatId);
            var prevMessageId = config.getLastSummaryMessageId();
            var prevText = config.getLastSummaryText();

            var body = "📋 *Summary*\n\n" + summary;
            var textToSend = withPreviousLink(chatId, body, prevMessageId);
            var sentMessageId = chatSummaryBot.sendMessageReturningId(chatId, textToSend);

            if (sentMessageId == null) {
                log.error("Failed to deliver scheduled summary to chat {}; not consuming credit or clearing messages",
                        chatId);
                return false;
            }

            linkPreviousToCurrent(chatId, prevMessageId, prevText, sentMessageId);
            chatConfigService.updateLastSummary(chatId, sentMessageId, textToSend);

            if (chatConfigService.getChatConfig(chatId).isMonthlySummaryEnabled()) {
                messageService.saveDailySummary(chatId, summary);
            }

            adService.consumeCreditAndMaybeShowAd(chatId);

            messageService.clearOldMessages(chatId, Instant.now());
            log.info("Sent scheduled summary to chat {} ({} messages)", chatId, messages.size());
            return true;
        } catch (Exception exception) {
            log.error("Failed to send scheduled summary for chat {}", chatId, exception);
            var chatTitle = chatSummaryBot.getChatTitle(chatId);
            adminNotificationService.notifyOnFailure(chatId, chatTitle, "Scheduled Summary", exception, true);
            return false;
        }
    }

    private String withPreviousLink(long chatId, String body, Integer prevMessageId) {
        if (prevMessageId == null) {
            return body;
        }

        return TelegramLinks.messageUrl(chatId, prevMessageId)
                .map(url -> body + "\n\n<a href=\"%s\">⬆️</a>".formatted(url))
                .orElse(body);
    }

    private void linkPreviousToCurrent(long chatId, Integer prevMessageId, String prevText, int currentMessageId) {
        if (prevMessageId == null || prevText == null) {
            return;
        }

        TelegramLinks.messageUrl(chatId, currentMessageId).ifPresent(url ->
                chatSummaryBot.editMessageText(chatId, prevMessageId,
                        prevText + "\n<a href=\"%s\">⬇️</a>".formatted(url)));
    }
}
