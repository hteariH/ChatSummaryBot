package com.chatsummary.bot.service

import com.chatsummary.bot.model.ChatMessage
import com.chatsummary.bot.repository.ChatMessageRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class MessageService(
    private val chatMessageRepository: ChatMessageRepository
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
