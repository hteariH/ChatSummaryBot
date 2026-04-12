package com.chatsummary.bot.telegram

import com.chatsummary.bot.service.AdminNotificationService
import com.chatsummary.bot.service.ChatConfigService
import com.chatsummary.bot.service.GeminiSummaryService
import com.chatsummary.bot.service.MessageService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Component
class ChatSummaryBot(
    @param:Value("\${telegram.bot.token}") private val botToken: String,
    private val messageService: MessageService,
    private val geminiSummaryService: GeminiSummaryService,
    private val chatConfigService: ChatConfigService,
    private val adminNotificationService: AdminNotificationService
) : SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private val log = LoggerFactory.getLogger(ChatSummaryBot::class.java)
    private val telegramClient = OkHttpTelegramClient(botToken)

    override fun getBotToken(): String = botToken

    override fun getUpdatesConsumer(): LongPollingUpdateConsumer = this

    override fun consume(update: Update) {
        if (update.hasPreCheckoutQuery()) {
            val query = update.preCheckoutQuery
            telegramClient.execute(AnswerPreCheckoutQuery(query.id, true))
            return
        }

        if (update.hasMyChatMember()) {
            val member = update.myChatMember
            val oldStatus = member.oldChatMember.status
            val newStatus = member.newChatMember.status
            if (oldStatus in listOf("left", "kicked") && newStatus in listOf("member", "administrator")) {
                val chat = member.chat
                val addedBy = member.from?.let { u ->
                    listOfNotNull(u.firstName, u.lastName).joinToString(" ").ifBlank { u.userName ?: "Unknown" }
                } ?: "Unknown"
                adminNotificationService.notifyNewChat(chat.id, chat.title ?: "Unknown", chat.type, addedBy)
            }
            return
        }

        if (!update.hasMessage()) return
        val message = update.message

        if (message.hasSuccessfulPayment()) {
            val payment = message.successfulPayment
            val stars = payment.totalAmount
            val creditsAdded = stars
            val donorName = message.from?.let { u ->
                listOfNotNull(u.firstName, u.lastName).joinToString(" ").ifBlank { u.userName ?: "Someone" }
            } ?: "Someone"
            chatConfigService.addSummaryCredits(message.chatId, stars)
            sendMessage(message.chatId, "✅ Спасибо, $donorName! Добавлено $creditsAdded саммари без рекламы.")
            adminNotificationService.notifyPayment(message.chatId, donorName, stars, creditsAdded)
            return
        }

        if (!message.hasText()) return

        val chatId = message.chatId
        val text = message.text
        val senderName = message.from?.let { user ->
            listOfNotNull(user.firstName, user.lastName).joinToString(" ").ifBlank { user.userName ?: "Unknown" }
        } ?: "Unknown"

        if (text.startsWith("/summary")) {
            if (!isUserAdmin(chatId, message.from!!.id)) {
                sendMessage(chatId, "⛔ Only group admins can use this command.")
                return
            }
            handleSummaryCommand(chatId)
            return
        }

        if (text.startsWith("/setcron")) {
            if (!isUserAdmin(chatId, message.from!!.id)) {
                sendMessage(chatId, "⛔ Only group admins can use this command.")
                return
            }
            handleSetCronCommand(chatId, text)
            return
        }

        if (text.startsWith("/enable") || text.startsWith("/disable")) {
            if (!isUserAdmin(chatId, message.from!!.id)) {
                sendMessage(chatId, "⛔ Only group admins can use this command.")
                return
            }
            val enable = text.startsWith("/enable")
            chatConfigService.setEnabled(chatId, enable)
            sendMessage(chatId, if (enable) "✅ Bot enabled for this chat. Messages will be saved." else "🚫 Bot disabled for this chat. Messages will no longer be saved.")
            log.info("{} chat {}", if (enable) "Enabled" else "Disabled", chatId)
            return
        }

        if (text.startsWith("/setlanguage")) {
            if (!isUserAdmin(chatId, message.from!!.id)) {
                sendMessage(chatId, "⛔ Only group admins can use this command.")
                return
            }
            handleSetLanguageCommand(chatId, text)
            return
        }

        if (!text.startsWith("/")) {
            val config = chatConfigService.getChatConfig(chatId)
            if (config.enabled) {
                messageService.saveMessage(chatId, senderName, text)
            }
        }
    }

    private fun handleSetLanguageCommand(chatId: Long, text: String) {
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2 || parts[1].isBlank()) {
            sendMessage(chatId, "⚠️ Usage: /setlanguage English\nExamples: English, Russian, Spanish, German, French")
            return
        }
        val language = parts[1].trim()
        chatConfigService.setLanguage(chatId, language)
        sendMessage(chatId, "✅ Summary language set to: $language")
        log.info("Updated language for chat {}: {}", chatId, language)
    }

    private fun handleSetCronCommand(chatId: Long, text: String) {
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2) {
            sendMessage(chatId, "⚠️ Usage: `/setcron 0 0 21 * * *` (seconds minutes hours day month day-of-week)")
            return
        }

        val cron = parts[1].trim()
        if (!CronExpression.isValidExpression(cron)) {
            sendMessage(chatId, "⚠️ Invalid cron expression. Please use the Spring/Quartz format: `sec min hour day month dow`.")
            return
        }

        try {
            chatConfigService.saveChatConfig(chatId, cron)
            sendMessage(chatId, "✅ Summary schedule updated to: `$cron`")
            log.info("Updated cron for chat {}: {}", chatId, cron)
        } catch (e: Exception) {
            log.error("Failed to save cron for chat {}", chatId, e)
            sendMessage(chatId, "⚠️ Failed to save cron. Please try again.")
        }
    }

    private fun handleSummaryCommand(chatId: Long) {
        try {
            val config = chatConfigService.getChatConfig(chatId)
            val since = config.lastProcessedAt ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val messages = messageService.getMessagesSince(chatId, since)

            if (messages.isEmpty()) {
                sendMessage(chatId, "📭 No new messages since last summary. Nothing to summarize!")
                return
            }

            sendMessage(chatId, "⏳ Generating summary of ${messages.size} messages...")

            val summary = geminiSummaryService.summarize(messages, config.language)
            sendMessage(chatId, "📋 *Summary*\n\n$summary")
            chatConfigService.updateLastProcessedAt(chatId, Instant.now())

            val remaining = chatConfigService.consumeSummaryCredit(chatId)
            if (remaining == 0) {
                sendAdWithRemoveOption(chatId)
            }
        } catch (e: Exception) {
            log.error("Error handling /summary command for chat {}", chatId, e)
            sendMessage(chatId, "⚠️ Sorry, failed to generate summary. Please try again later.")
            val chatTitle = getChatTitle(chatId)
            adminNotificationService.notifyOnFailure(chatId, chatTitle, "Summary generation (/summary command)", e)
        }
    }

    fun sendAdWithRemoveOption(chatId: Long) {
//        sendMessage(chatId, "💬 Это сообщение спонсировано нашим партнёром —  VPN-ботом.\n👉 https://t.me/net4ebur_bot?startapp=eyJyIjoiS1hTVFg4In0=")
        try {
            val invoice = SendInvoice.builder()
                .chatId(chatId.toString())
                .title("Убрать рекламу")
                .description("30 ⭐ = 30 саммари без рекламы для этого чата.")
                .payload("summary_credits")
                .currency("XTR")
                .price(LabeledPrice("30 звёзд = 30 саммари", 30))
                .build()
            telegramClient.execute(invoice)
        } catch (e: Exception) {
            log.error("Failed to send invoice to chat {}", chatId, e)
        }
    }

    private fun isUserAdmin(chatId: Long, userId: Long): Boolean {
        return try {
            val member = telegramClient.execute(GetChatMember(chatId.toString(), userId))
            member.status in listOf("administrator", "creator")
        } catch (e: Exception) {
            log.warn("Failed to check admin status for user {} in chat {}", userId, chatId, e)
            false
        }
    }

    fun getChatTitle(chatId: Long): String {
        return try {
            val chat = telegramClient.execute(GetChat(chatId.toString()))
            chat.title ?: "Unknown"
        } catch (e: Exception) {
            log.warn("Failed to get chat title for {}", chatId, e)
            "Unknown"
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
}
