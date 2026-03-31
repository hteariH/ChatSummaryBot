package com.chatsummary.bot.scheduler

import com.chatsummary.bot.service.AdminNotificationService
import com.chatsummary.bot.service.ChatConfigService
import com.chatsummary.bot.service.GeminiSummaryService
import com.chatsummary.bot.service.MessageService
import com.chatsummary.bot.telegram.ChatSummaryBot
import com.cronutils.model.CronType
import com.cronutils.model.definition.CronDefinitionBuilder
import com.cronutils.model.time.ExecutionTime
import com.cronutils.parser.CronParser
import io.quarkus.scheduler.Scheduled
import jakarta.enterprise.context.ApplicationScoped
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@ApplicationScoped
class DailySummaryScheduler(
    private val messageService: MessageService,
    private val geminiSummaryService: GeminiSummaryService,
    private val chatSummaryBot: ChatSummaryBot,
    private val chatConfigService: ChatConfigService,
    private val adminNotificationService: AdminNotificationService
) {
    private val log = LoggerFactory.getLogger(DailySummaryScheduler::class.java)

    private val cronParser = CronParser(
        CronDefinitionBuilder.instanceDefinitionFor(CronType.SPRING)
    )

    @Scheduled(every = "5m")
    fun sendScheduledSummaries() {
        log.debug("Checking for scheduled summaries...")

        val now = ZonedDateTime.now()
        val activeChatIds = messageService.getAllActiveChatIds()

        if (activeChatIds.isEmpty()) return

        for (chatId in activeChatIds) {
            val config = chatConfigService.getChatConfig(chatId)

            val executionTime = try {
                ExecutionTime.forCron(cronParser.parse(config.cron))
            } catch (e: Exception) {
                log.warn("Invalid cron expression '{}' for chat {}: {}", config.cron, chatId, e.message)
                continue
            }

            val lastProcessedInstant = config.lastProcessedAt
                ?: LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
            val lastProcessedZdt = lastProcessedInstant.atZone(ZoneId.systemDefault())

            log.debug("Checking chat {} for scheduled summary (last processed: {}, cron: {})", chatId, lastProcessedZdt, config.cron)

            val nextExecution = executionTime.nextExecution(lastProcessedZdt).orElse(null)

            if (nextExecution != null && !nextExecution.isAfter(now)) {
                if (processSummary(chatId, lastProcessedInstant)) {
                    chatConfigService.updateLastProcessedAt(chatId, now.toInstant())
                }
                Thread.sleep(2000L * 60) // Sleep 2 minutes between chats to avoid overloading Gemini API
            }
        }
    }

    private fun processSummary(chatId: Long, since: Instant): Boolean {
        return try {
            val messages = messageService.getMessagesSince(chatId, since)
            if (messages.isEmpty()) {
                log.info("No new messages for chat {} since {}, skipping scheduled summary.", chatId, since)
                return true
            }

            log.info("Sending scheduled summary for chat {} since {}...", chatId, since)
            val summary = geminiSummaryService.summarize(messages)
            chatSummaryBot.sendMessage(chatId, "📋 *Summary*\n\n$summary")

            messageService.clearOldMessages(chatId, Instant.now())
            log.info("Sent scheduled summary to chat {} ({} messages)", chatId, messages.size)
            true
        } catch (e: Exception) {
            log.error("Failed to send scheduled summary for chat {}", chatId, e)
            adminNotificationService.notifyOnFailure(chatId, "Scheduled Summary", e, isScheduled = true)
            false
        }
    }
}
