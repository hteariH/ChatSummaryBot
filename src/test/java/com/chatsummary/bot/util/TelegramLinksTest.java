package com.chatsummary.bot.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TelegramLinksTest {

    @Test
    void buildsSupergroupLinkStrippingThe100Prefix() {
        assertThat(TelegramLinks.messageUrl(-1001234567890L, 42))
                .contains("https://t.me/c/1234567890/42");
    }

    @Test
    void returnsEmptyForNonSupergroupChat() {
        assertThat(TelegramLinks.messageUrl(-987654321L, 42)).isEmpty();
        assertThat(TelegramLinks.messageUrl(12345L, 42)).isEmpty();
    }

    @Test
    void returnsEmptyWhenMessageIdIsNull() {
        assertThat(TelegramLinks.messageUrl(-1001234567890L, null)).isEmpty();
    }
}
