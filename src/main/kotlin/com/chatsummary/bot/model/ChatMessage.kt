package com.chatsummary.bot.model

import org.bson.types.Binary
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

    // Добавляем список вложенных изображений
    val attachments: List<ChatAttachment> = emptyList(),

    @Indexed
    val timestamp: Instant = Instant.now()
)

// Вспомогательный класс для вложенного документа
data class ChatAttachment(
    val contentType: String, // например, "image/jpeg"
    val data: Binary          // Сами байты картинки
)