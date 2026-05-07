package com.chatsummary.bot.service

import com.chatsummary.bot.model.ChatMessage
import com.chatsummary.bot.model.DailySummary
import com.chatsummary.bot.repository.ChatMessageRepository
import com.chatsummary.bot.repository.DailySummaryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class MessageService(
    private val chatMessageRepository: ChatMessageRepository,
    private val dailySummaryRepository: DailySummaryRepository
) {
    private val log = LoggerFactory.getLogger(MessageService::class.java)

    fun saveMessage(chatId: Long, senderName: String, text: String) {
        val message = ChatMessage(
            chatId = chatId,
            senderName = senderName,
            text = text
        )
        chatMessageRepository.save(message)
        log.debug("Saved message from '{}' in chat {}", senderName, chatId)
    }

    fun saveDailySummary(chatId: Long, text: String) {
        val summary = DailySummary(
            chatId = chatId,
            text = text
        )
        dailySummaryRepository.save(summary)
        log.info("Saved daily summary for chat {}", chatId)
    }

    fun getDailySummariesSince(chatId: Long, since: Instant): List<DailySummary> {
        return dailySummaryRepository.findByChatIdAndTimestampAfter(chatId, since)
    }

    fun clearOldDailySummaries(chatId: Long, before: Instant) {
        dailySummaryRepository.deleteByChatIdAndTimestampBefore(chatId, before)
        log.info("Cleared daily summaries before {} in chat {}", before, chatId)
    }

    fun getMessagesSince(chatId: Long, since: Instant): List<ChatMessage> {
        return chatMessageRepository.findByChatIdAndTimestampAfter(chatId, since)
    }

    fun clearOldMessages(chatId: Long, before: Instant) {
        chatMessageRepository.deleteByChatIdAndTimestampBefore(chatId, before)
        log.info("Cleared messages before {} in chat {}", before, chatId)
    }

    fun getAllActiveChatIds(): Set<Long> {
        return chatMessageRepository.findAllChatIds()
            .map { it.chatId }
            .toSet()
    }
}
