package com.chatsummary.bot.scheduler

import com.chatsummary.bot.service.ChatConfigService
import com.chatsummary.bot.service.GeminiSummaryService
import com.chatsummary.bot.service.MessageService
import com.chatsummary.bot.telegram.ChatSummaryBot
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@Component
class DailySummaryScheduler(
    @param:Value("\${summary.admin-chat-id}") private val adminChatId: Long,
    private val messageService: MessageService,
    private val geminiSummaryService: GeminiSummaryService,
    private val chatSummaryBot: ChatSummaryBot,
    private val chatConfigService: ChatConfigService
) {
    private val log = LoggerFactory.getLogger(DailySummaryScheduler::class.java)

    @Scheduled(fixedRate = 60000) // Check every minute
    fun sendScheduledSummaries() {
        log.debug("Checking for scheduled summaries...")

        val now = ZonedDateTime.now().truncatedTo(ChronoUnit.MINUTES)
        val activeChatIds = messageService.getAllActiveChatIds()
        
        if (activeChatIds.isEmpty()) return

        for (chatId in activeChatIds) {
            val config = chatConfigService.getChatConfig(chatId)
            val cron = CronExpression.parse(config.cron)
            log.debug("Checking chat {} for scheduled summary (cron: {})", chatId, config.cron)
            // Check if it's time to run (matches current minute)
            if (cron.next(now.minusSeconds(1))?.truncatedTo(ChronoUnit.MINUTES)?.isEqual(now) == true) {
                processSummary(chatId)
            }
        }
    }

    private fun processSummary(chatId: Long) {
        try {
            val messages = messageService.getTodayMessages(chatId)
            if (messages.isEmpty()) {
                log.info("No messages today for chat {}, skipping scheduled summary.", chatId)
                return
            }

            log.info("Sending scheduled summary for chat {}...", chatId)
            val summary = geminiSummaryService.summarize(messages)
            chatSummaryBot.sendMessage(chatId, "📋 *End-of-Day Summary*\n\n$summary")

            // Clear old messages after summary is sent
            messageService.clearOldMessages(chatId, Instant.now())
            log.info("Sent scheduled summary to chat {} ({} messages)", chatId, messages.size)
        } catch (e: Exception) {
            log.error("Failed to send scheduled summary for chat {}", chatId, e)
            notifyAdminOnFailure(chatId, "Scheduled Summary", e)
        }
    }

    private fun notifyAdminOnFailure(chatId: Long, operation: String, e: Exception) {
        val errorMsg = """
            🚨 *Failure Alert (Scheduled)*
            *Operation:* $operation
            *Chat ID:* $chatId
            *Error:* ${e.message ?: "Unknown error"}
        """.trimIndent()
        chatSummaryBot.sendMessage(adminChatId, errorMsg)
    }
}
