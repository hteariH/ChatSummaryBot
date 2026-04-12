package com.chatsummary.bot.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.index.Indexed
import java.time.Instant

@Document(collection = "chat_configs")
data class ChatConfig(
    @Id
    val id: String? = null,

    @Indexed(unique = true)
    val chatId: Long,

    var cron: String,

    var lastProcessedAt: Instant? = null,

    var summaryCredits: Int = 30,

    var language: String = "English",

    var enabled: Boolean = true
)
