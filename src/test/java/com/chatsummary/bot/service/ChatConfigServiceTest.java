package com.chatsummary.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chatsummary.bot.model.ChatConfig;
import com.chatsummary.bot.repository.ChatConfigRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ChatConfigServiceTest {

    private static final int DEFAULT_HOUR = 9;
    private static final long CHAT_ID = -100L;

    private ChatConfigRepository repository;
    private ChatConfigService service;

    @BeforeEach
    void setUp() {
        repository = Mockito.mock(ChatConfigRepository.class);
        service = new ChatConfigService(repository, DEFAULT_HOUR);
    }

    @Test
    void getChatConfigReturnsUnsavedDefaultWithDefaultHourWhenAbsent() {
        when(repository.findByChatId(CHAT_ID)).thenReturn(Optional.empty());

        var config = service.getChatConfig(CHAT_ID);

        assertThat(config.getChatId()).isEqualTo(CHAT_ID);
        assertThat(config.getSummaryHour()).isEqualTo(DEFAULT_HOUR);
        verify(repository, never()).save(any());
    }

    @Test
    void getChatConfigReturnsPersistedConfigWhenPresent() {
        var existing = new ChatConfig(CHAT_ID, 15);
        when(repository.findByChatId(CHAT_ID)).thenReturn(Optional.of(existing));

        assertThat(service.getChatConfig(CHAT_ID)).isSameAs(existing);
    }

    @Test
    void consumeSummaryCreditDecrementsAndPersists() {
        var config = new ChatConfig(CHAT_ID, DEFAULT_HOUR);
        config.setSummaryCredits(3);
        when(repository.findByChatId(CHAT_ID)).thenReturn(Optional.of(config));

        var remaining = service.consumeSummaryCredit(CHAT_ID);

        assertThat(remaining).isEqualTo(2);
        assertThat(config.getSummaryCredits()).isEqualTo(2);
        verify(repository).save(config);
    }

    @Test
    void consumeSummaryCreditFloorsAtZero() {
        var config = new ChatConfig(CHAT_ID, DEFAULT_HOUR);
        config.setSummaryCredits(0);
        when(repository.findByChatId(CHAT_ID)).thenReturn(Optional.of(config));

        var remaining = service.consumeSummaryCredit(CHAT_ID);

        assertThat(remaining).isZero();
        assertThat(config.getSummaryCredits()).isZero();
    }

    @Test
    void addSummaryCreditsIncrementsAndPersists() {
        var config = new ChatConfig(CHAT_ID, DEFAULT_HOUR);
        config.setSummaryCredits(5);
        when(repository.findByChatId(CHAT_ID)).thenReturn(Optional.of(config));

        service.addSummaryCredits(CHAT_ID, 30);

        assertThat(config.getSummaryCredits()).isEqualTo(35);
        verify(repository).save(config);
    }

    @Test
    void setLanguageMutatesAndPersists() {
        when(repository.findByChatId(CHAT_ID)).thenReturn(Optional.empty());

        service.setLanguage(CHAT_ID, "Russian");

        var captor = ArgumentCaptor.forClass(ChatConfig.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getLanguage()).isEqualTo("Russian");
    }

    @Test
    void saveChatConfigUpdatesHourOnExistingRow() {
        var existing = new ChatConfig(CHAT_ID, DEFAULT_HOUR);
        when(repository.findByChatId(CHAT_ID)).thenReturn(Optional.of(existing));

        service.saveChatConfig(CHAT_ID, 18);

        assertThat(existing.getSummaryHour()).isEqualTo(18);
        verify(repository).save(existing);
    }

    @Test
    void saveChatConfigDeprecatedCronParsesHour() {
        var existing = new ChatConfig(CHAT_ID, DEFAULT_HOUR);
        when(repository.findByChatId(CHAT_ID)).thenReturn(Optional.of(existing));

        service.saveChatConfig(CHAT_ID, "0 30 8 * * *");

        assertThat(existing.getSummaryHour()).isEqualTo(8);
        verify(repository).save(existing);
    }
}
