package com.chatsummary.bot

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.retry.annotation.EnableRetry
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
@EnableRetry
class ChatSummaryBotApplication

fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger(ChatSummaryBotApplication::class.java)
    logger.info("Starting ChatSummaryBot Application...")
    runApplication<ChatSummaryBotApplication>(*args)
}
