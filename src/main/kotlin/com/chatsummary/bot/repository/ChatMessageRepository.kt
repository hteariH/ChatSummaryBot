package com.chatsummary.bot.repository

import com.chatsummary.bot.model.ChatMessage
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.data.mongodb.repository.Query
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface ChatMessageRepository : MongoRepository<ChatMessage, String> {

    fun findByChatIdAndTimestampAfter(chatId: Long, since: Instant): List<ChatMessage>

    fun deleteByChatIdAndTimestampBefore(chatId: Long, before: Instant)

    @Query(value = "{}", fields = "{ 'chatId': 1 }")
    fun findAllChatIds(): List<ChatIdOnly>
}

data class ChatIdOnly(val chatId: Long)
