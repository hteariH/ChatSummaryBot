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

    // Overridable in tests to avoid real sleeps.
    long geminiThrottleMillis = GEMINI_THROTTLE_MILLIS;

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
            boolean summaryAttempted;
            try {
                summaryAttempted = checkAndProcessChat(chatId, now);
            } catch (Exception exception) {
                // A broken config/cron for one chat must not abort the whole run.
                log.error("Failed to evaluate schedule for chat {}", chatId, exception);
                adminNotificationService.notifyOnFailure(
                        chatId, chatSummaryBot.getChatTitle(chatId), "Scheduled Summary", exception, true);
                continue;
            }
            if (summaryAttempted) {
                Thread.sleep(geminiThrottleMillis);
            }
        }
    }

    private boolean checkAndProcessChat(long chatId, ZonedDateTime now) {
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
        if (nextExecution == null || nextExecution.isAfter(now)) {
            return false;
        }

        var processed = processSummary(
                chatId,
                lastProcessedInstant,
                config.getLanguage(),
                config.getCustomPrompt()
        );
        if (processed) {
            chatConfigService.updateLastProcessedAt(chatId, now.toInstant());
        }
        return true;
    }

    private boolean processSummary(long chatId, Instant since, String language, String customPrompt) {
        int messageCount;
        String summary;
        Integer prevMessageId;
        Integer prevTailMessageId;
        String prevTailText;
        boolean fullAccess;
        String fullTextToSend;
        ChatSummaryBot.SentMessageResult sentSummary;
        try {
            var messages = messageService.getMessagesSince(chatId, since);
            if (messages.isEmpty()) {
                log.info("No new messages for chat {} since {}, skipping scheduled summary.", chatId, since);
                return true;
            }
            messageCount = messages.size();

            log.info("Sending scheduled summary for chat {} since {}...", chatId, since);
            summary = geminiSummaryService.summarize(messages, language, customPrompt);

            var config = chatConfigService.getChatConfig(chatId);
            prevMessageId = config.getLastSummaryMessageId();
            prevTailMessageId = firstNonNull(config.getLastSummaryTailMessageId(), prevMessageId);
            prevTailText = firstNonNull(config.getLastSummaryTailText(), config.getLastSummaryText());

            // Chats that have run out of credits only get a short teaser + a pay prompt; the full
            // text is stashed below so it can be revealed in place once the chat pays.
            fullAccess = adService.hasFullSummaryAccess(chatId);
            fullTextToSend = withPreviousLink(chatId, "📋 *Summary*\n\n" + summary, prevMessageId);
            var textToSend = fullAccess
                    ? fullTextToSend
                    : withPreviousLink(chatId,
                            "📋 *Summary*\n\n" + adService.buildPaywalledSummary(chatId, summary), prevMessageId);
            sentSummary = chatSummaryBot.sendSummaryMessage(chatId, textToSend);
        } catch (Exception exception) {
            log.error("Failed to send scheduled summary for chat {}", chatId, exception);
            var chatTitle = chatSummaryBot.getChatTitle(chatId);
            adminNotificationService.notifyOnFailure(chatId, chatTitle, "Scheduled Summary", exception, true);
            return false;
        }

        if (sentSummary == null || sentSummary.firstMessageId() == null || sentSummary.lastMessageId() == null) {
            log.error("Failed to deliver scheduled summary to chat {}; not consuming credit or clearing messages",
                    chatId);
            return false;
        }

        // The summary is already delivered: a failure below must not roll back the watermark,
        // otherwise the next tick would regenerate and re-send a duplicate.
        try {
            linkPreviousToCurrent(chatId, prevTailMessageId, prevTailText, sentSummary.firstMessageId());
            chatConfigService.updateLastSummary(
                    chatId,
                    sentSummary.firstMessageId(),
                    sentSummary.lastMessageId(),
                    sentSummary.lastChunk());

            if (chatConfigService.getChatConfig(chatId).isMonthlySummaryEnabled()) {
                messageService.saveDailySummary(chatId, summary);
            }

            if (fullAccess) {
                chatConfigService.clearPendingFullSummary(chatId);
            } else {
                chatConfigService.setPendingFullSummary(chatId, sentSummary.firstMessageId(), fullTextToSend);
            }

            adService.applyPaywallAfterSummary(chatId);

            messageService.clearOldMessages(chatId, Instant.now());
            log.info("Sent scheduled summary to chat {} ({} messages)", chatId, messageCount);
        } catch (Exception exception) {
            log.error("Post-processing failed for delivered summary in chat {}", chatId, exception);
            var chatTitle = chatSummaryBot.getChatTitle(chatId);
            adminNotificationService.notifyOnFailure(chatId, chatTitle, "Scheduled Summary Post-Processing",
                    exception, true);
        }
        return true;
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

        TelegramLinks.messageUrl(chatId, currentMessageId).ifPresent(url -> {
            var updatedText = prevText + "\n<a href=\"%s\">⬇️</a>".formatted(url);
            if (ChatSummaryBot.splitMessage(updatedText).size() > 1) {
                log.warn("Skipping previous summary navigation update for chat {} because stored tail text is too long",
                        chatId);
                return;
            }

            chatSummaryBot.editMessageText(chatId, prevMessageId, updatedText);
        });
    }

    private static <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
