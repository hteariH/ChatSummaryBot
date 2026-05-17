package com.chatsummary.bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableRetry
public class ChatSummaryBotApplication {

    private static final Logger log = LoggerFactory.getLogger(ChatSummaryBotApplication.class);

    void main(String[] args) {
        log.info("Starting ChatSummaryBot Application...");
        SpringApplication.run(ChatSummaryBotApplication.class, args);
    }
}
