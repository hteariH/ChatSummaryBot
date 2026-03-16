package com.chatsummary.bot.service

import com.chatsummary.bot.telegram.ChatSummaryBot
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

@Service
class AdminNotificationService(
    @param:Value("\${summary.admin-chat-id}") private val adminChatId: Long,
    @param:Lazy private val chatSummaryBot: ChatSummaryBot
) {
    private val log = LoggerFactory.getLogger(AdminNotificationService::class.java)

    fun notifyOnFailure(chatId: Long, operation: String, e: Exception, isScheduled: Boolean = false) {
        if (chatId == adminChatId) return // Avoid loop or redundant message
        
        val header = if (isScheduled) "🚨 *Failure Alert (Scheduled)*" else "🚨 *Failure Alert*"
        val errorMsg = """
            $header
            *Operation:* $operation
            *Chat ID:* $chatId
            *Error:* ${e.message ?: "Unknown error"}
        """.trimIndent()
        
        chatSummaryBot.sendMessage(adminChatId, errorMsg)
        log.info("Notified admin about failure in operation: {}", operation)
    }
}
