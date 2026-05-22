package com.chatsummary.bot;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@SpringBootApplication
@EnableScheduling
@EnableRetry
public class ChatSummaryBotApplication {

    static void main(String[] args) {
        log.info("Starting ChatSummaryBot Application...");
        SpringApplication.run(ChatSummaryBotApplication.class, args);
    }
}
