package com.chatsummary.bot.service

interface BotMessageSender {
    fun sendMessage(chatId: Long, text: String)
}
