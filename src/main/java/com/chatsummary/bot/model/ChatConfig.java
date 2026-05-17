package com.chatsummary.bot.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "chat_configs")
public final class ChatConfig {

    @Id
    private String id;

    @Indexed(unique = true)
    private long chatId;

    private String cron;
    private Instant lastProcessedAt;
    private int summaryCredits = 30;
    private String language = "English";
    private boolean enabled = true;
    private String customPrompt;
    private boolean monthlySummaryEnabled;
    private Instant lastMonthlyProcessedAt;

    public ChatConfig() {
    }

    public ChatConfig(long chatId, String cron) {
        this.chatId = chatId;
        this.cron = cron;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public long getChatId() {
        return chatId;
    }

    public void setChatId(long chatId) {
        this.chatId = chatId;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public Instant getLastProcessedAt() {
        return lastProcessedAt;
    }

    public void setLastProcessedAt(Instant lastProcessedAt) {
        this.lastProcessedAt = lastProcessedAt;
    }

    public int getSummaryCredits() {
        return summaryCredits;
    }

    public void setSummaryCredits(int summaryCredits) {
        this.summaryCredits = summaryCredits;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCustomPrompt() {
        return customPrompt;
    }

    public void setCustomPrompt(String customPrompt) {
        this.customPrompt = customPrompt;
    }

    public boolean isMonthlySummaryEnabled() {
        return monthlySummaryEnabled;
    }

    public void setMonthlySummaryEnabled(boolean monthlySummaryEnabled) {
        this.monthlySummaryEnabled = monthlySummaryEnabled;
    }

    public Instant getLastMonthlyProcessedAt() {
        return lastMonthlyProcessedAt;
    }

    public void setLastMonthlyProcessedAt(Instant lastMonthlyProcessedAt) {
        this.lastMonthlyProcessedAt = lastMonthlyProcessedAt;
    }
}
