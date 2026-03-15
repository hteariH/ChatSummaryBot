package com.chatsummary.bot.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.index.Indexed
import java.time.Instant

@Document(collection = "chat_messages")
data class ChatMessage(
    @Id
    val id: String? = null,

    @Indexed
    val chatId: Long,

    val senderName: String,

    val text: String,

    @Indexed
    val timestamp: Instant = Instant.now()
)
