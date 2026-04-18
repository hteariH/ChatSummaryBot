package com.chatsummary.bot.scheduler

import com.chatsummary.bot.service.AdminNotificationService
import com.chatsummary.bot.service.ChatConfigService
import com.chatsummary.bot.service.GeminiSummaryService
import com.chatsummary.bot.service.MessageService
import com.chatsummary.bot.telegram.ChatSummaryBot
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@Component
class DailySummaryScheduler(
    private val messageService: MessageService,
    private val geminiSummaryService: GeminiSummaryService,
    private val chatSummaryBot: ChatSummaryBot,
    private val chatConfigService: ChatConfigService,
    private val adminNotificationService: AdminNotificationService
) {
    private val log = LoggerFactory.getLogger(DailySummaryScheduler::class.java)

    @Scheduled(fixedRate = 60000) // Check every 1 minutes
    fun sendScheduledSummaries() {
        log.debug("Checking for scheduled summaries...")

        val now = ZonedDateTime.now()
        val activeChatIds = messageService.getAllActiveChatIds()
        
        if (activeChatIds.isEmpty()) return

        for (chatId in activeChatIds) {
            val config = chatConfigService.getChatConfig(chatId)
            val cron = CronExpression.parse(config.cron)
            
            // Determine the start point for checking missed summaries
            // If never processed, start from beginning of today
            val lastProcessedInstant = config.lastProcessedAt ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val lastProcessedZdt = lastProcessedInstant.atZone(ZoneId.systemDefault())

            log.debug("Checking chat {} for scheduled summary (last processed: {}, cron: {})", chatId, lastProcessedZdt, config.cron)

            // Find the next scheduled execution time after the last processed time
            val nextExecution = cron.next(lastProcessedZdt)

            // If the next execution time is in the past or is exactly now (truncated to minutes), process it
            if (nextExecution != null && !nextExecution.isAfter(now)) {
                if (processSummary(chatId, lastProcessedInstant, config.language, config.customPrompt)) {
                    chatConfigService.updateLastProcessedAt(chatId, now.toInstant())
                }
                Thread.sleep(2000*60) // Sleep for 2 minute before checking the next chat to not overload the gemini API
            }
        }
    }

    private fun processSummary(chatId: Long, since: Instant, language: String = "English", customPrompt: String? = null): Boolean {
        try {
            val messages = messageService.getMessagesSince(chatId, since)
            if (messages.isEmpty()) {
                log.info("No new messages for chat {} since {}, skipping scheduled summary.", chatId, since)
                return true
            }

            log.info("Sending scheduled summary for chat {} since {}...", chatId, since)
            val summary = geminiSummaryService.summarize(messages, language, customPrompt)
            chatSummaryBot.sendMessage(chatId, "📋 *Summary*\n\n$summary")

            val remaining = chatConfigService.consumeSummaryCredit(chatId)
            if (remaining == 0) {
                chatSummaryBot.sendAdWithRemoveOption(chatId)
            }

            // Clear old messages after summary is sent
            messageService.clearOldMessages(chatId, Instant.now())
            log.info("Sent scheduled summary to chat {} ({} messages)", chatId, messages.size)
            return true
        } catch (e: Exception) {
            log.error("Failed to send scheduled summary for chat {}", chatId, e)
            val chatTitle = chatSummaryBot.getChatTitle(chatId)
            adminNotificationService.notifyOnFailure(chatId, chatTitle, "Scheduled Summary", e, isScheduled = true)
            return false
        }
    }
}
