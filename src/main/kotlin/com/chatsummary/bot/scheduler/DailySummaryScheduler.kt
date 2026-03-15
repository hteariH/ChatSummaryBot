package com.chatsummary.bot.scheduler

import com.chatsummary.bot.service.GeminiSummaryService
import com.chatsummary.bot.service.MessageService
import com.chatsummary.bot.telegram.ChatSummaryBot
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class DailySummaryScheduler(
    @param:Value("\${summary.admin-chat-id}") private val adminChatId: Long,
    private val messageService: MessageService,
    private val geminiSummaryService: GeminiSummaryService,
    private val chatSummaryBot: ChatSummaryBot
) {
    private val log = LoggerFactory.getLogger(DailySummaryScheduler::class.java)

    @Scheduled(cron = "\${summary.cron}")
    fun sendDailySummaries() {
        log.info("Starting scheduled daily summary...")

        val chatIds = messageService.getAllActiveChatIds()
        if (chatIds.isEmpty()) {
            log.info("No active chats found, skipping summary.")
            return
        }

        for (chatId in chatIds) {
            try {
                val messages = messageService.getTodayMessages(chatId)
                if (messages.isEmpty()) {
                    log.info("No messages today for chat {}, skipping.", chatId)
                    continue
                }

                val summary = geminiSummaryService.summarize(messages)
                chatSummaryBot.sendMessage(chatId, "📋 *End-of-Day Summary*\n\n$summary")

                // Clear old messages after summary is sent
                messageService.clearOldMessages(chatId, Instant.now())
                log.info("Sent daily summary to chat {} ({} messages)", chatId, messages.size)
            } catch (e: Exception) {
                log.error("Failed to send daily summary for chat {}", chatId, e)
                notifyAdminOnFailure(chatId, "Scheduled Daily Summary", e)
            }
        }

        log.info("Daily summary completed for {} chats.", chatIds.size)
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
