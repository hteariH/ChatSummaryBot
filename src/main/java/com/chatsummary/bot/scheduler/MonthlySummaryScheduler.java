package com.chatsummary.bot.scheduler;

import com.chatsummary.bot.service.AdminNotificationService;
import com.chatsummary.bot.service.ChatConfigService;
import com.chatsummary.bot.service.GeminiSummaryService;
import com.chatsummary.bot.service.MessageService;
import com.chatsummary.bot.telegram.ChatSummaryBot;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class MonthlySummaryScheduler {

    private static final long GEMINI_THROTTLE_MILLIS = 2_000L * 60L;

    // Overridable in tests to avoid real sleeps.
    long geminiThrottleMillis = GEMINI_THROTTLE_MILLIS;

    private final MessageService messageService;
    private final GeminiSummaryService geminiSummaryService;
    private final ChatSummaryBot chatSummaryBot;
    private final ChatConfigService chatConfigService;
    private final AdminNotificationService adminNotificationService;

    @Scheduled(cron = "0 0 21 L * *")
    public void sendMonthlySummaries() throws InterruptedException {
        log.info("Starting scheduled monthly summaries...");

        var now = ZonedDateTime.now();
        var allConfigs = chatConfigService.getAllConfigs();

        for (var config : allConfigs) {
            if (!config.isMonthlySummaryEnabled()) {
                continue;
            }

            var chatId = config.getChatId();
            var lastProcessed = config.getLastMonthlyProcessedAt() == null
                    ? null
                    : config.getLastMonthlyProcessedAt().atZone(ZoneId.systemDefault());

            if (lastProcessed != null && lastProcessed.getMonth() == now.getMonth() && lastProcessed.getYear() == now.getYear()) {
                log.debug("Monthly summary for chat {} already processed this month, skipping.", chatId);
                continue;
            }

            if (processMonthlySummary(chatId, config.getLanguage())) {
                chatConfigService.updateLastMonthlyProcessedAt(chatId, now.toInstant());
            }
            Thread.sleep(geminiThrottleMillis);
        }
    }

    private boolean processMonthlySummary(long chatId, String language) {
        try {
            var dailySummaries = messageService.getDailySummaries(chatId);
            if (dailySummaries.isEmpty()) {
                log.info("No daily summaries for chat {} this month, skipping monthly summary.", chatId);
                return true;
            }

            log.info("Generating monthly summary for chat {} ({} daily summaries)...", chatId, dailySummaries.size());
            var monthlySummary = geminiSummaryService.summarizeMonthly(dailySummaries, language);

            var sentMessageId = chatSummaryBot.sendMessageReturningId(chatId, "📅 *Monthly Digest*\n\n" + monthlySummary);
            if (sentMessageId == null) {
                log.error("Failed to deliver monthly summary to chat {}; keeping daily summaries for retry", chatId);
                return false;
            }
            messageService.clearOldDailySummaries(chatId);

            log.info("Sent monthly summary to chat {}", chatId);
            return true;
        } catch (Exception exception) {
            log.error("Failed to send monthly summary for chat {}", chatId, exception);
            var chatTitle = chatSummaryBot.getChatTitle(chatId);
            adminNotificationService.notifyOnFailure(chatId, chatTitle, "Monthly Summary", exception, true);
            return false;
        }
    }
}
