package com.chatsummary.bot.telegram

import com.chatsummary.bot.service.GeminiSummaryService
import com.chatsummary.bot.service.MessageService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update

@Component
class ChatSummaryBot(
    @param:Value("\${telegram.bot.token}") private val botToken: String,
    @param:Value("\${summary.admin-chat-id}") private val adminChatId: Long,
    private val messageService: MessageService,
    private val geminiSummaryService: GeminiSummaryService
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private val log = LoggerFactory.getLogger(ChatSummaryBot::class.java)
    private val telegramClient = OkHttpTelegramClient(botToken)

    override fun getBotToken(): String = botToken

    override fun getUpdatesConsumer(): LongPollingUpdateConsumer = this

    override fun consume(update: Update) {
        if (!update.hasMessage()) return
        val message = update.message

        // Only process text messages
        if (!message.hasText()) return

        val chatId = message.chatId
        val text = message.text
        val senderName = message.from?.let { user ->
            listOfNotNull(user.firstName, user.lastName).joinToString(" ").ifBlank { user.userName ?: "Unknown" }
        } ?: "Unknown"

        // Handle /summary command
        if (text.startsWith("/summary")) {
            handleSummaryCommand(chatId)
            return
        }

        // Save regular message (skip other bot commands)
        if (!text.startsWith("/")) {
            messageService.saveMessage(chatId, senderName, text)
        }
    }

    private fun handleSummaryCommand(chatId: Long) {
        try {
            val messages = messageService.getTodayMessages(chatId)

            if (messages.isEmpty()) {
                sendMessage(chatId, "📭 No messages recorded today. Nothing to summarize!")
                return
            }

            sendMessage(chatId, "⏳ Generating summary of ${messages.size} messages...")

            val summary = geminiSummaryService.summarize(messages)
            sendMessage(chatId, "📋 *Daily Summary*\n\n$summary")
        } catch (e: Exception) {
            log.error("Error handling /summary command for chat {}", chatId, e)
            sendMessage(chatId, "⚠️ Sorry, failed to generate summary. Please try again later.")
            notifyAdminOnFailure(chatId, "Summary generation (/summary command)", e)
        }
    }

    fun sendMessage(chatId: Long, text: String) {
        try {
            val message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build()
            telegramClient.execute(message)
        } catch (e: Exception) {
            log.error("Failed to send message to chat {}", chatId, e)
        }
    }

    private fun notifyAdminOnFailure(chatId: Long, operation: String, e: Exception) {
        if (chatId == adminChatId) return // Avoid loop or redundant message
        val errorMsg = """
            🚨 *Failure Alert*
            *Operation:* $operation
            *Chat ID:* $chatId
            *Error:* ${e.message ?: "Unknown error"}
        """.trimIndent()
        sendMessage(adminChatId, errorMsg)
    }
}
