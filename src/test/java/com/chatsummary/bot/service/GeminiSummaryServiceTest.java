package com.chatsummary.bot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GeminiSummaryServiceTest {

    private GeminiSummaryService service;

    @BeforeEach
    void setUp() {
        // Client.builder() performs no network call at construction, so a dummy key is safe.
        service = new GeminiSummaryService("dummy-key", "gemini-3-flash-preview",
                Mockito.mock(VoiceStorageService.class));
    }

    @Test
    void summarizeReturnsEmptySentinelWithoutCallingGemini() {
        assertThat(service.summarize(List.of(), "English", null))
                .isEqualTo("📭 No messages to summarize today.");
    }

    @Test
    void summarizeMonthlyReturnsEmptySentinelWithoutCallingGemini() {
        assertThat(service.summarizeMonthly(List.of(), "English"))
                .isEqualTo("📭 No daily summaries found for this month.");
    }
}
