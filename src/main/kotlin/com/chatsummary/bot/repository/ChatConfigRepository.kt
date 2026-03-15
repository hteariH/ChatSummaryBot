package com.chatsummary.bot.repository

import com.chatsummary.bot.model.ChatConfig
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository

@Repository
interface ChatConfigRepository : MongoRepository<ChatConfig, String> {
    fun findByChatId(chatId: Long): ChatConfig?
}
