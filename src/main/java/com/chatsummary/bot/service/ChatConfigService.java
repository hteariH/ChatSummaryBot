package com.chatsummary.bot.service;

import com.chatsummary.bot.model.ChatConfig;
import com.chatsummary.bot.repository.ChatConfigRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ChatConfigService {

    private final ChatConfigRepository chatConfigRepository;
    private final String defaultCron;

    public ChatConfigService(
            ChatConfigRepository chatConfigRepository,
            @Value("${summary.cron}") String defaultCron
    ) {
        this.chatConfigRepository = chatConfigRepository;
        this.defaultCron = defaultCron;
    }

    public ChatConfig getChatConfig(long chatId) {
        return chatConfigRepository.findByChatId(chatId)
                .orElseGet(() -> new ChatConfig(chatId, defaultCron));
    }

    public void saveChatConfig(long chatId, String cron) {
        var config = chatConfigRepository.findByChatId(chatId)
                .orElseGet(() -> new ChatConfig(chatId, cron));
        config.setCron(cron);
        chatConfigRepository.save(config);
    }

    public void updateLastProcessedAt(long chatId, Instant timestamp) {
        var config = getChatConfig(chatId);
        config.setLastProcessedAt(timestamp);
        chatConfigRepository.save(config);
    }

    public List<ChatConfig> getAllConfigs() {
        return chatConfigRepository.findAll();
    }

    public int consumeSummaryCredit(long chatId) {
        var config = getChatConfig(chatId);
        config.setSummaryCredits(Math.max(0, config.getSummaryCredits() - 1));
        chatConfigRepository.save(config);
        return config.getSummaryCredits();
    }

    public void setEnabled(long chatId, boolean enabled) {
        var config = getChatConfig(chatId);
        config.setEnabled(enabled);
        chatConfigRepository.save(config);
    }

    public void setLanguage(long chatId, String language) {
        var config = getChatConfig(chatId);
        config.setLanguage(language);
        chatConfigRepository.save(config);
    }

    public void setCustomPrompt(long chatId, String customPrompt) {
        var config = getChatConfig(chatId);
        config.setCustomPrompt(customPrompt);
        chatConfigRepository.save(config);
    }

    public void setMonthlySummaryEnabled(long chatId, boolean enabled) {
        var config = getChatConfig(chatId);
        config.setMonthlySummaryEnabled(enabled);
        chatConfigRepository.save(config);
    }

    public void updateLastMonthlyProcessedAt(long chatId, Instant timestamp) {
        var config = getChatConfig(chatId);
        config.setLastMonthlyProcessedAt(timestamp);
        chatConfigRepository.save(config);
    }

    public void updateLastSummary(long chatId, Integer messageId, String text) {
        var config = getChatConfig(chatId);
        config.setLastSummaryMessageId(messageId);
        config.setLastSummaryText(text);
        chatConfigRepository.save(config);
    }

    public void addSummaryCredits(long chatId, int stars) {
        var config = getChatConfig(chatId);
        config.setSummaryCredits(config.getSummaryCredits() + stars);
        chatConfigRepository.save(config);
    }
}
