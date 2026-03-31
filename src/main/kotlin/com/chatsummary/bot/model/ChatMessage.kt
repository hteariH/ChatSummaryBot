package com.chatsummary.bot.model

import io.quarkus.mongodb.panache.kotlin.PanacheMongoEntity
import io.quarkus.mongodb.panache.common.MongoEntity
import io.quarkus.mongodb.panache.kotlin.PanacheMongoCompanion
import java.time.Instant

@MongoEntity(collection = "chat_messages")
class ChatMessage : PanacheMongoEntity() {
    var chatId: Long = 0
    var senderName: String = ""
    var text: String = ""
    var timestamp: Instant = Instant.now()

    companion object : PanacheMongoCompanion<ChatMessage> {
        fun findByChatIdAndTimestampAfter(chatId: Long, since: Instant): List<ChatMessage> =
            find("chatId = ?1 and timestamp > ?2", chatId, since).list()

        fun deleteByChatIdAndTimestampBefore(chatId: Long, before: Instant) {
            delete("chatId = ?1 and timestamp < ?2", chatId, before)
        }

        fun findDistinctChatIds(): List<Long> =
            mongoCollection().distinct("chatId", Long::class.java).into(mutableListOf())
    }
}
