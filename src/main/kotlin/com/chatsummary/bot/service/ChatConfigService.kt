package com.chatsummary.bot.service

import com.chatsummary.bot.model.ChatConfig
import com.chatsummary.bot.repository.ChatConfigRepository
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ChatConfigService(
    private val chatConfigRepository: ChatConfigRepository,
    @param:Value("\${summary.cron}") private val defaultCron: String
) {
    fun getChatConfig(chatId: Long): ChatConfig {
        return chatConfigRepository.findByChatId(chatId) ?: ChatConfig(chatId = chatId, cron = defaultCron)
    }

    fun saveChatConfig(chatId: Long, cron: String): ChatConfig {
        val config = chatConfigRepository.findByChatId(chatId) ?: ChatConfig(chatId = chatId, cron = cron)
        config.cron = cron
        return chatConfigRepository.save(config)
    }

    fun updateLastProcessedAt(chatId: Long, timestamp: Instant) {
        val config = getChatConfig(chatId)
        config.lastProcessedAt = timestamp
        chatConfigRepository.save(config)
    }

    fun getAllConfigs(): List<ChatConfig> {
        return chatConfigRepository.findAll()
    }

    /** Decrements credits by 1 (floor 0) and saves. Returns remaining credits. */
    fun consumeSummaryCredit(chatId: Long): Int {
        val config = getChatConfig(chatId)
        if (config.summaryCredits > 0) config.summaryCredits--
        chatConfigRepository.save(config)
        return config.summaryCredits
    }

    fun setEnabled(chatId: Long, enabled: Boolean) {
        val config = getChatConfig(chatId)
        config.enabled = enabled
        chatConfigRepository.save(config)
    }

    fun setLanguage(chatId: Long, language: String) {
        val config = getChatConfig(chatId)
        config.language = language
        chatConfigRepository.save(config)
    }

    /** Adds stars credits to the chat balance (1 star = 1 summary). */
    fun addSummaryCredits(chatId: Long, stars: Int) {
        val config = getChatConfig(chatId)
        config.summaryCredits += stars
        chatConfigRepository.save(config)
    }
}
