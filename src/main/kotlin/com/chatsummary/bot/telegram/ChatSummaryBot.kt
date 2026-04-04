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
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Component
class ChatSummaryBot(
    @param:Value($$"${telegram.bot.token}") private val botToken: String,
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
        // Handle donation pre-checkout approval
        if (update.hasPreCheckoutQuery()) {
            val query = update.preCheckoutQuery
            telegramClient.execute(AnswerPreCheckoutQuery(query.id, true))
            return
        }

        if (!update.hasMessage()) return
        val message = update.message

        // Handle successful payment — top up credits for this chat
        if (message.hasSuccessfulPayment()) {
            val payment = message.successfulPayment
            val stars = payment.totalAmount
            val creditsAdded = stars * 30
            val donorName = message.from?.let { u ->
                listOfNotNull(u.firstName, u.lastName).joinToString(" ").ifBlank { u.userName ?: "Someone" }
            } ?: "Someone"
            chatConfigService.addSummaryCredits(message.chatId, stars)
            sendMessage(message.chatId, "Thank you, $donorName! Added $creditsAdded summaries to this chat's balance.")
            adminNotificationService.notifyDonation(message.chatId, donorName, stars)
            return
        }

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

        // Handle /setcron command
        if (text.startsWith("/setcron")) {
            handleSetCronCommand(chatId, text)
            return
        }

        // Handle /donate command
        if (text.startsWith("/donate")) {
            handleDonateCommand(chatId)
            return
        }

        // Save regular message (skip other bot commands)
        if (!text.startsWith("/")) {
            messageService.saveMessage(chatId, senderName, text)
        }
    }

    private fun handleDonateCommand(chatId: Long) {
        sendSupportInvoice(chatId)
    }

    fun sendSupportInvoice(chatId: Long) {
        val invoice = SendInvoice.builder()
            .chatId(chatId.toString())
            .title("Remove ads & support the bot")
            .description("1 ⭐ = 30 ad-free summaries for this chat. Thank you for your support!")
            .payload("summary_credits")
            .currency("XTR")
            .price(LabeledPrice("1 Star = 30 summaries", 1))
            .build()
        try {
            telegramClient.execute(invoice)
        } catch (e: Exception) {
            log.error("Failed to send invoice to chat {}", chatId, e)
        }
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

            val summary = geminiSummaryService.summarize(messages)
            sendMessage(chatId, "📋 *Summary*\n\n$summary")
            chatConfigService.updateLastProcessedAt(chatId, Instant.now())

            val remaining = chatConfigService.consumeSummaryCredit(chatId)
            if (remaining == 0) {
                sendSupportInvoice(chatId)
            }
        } catch (e: Exception) {
            log.error("Error handling /summary command for chat {}", chatId, e)
            sendMessage(chatId, "⚠️ Sorry, failed to generate summary. Please try again later.")
            adminNotificationService.notifyOnFailure(chatId, "Summary generation (/summary command)", e)
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
