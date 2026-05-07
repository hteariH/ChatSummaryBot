package com.chatsummary.bot.model

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.index.Indexed
import java.time.Instant

@Document(collection = "daily_summaries")
data class DailySummary(
    @Id
    val id: String? = null,

    @Indexed
    val chatId: Long,

    val text: String,

    @Indexed
    val timestamp: Instant = Instant.now()
)
