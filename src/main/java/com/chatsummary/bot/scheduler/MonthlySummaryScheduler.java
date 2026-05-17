package com.chatsummary.bot.scheduler;

import com.chatsummary.bot.service.AdminNotificationService;
import com.chatsummary.bot.service.ChatConfigService;
import com.chatsummary.bot.service.GeminiSummaryService;
import com.chatsummary.bot.service.MessageService;
import com.chatsummary.bot.telegram.ChatSummaryBot;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.TemporalAdjusters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MonthlySummaryScheduler {

    private static final Logger log = LoggerFactory.getLogger(MonthlySummaryScheduler.class);
    private static final long GEMINI_THROTTLE_MILLIS = 2_000L * 60L;

    private final MessageService messageService;
    private final GeminiSummaryService geminiSummaryService;
    private final ChatSummaryBot chatSummaryBot;
    private final ChatConfigService chatConfigService;
    private final AdminNotificationService adminNotificationService;

    public MonthlySummaryScheduler(
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

    @Scheduled(cron = "0 0 19 L * *")
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

            processMonthlySummary(chatId, config.getLanguage());
            chatConfigService.updateLastMonthlyProcessedAt(chatId, now.toInstant());
            Thread.sleep(GEMINI_THROTTLE_MILLIS);
        }
    }

    private void processMonthlySummary(long chatId, String language) {
        try {
            var now = ZonedDateTime.now();
            var firstDayOfMonth = now.with(TemporalAdjusters.firstDayOfMonth())
                    .withHour(0)
                    .withMinute(0)
                    .withSecond(0)
                    .toInstant();

            var dailySummaries = messageService.getDailySummariesSince(chatId, firstDayOfMonth);
            if (dailySummaries.isEmpty()) {
                log.info("No daily summaries for chat {} this month, skipping monthly summary.", chatId);
                return;
            }

            log.info("Generating monthly summary for chat {} ({} daily summaries)...", chatId, dailySummaries.size());
            var monthlySummary = geminiSummaryService.summarizeMonthly(dailySummaries, language);

            chatSummaryBot.sendMessage(chatId, "📅 *Monthly Digest*\n\n" + monthlySummary);
            messageService.clearOldDailySummaries(chatId, firstDayOfMonth);

            log.info("Sent monthly summary to chat {}", chatId);
        } catch (Exception exception) {
            log.error("Failed to send monthly summary for chat {}", chatId, exception);
            var chatTitle = chatSummaryBot.getChatTitle(chatId);
            adminNotificationService.notifyOnFailure(chatId, chatTitle, "Monthly Summary", exception, true);
        }
    }
}
