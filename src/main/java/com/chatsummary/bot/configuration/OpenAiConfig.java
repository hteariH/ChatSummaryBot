package com.chatsummary.bot.configuration;

import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenAiConfig {

    @Bean
    public OpenAiChatModel openAiChatModel(
            @Value("${spring.ai.openai.base-url:http://host.docker.internal:1234/v1}") String baseUrl,
            @Value("${spring.ai.openai.model:qwen/qwen3-4b}") String model,
            @Value("${spring.ai.openai.timeout:PT120S}") String timeout,
            @Value("${spring.ai.openai.max-retries:3}") int maxRetries
           ) {

        OpenAiChatOptions.Builder optionsBuilder = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .model(model)
                .timeout(java.time.Duration.parse(timeout))
                .maxRetries(maxRetries);
            optionsBuilder.apiKey("");

        OpenAiChatOptions options = optionsBuilder.build();

        return OpenAiChatModel.builder()
                .options(options)
                .toolCallingManager(ToolCallingManager.builder().build())
                .build();
    }

}