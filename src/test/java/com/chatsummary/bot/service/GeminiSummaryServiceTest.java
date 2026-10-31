package com.chatsummary.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.genai.types.GenerateContentResponse;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class GeminiSummaryServiceTest {

    private GeminiSummaryService service;

    @BeforeEach
    void setUp() {
        // Client.builder() performs no network call at construction, so a dummy key is safe.
        service = new GeminiSummaryService("dummy-key",
                List.of("gemini-3-flash-preview", "gemini-2.5-flash"),
                -1, -1, true,
                Mockito.mock(VoiceStorageService.class),
                Mockito.mock(AdminNotificationService.class));
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

    @Test
    void requireTextReturnsVisibleText() {
        var response = GenerateContentResponse.fromJson("""
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                  {"text":"thinking...","thought":true},{"text":"summary"}]}}]}""");

        assertThat(GeminiSummaryService.requireText(response, "chat summary")).isEqualTo("summary");
    }

    @Test
    void requireTextThrowsNonRetryableBlockedExceptionWhenPromptIsBlocked() {
        var response = GenerateContentResponse.fromJson("""
                {"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}""");

        assertThatThrownBy(() -> GeminiSummaryService.requireText(response, "chat summary"))
                .isInstanceOf(GeminiBlockedResponseException.class)
                .hasMessageContaining("PROHIBITED_CONTENT")
                .hasMessageContaining("candidates=0");
    }

    @Test
    void requireTextTreatsThoughtOnlyResponseAsEmpty() {
        var response = GenerateContentResponse.fromJson("""
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                  {"text":"thinking...","thought":true}]}}]}""");

        assertThatThrownBy(() -> GeminiSummaryService.requireText(response, "chat summary"))
                .isInstanceOf(GeminiEmptyResponseException.class)
                .hasMessageContaining("finishReason=STOP");
    }

    @Test
    void requireTextThrowsTruncatedExceptionOnMaxTokens() {
        var response = GenerateContentResponse.fromJson("""
                {"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[]}}]}""");

        assertThatThrownBy(() -> GeminiSummaryService.requireText(response, "chat summary"))
                .isInstanceOf(GeminiTruncatedResponseException.class);
    }
}
