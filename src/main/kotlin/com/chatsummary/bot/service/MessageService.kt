package com.chatsummary.bot.service

import com.chatsummary.bot.model.ChatMessage
import jakarta.enterprise.context.ApplicationScoped
import org.slf4j.LoggerFactory
import java.time.Instant

@ApplicationScoped
class MessageService {
    private val log = LoggerFactory.getLogger(MessageService::class.java)

    fun saveMessage(chatId: Long, senderName: String, text: String) {
        val message = ChatMessage().apply {
            this.chatId = chatId
            this.senderName = senderName
            this.text = text
            this.timestamp = Instant.now()
        }
        message.persist()
        log.debug("Saved message from '{}' in chat {}", senderName, chatId)
    }

    fun getMessagesSince(chatId: Long, since: Instant): List<ChatMessage> =
        ChatMessage.findByChatIdAndTimestampAfter(chatId, since)

    fun clearOldMessages(chatId: Long, before: Instant) {
        ChatMessage.deleteByChatIdAndTimestampBefore(chatId, before)
        log.info("Cleared messages before {} in chat {}", before, chatId)
    }

    fun getAllActiveChatIds(): Set<Long> =
        ChatMessage.findDistinctChatIds().toSet()
}
