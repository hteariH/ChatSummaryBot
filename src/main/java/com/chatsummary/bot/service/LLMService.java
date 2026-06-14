package com.chatsummary.bot.service;

import org.springframework.stereotype.Component;

@Component
public interface LLMService {

    String generateWithLLM(String prompt, String model);

}
