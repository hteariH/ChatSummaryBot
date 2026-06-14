package com.chatsummary.bot.service;

import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Component;

@Component
public class GeneralLLMService implements LLMService {

    private final OpenAiChatModel chatModel;

    public GeneralLLMService(OpenAiChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @Override
    public String generateWithLLM(String prompt, String model) {
        OpenAiChatOptions options = null;
        if (model != null && !model.isBlank()) {
            options = OpenAiChatOptions.builder().model(model).build();
        }

        Prompt request = new Prompt(prompt, options);
        ChatResponse response = this.chatModel.call(request);

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new RuntimeException("LLM returned an empty response");
        }

        return response.getResult().getOutput().getText();
    }
}
