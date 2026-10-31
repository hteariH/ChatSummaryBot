package com.chatsummary.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chatsummary.bot.model.ChatAttachment;
import com.chatsummary.bot.model.ChatMessage;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.bson.types.Binary;
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

    private static final String BLOCKED = """
            {"promptFeedback":{"blockReason":"PROHIBITED_CONTENT"}}""";

    private static final String OK = """
            {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"text-only summary"}]}}]}""";

    /** Service whose Gemini call is scripted: records each request and answers via {@code responder}. */
    private static GeminiSummaryService scripted(List<Content> requests, Function<Content, String> responder) {
        return new GeminiSummaryService("dummy-key", List.of("gemini-3-flash-preview"), -1, -1, true,
                Mockito.mock(VoiceStorageService.class), Mockito.mock(AdminNotificationService.class)) {
            @Override
            GenerateContentResponse generateContent(Content content) {
                requests.add(content);
                return GenerateContentResponse.fromJson(responder.apply(content));
            }
        };
    }

    private static boolean hasMedia(Content content) {
        return content.parts().orElseThrow().stream().anyMatch(part -> part.inlineData().isPresent());
    }

    @Test
    void summarizeFallsBackToTextOnlyWhenMultimodalPromptIsBlocked() {
        var requests = new ArrayList<Content>();
        var service = scripted(requests, content -> hasMedia(content) ? BLOCKED : OK);
        var photo = new ChatAttachment("image/jpeg", new Binary(new byte[]{1, 2, 3}));
        var messages = List.of(new ChatMessage(-1001L, 7, "Alice", "look", List.of(photo)));

        assertThat(service.summarize(messages, "English", null)).isEqualTo("text-only summary");
        assertThat(requests).hasSize(2);
        assertThat(hasMedia(requests.get(1))).isFalse();
        assertThat(requests.get(1).parts().orElseThrow())
                .anyMatch(part -> part.text().orElse("").contains("[media omitted]"));
    }

    @Test
    void summarizeDoesNotFallBackWhenTextOnlyPromptIsBlocked() {
        var requests = new ArrayList<Content>();
        var service = scripted(requests, content -> BLOCKED);
        var messages = List.of(new ChatMessage(-1001L, 7, "Alice", "hello"));

        assertThatThrownBy(() -> service.summarize(messages, "English", null))
                .isInstanceOf(GeminiBlockedResponseException.class);
        assertThat(requests).hasSize(1);
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
