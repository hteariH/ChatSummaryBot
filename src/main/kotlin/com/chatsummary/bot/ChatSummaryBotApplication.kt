package com.chatsummary.bot

import io.quarkus.runtime.Quarkus
import io.quarkus.runtime.annotations.QuarkusMain
import org.slf4j.LoggerFactory

@QuarkusMain
object ChatSummaryBotApplication {
    private val logger = LoggerFactory.getLogger(ChatSummaryBotApplication::class.java)

    @JvmStatic
    fun main(args: Array<String>) {
        logger.info("Starting ChatSummaryBot Application...")
        Quarkus.run(*args)
    }
}
