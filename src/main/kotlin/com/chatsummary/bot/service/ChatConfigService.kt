package com.chatsummary.bot.service

import com.chatsummary.bot.model.ChatConfig
import com.chatsummary.bot.repository.ChatConfigRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ChatConfigService(
    private val chatConfigRepository: ChatConfigRepository,
    @Value("\${summary.cron}") private val defaultCron: String
) {
    fun getChatConfig(chatId: Long): ChatConfig {
        return chatConfigRepository.findByChatId(chatId) ?: ChatConfig(chatId = chatId, cron = defaultCron)
    }

    fun saveChatConfig(chatId: Long, cron: String): ChatConfig {
        val config = chatConfigRepository.findByChatId(chatId) ?: ChatConfig(chatId = chatId, cron = cron)
        config.cron = cron
        return chatConfigRepository.save(config)
    }

    fun getAllConfigs(): List<ChatConfig> {
        return chatConfigRepository.findAll()
    }
}
