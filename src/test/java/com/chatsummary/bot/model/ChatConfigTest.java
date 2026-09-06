package com.chatsummary.bot.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChatConfigTest {

    @Test
    void hasSensibleDefaults() {
        var config = new ChatConfig();

        assertThat(config.getSummaryCredits()).isEqualTo(30);
        assertThat(config.getLanguage()).isEqualTo("English");
        assertThat(config.isEnabled()).isTrue();
        assertThat(config.isMonthlySummaryEnabled()).isFalse();
        assertThat(config.getSummaryHour()).isEqualTo(21);
    }

    @Test
    void constructorSetsChatIdAndHour() {
        var config = new ChatConfig(-100L, 9);

        assertThat(config.getChatId()).isEqualTo(-100L);
        assertThat(config.getSummaryHour()).isEqualTo(9);
    }

    @Test
    void constructorSetsChatIdAndCron() {
        var config = new ChatConfig(-100L, "0 0 9 * * *");

        assertThat(config.getChatId()).isEqualTo(-100L);
        assertThat(config.getCron()).isEqualTo("0 0 9 * * *");
    }
}
