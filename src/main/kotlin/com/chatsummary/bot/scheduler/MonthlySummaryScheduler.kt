package com.chatsummary.bot.scheduler

import com.chatsummary.bot.service.AdminNotificationService
import com.chatsummary.bot.service.ChatConfigService
import com.chatsummary.bot.service.GeminiSummaryService
import com.chatsummary.bot.service.MessageService
import com.chatsummary.bot.telegram.ChatSummaryBot
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

@Component
class MonthlySummaryScheduler(
    private val messageService: MessageService,
    private val geminiSummaryService: GeminiSummaryService,
    private val chatSummaryBot: ChatSummaryBot,
    private val chatConfigService: ChatConfigService,
    private val adminNotificationService: AdminNotificationService
) {
    private val log = LoggerFactory.getLogger(MonthlySummaryScheduler::class.java)

    @Scheduled(cron = "0 0 19 L * *") // Last day of month at 19:00:00
    fun sendMonthlySummaries() {
        log.info("Starting scheduled monthly summaries...")

        val now = ZonedDateTime.now()
        val allConfigs = chatConfigService.getAllConfigs()

        for (config in allConfigs) {
            if (!config.monthlySummaryEnabled) continue

            val chatId = config.chatId
            
            // Check if already processed this month to avoid duplicates if scheduler runs twice or after restart
            val lastProcessed = config.lastMonthlyProcessedAt?.atZone(ZoneId.systemDefault())
            if (lastProcessed != null && lastProcessed.month == now.month && lastProcessed.year == now.year) {
                log.debug("Monthly summary for chat {} already processed this month, skipping.", chatId)
                continue
            }

            processMonthlySummary(chatId, config.language)
            chatConfigService.updateLastMonthlyProcessedAt(chatId, now.toInstant())
            
            // Avoid Gemini rate limits
            Thread.sleep(2000 * 60)
        }
    }

    private fun processMonthlySummary(chatId: Long, language: String) {
        try {
            val now = ZonedDateTime.now()
            val firstDayOfMonth = now.with(TemporalAdjusters.firstDayOfMonth()).withHour(0).withMinute(0).withSecond(0).toInstant()
            
            val dailySummaries = messageService.getDailySummariesSince(chatId, firstDayOfMonth)
            if (dailySummaries.isEmpty()) {
                log.info("No daily summaries for chat {} this month, skipping monthly summary.", chatId)
                return
            }

            log.info("Generating monthly summary for chat {} ({} daily summaries)...", chatId, dailySummaries.size)
            val monthlySummary = geminiSummaryService.summarizeMonthly(dailySummaries, language)
            
            chatSummaryBot.sendMessage(chatId, "📅 *Monthly Digest*\n\n$monthlySummary")
            
            // Optional: clear old daily summaries after monthly one is sent
            messageService.clearOldDailySummaries(chatId, firstDayOfMonth)
            
            log.info("Sent monthly summary to chat {}", chatId)
        } catch (e: Exception) {
            log.error("Failed to send monthly summary for chat {}", chatId, e)
            val chatTitle = chatSummaryBot.getChatTitle(chatId)
            adminNotificationService.notifyOnFailure(chatId, chatTitle, "Monthly Summary", e, isScheduled = true)
        }
    }
}
