package com.chatsummary.bot.telegram

import com.chatsummary.bot.service.AdminNotificationService
import com.chatsummary.bot.service.BotMessageSender
import com.chatsummary.bot.service.ChatConfigService
import com.chatsummary.bot.service.GeminiSummaryService
import com.chatsummary.bot.service.MessageService
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.parser.CronParser
import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.slf4j.LoggerFactory
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer
import org.telegram.telegrambots.meta.api.methods.send.SendMessage
import org.telegram.telegrambots.meta.api.objects.Update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@ApplicationScoped
class ChatSummaryBot(
    private val messageService: MessageService,
    private val geminiSummaryService: GeminiSummaryService,
    private val chatConfigService: ChatConfigService,
    private val adminNotificationService: AdminNotificationService
) : BotMessageSender, LongPollingSingleThreadUpdateConsumer {

    private val log = LoggerFactory.getLogger(ChatSummaryBot::class.java)

    @ConfigProperty(name = "telegram.bot.token")
    lateinit var botToken: String

    private lateinit var telegramClient: OkHttpTelegramClient
    private lateinit var botsApplication: TelegramBotsLongPollingApplication

    fun onStart(@Observes event: StartupEvent) {
        telegramClient = OkHttpTelegramClient(botToken)
        botsApplication = TelegramBotsLongPollingApplication()
        botsApplication.registerBot(botToken, this)
        log.info("ChatSummaryBot started and registered for long polling")
    }

    override fun consume(update: Update) {
        if (!update.hasMessage()) return
        val message = update.message

        if (!message.hasText()) return

        val chatId = message.chatId
        val text = message.text
        val senderName = message.from?.let { user ->
            listOfNotNull(user.firstName, user.lastName).joinToString(" ").ifBlank { user.userName ?: "Unknown" }
        } ?: "Unknown"

        when {
            text.startsWith("/summary") -> handleSummaryCommand(chatId)
            text.startsWith("/setcron") -> handleSetCronCommand(chatId, text)
            !text.startsWith("/") -> messageService.saveMessage(chatId, senderName, text)
        }
    }

    private fun handleSetCronCommand(chatId: Long, text: String) {
        val parts = text.split(" ", limit = 2)
        if (parts.size < 2) {
            sendMessage(chatId, "⚠️ Usage: `/setcron 0 0 21 * * *` (seconds minutes hours day month day-of-week)")
            return
        }

        val cron = parts[1].trim()
        if (!isValidCronExpression(cron)) {
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
        } catch (e: Exception) {
            log.error("Error handling /summary command for chat {}", chatId, e)
            sendMessage(chatId, "⚠️ Sorry, failed to generate summary. Please try again later.")
            adminNotificationService.notifyOnFailure(chatId, "Summary generation (/summary command)", e)
        }
    }

    override fun sendMessage(chatId: Long, text: String) {
        try {
            val msg = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build()
            telegramClient.execute(msg)
        } catch (e: Exception) {
            log.error("Failed to send message to chat {}", chatId, e)
        }
    }

    private val cronParser = CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING)
    )

    private fun isValidCronExpression(cron: String): Boolean {
        return try {
            cronParser.parse(cron)
            true
        } catch (e: Exception) {
            false
        }
    }
}
