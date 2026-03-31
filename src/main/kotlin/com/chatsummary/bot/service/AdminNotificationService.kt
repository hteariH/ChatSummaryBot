package com.chatsummary.bot.service

import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.slf4j.LoggerFactory

@ApplicationScoped
class AdminNotificationService(
    @param:ConfigProperty(name = "summary.admin-chat-id") val adminChatId: Long,
    val botProvider: BotMessageSender
) {
    private val log = LoggerFactory.getLogger(AdminNotificationService::class.java)

    fun notifyOnFailure(chatId: Long, operation: String, e: Exception, isScheduled: Boolean = false) {
        if (chatId == adminChatId) return

        val header = if (isScheduled) "🚨 *Failure Alert (Scheduled)*" else "🚨 *Failure Alert*"
        val errorMsg = """
            $header
            *Operation:* $operation
            *Chat ID:* $chatId
            *Error:* ${e.message ?: "Unknown error"}
        """.trimIndent()

        botProvider.sendMessage(adminChatId, errorMsg)
        log.info("Notified admin about failure in operation: {}", operation)
    }
}
