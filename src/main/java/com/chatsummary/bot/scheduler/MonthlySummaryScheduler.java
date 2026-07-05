package com.chatsummary.bot.scheduler;

import com.chatsummary.bot.model.ChatConfig;
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
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

@Slf4j
@RequiredArgsConstructor
@Component
public class MonthlySummaryScheduler {

    private static final long GEMINI_THROTTLE_MILLIS = 2_000L * 60L;

    /**
     * When the digest is due: last day of month at 21:00. Evaluated as a watermark against
     * lastMonthlyProcessedAt on every tick, so a failed digest is retried until it succeeds
     * (even past the month boundary) instead of being lost until next month.
     */
    private static final CronExpression MONTHLY_CRON = CronExpression.parse("0 0 21 L * *");

    // Overridable in tests to avoid real sleeps.
    long geminiThrottleMillis = GEMINI_THROTTLE_MILLIS;

    private final MessageService messageService;
    private final GeminiSummaryService geminiSummaryService;
    private final ChatSummaryBot chatSummaryBot;
    private final ChatConfigService chatConfigService;
    private final AdminNotificationService adminNotificationService;

    @Scheduled(fixedRate = 600_000)
    public void sendMonthlySummaries() throws InterruptedException {
        log.debug("Checking for due monthly summaries...");

        var now = ZonedDateTime.now();
        var allConfigs = chatConfigService.getAllConfigs();

        for (var config : allConfigs) {
            if (!config.isMonthlySummaryEnabled()) {
                continue;
            }

            boolean digestAttempted;
            try {
                digestAttempted = checkAndProcessChat(config, now);
            } catch (Exception exception) {
                // A broken config for one chat must not abort the whole run.
                log.error("Failed to evaluate monthly schedule for chat {}", config.getChatId(), exception);
                adminNotificationService.notifyOnFailure(
                        config.getChatId(), chatSummaryBot.getChatTitle(config.getChatId()),
                        "Monthly Summary", exception, true);
                continue;
            }
            if (digestAttempted) {
                Thread.sleep(geminiThrottleMillis);
            }
        }
    }

    private boolean checkAndProcessChat(ChatConfig config, ZonedDateTime now) {
        var chatId = config.getChatId();
        var baseline = config.getLastMonthlyProcessedAt() == null
                ? now.toLocalDate().withDayOfMonth(1).atStartOfDay(ZoneId.systemDefault())
                : config.getLastMonthlyProcessedAt().atZone(ZoneId.systemDefault());

        var nextExecution = MONTHLY_CRON.next(baseline);
        if (nextExecution == null || nextExecution.isAfter(now)) {
            return false;
        }

        if (processMonthlySummary(chatId, config.getLanguage())) {
            chatConfigService.updateLastMonthlyProcessedAt(chatId, now.toInstant());
        }
        return true;
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
