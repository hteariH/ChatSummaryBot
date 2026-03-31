package com.chatsummary.bot.model

import io.quarkus.mongodb.panache.kotlin.PanacheMongoEntity
import io.quarkus.mongodb.panache.common.MongoEntity
import java.time.Instant

@MongoEntity(collection = "chat_configs")
class ChatConfig : PanacheMongoEntity() {
    var chatId: Long = 0
    var cron: String = ""
    var lastProcessedAt: Instant? = null

    companion object : io.quarkus.mongodb.panache.kotlin.PanacheMongoCompanion<ChatConfig> {
        fun findByChatId(chatId: Long): ChatConfig? = find("chatId", chatId).firstResult()
    }
}
