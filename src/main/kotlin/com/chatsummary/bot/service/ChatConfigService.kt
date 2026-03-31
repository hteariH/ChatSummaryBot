package com.chatsummary.bot.service

import com.chatsummary.bot.model.ChatConfig
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.time.Instant

@ApplicationScoped
class ChatConfigService {

    @ConfigProperty(name = "summary.cron")
    lateinit var defaultCron: String

    fun getChatConfig(chatId: Long): ChatConfig =
        ChatConfig.findByChatId(chatId) ?: ChatConfig().apply {
            this.chatId = chatId
            this.cron = defaultCron
        }

    fun saveChatConfig(chatId: Long, cron: String): ChatConfig {
        val config = ChatConfig.findByChatId(chatId) ?: ChatConfig().apply {
            this.chatId = chatId
        }
        config.cron = cron
        config.persistOrUpdate()
        return config
    }

    fun updateLastProcessedAt(chatId: Long, timestamp: Instant) {
        val config = getChatConfig(chatId)
        config.lastProcessedAt = timestamp
        config.persistOrUpdate()
    }

    fun getAllConfigs(): List<ChatConfig> = ChatConfig.listAll()
}
