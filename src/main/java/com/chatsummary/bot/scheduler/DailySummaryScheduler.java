package com.chatsummary.bot.scheduler;

import com.chatsummary.bot.service.AdminNotificationService;
import com.chatsummary.bot.service.ChatConfigService;
import com.chatsummary.bot.service.GeminiSummaryService;
import com.chatsummary.bot.service.MessageService;
import com.chatsummary.bot.telegram.ChatSummaryBot;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Component
public class DailySummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryScheduler.class);
    private static final long GEMINI_THROTTLE_MILLIS = 2_000L * 60L;

    private final MessageService messageService;
    private final GeminiSummaryService geminiSummaryService;
    private final ChatSummaryBot chatSummaryBot;
    private final ChatConfigService chatConfigService;
    private final AdminNotificationService adminNotificationService;

    public DailySummaryScheduler(
            MessageService messageService,
            GeminiSummaryService geminiSummaryService,
            ChatSummaryBot chatSummaryBot,
            ChatConfigService chatConfigService,
            AdminNotificationService adminNotificationService
    ) {
        this.messageService = messageService;
        this.geminiSummaryService = geminiSummaryService;
        this.chatSummaryBot = chatSummaryBot;
        this.chatConfigService = chatConfigService;
        this.adminNotificationService = adminNotificationService;
    }

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
            chatSummaryBot.sendMessage(chatId, "📋 *Summary*\n\n" + summary);

            if (chatConfigService.getChatConfig(chatId).isMonthlySummaryEnabled()) {
                messageService.saveDailySummary(chatId, summary);
            }

            var remaining = chatConfigService.consumeSummaryCredit(chatId);
            if (remaining == 0) {
                chatSummaryBot.sendAdWithRemoveOption(chatId);
            }

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
}
