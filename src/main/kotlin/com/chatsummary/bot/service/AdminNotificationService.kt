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

    fun notifyNewChat(chatId: Long, chatTitle: String, chatType: String, addedBy: String) {
        val msg = "🆕 *Бот добавлен в новый чат!*\n*Название:* $chatTitle\n*Тип:* $chatType\n*ID:* $chatId\n*Добавил:* $addedBy"
        chatSummaryBot.sendMessage(adminChatId, msg)
        log.info("Bot added to new chat: {} ({}), by {}", chatTitle, chatId, addedBy)
    }

    fun notifyPayment(chatId: Long, donorName: String, stars: Int, creditsAdded: Int) {
        val msg = "⭐ *Оплата получена!*\n*От:* $donorName\n*Чат:* $chatId\n*Звёзд:* $stars\n*Добавлено саммари:* $creditsAdded"
        chatSummaryBot.sendMessage(adminChatId, msg)
        log.info("Payment of {} star(s) from {} in chat {}, added {} credits", stars, donorName, chatId, creditsAdded)
    }

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
