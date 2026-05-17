package com.chatsummary.bot.service;

import com.chatsummary.bot.model.ChatMessage;
import com.chatsummary.bot.model.DailySummary;
import com.google.genai.Client;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
public class GeminiSummaryService {

    private static final Logger log = LoggerFactory.getLogger(GeminiSummaryService.class);
    private static final String EMPTY_DAILY_SUMMARY = "📭 No messages to summarize today.";
    private static final String EMPTY_MONTHLY_SUMMARY = "📭 No daily summaries found for this month.";

    private final String model;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());
    private final Client client;

    public GeminiSummaryService(
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.model}") String model
    ) {
        this.model = model;
        this.client = Client.builder()
                .apiKey(apiKey)
                .build();
    }

    @Retryable(
            retryFor = {ServerException.class, RuntimeException.class},
            maxAttemptsExpression = "${gemini.retry.max-attempts:20}",
            backoff = @Backoff(delayExpression = "${gemini.retry.delay:10000}")
    )
    public String summarize(List<ChatMessage> messages, String language, String customPrompt) {
        if (messages.isEmpty()) {
            return EMPTY_DAILY_SUMMARY;
        }

        var systemPrompt = """
                You are a helpful assistant that summarizes group chat conversations.
                Below is a transcript of group chat messages, where some messages might have attached images immediately following their text.

                When answering prioritize language of the provided transcript, preferring %s over any other languages.

                Please provide a concise, well-structured summary that:
                1. Highlights the main topics discussed
                2. Notes any decisions made or action items
                3. Mentions key participants where relevant
                4. Uses bullet points for clarity
                5. Keeps it concise but informative
                6. If images are provided, incorporate their context into the summary where relevant.

                Format the summary nicely for Telegram (use plain text with lots of emojis for readability and structure).

                Negative prompt: markdown, HTML, code blocks, tables, lists, or any formatting that may not render well in Telegram.
                %s
                --- Chat Transcript Start ---
                """.formatted(
                language,
                customPrompt == null || customPrompt.isBlank() ? "" : "Additional instructions: " + customPrompt
        );

        var parts = new ArrayList<Part>();
        parts.add(Part.fromText(systemPrompt));

        for (var message : messages) {
            var messageText = "[%s] %s: %s".formatted(
                    timeFormatter.format(message.timestamp()),
                    message.senderName(),
                    message.text()
            );
            parts.add(Part.fromText(messageText));

            for (var attachment : message.attachments()) {
                parts.add(Part.fromBytes(attachment.data().getData(), attachment.contentType()));
            }
        }

        parts.add(Part.fromText("--- End of Transcript ---"));

        var content = Content.builder()
                .parts(parts)
                .build();

        var response = client.models.generateContent(model, content, null);
        var result = response.text();
        if (result == null) {
            log.warn("Gemini returned empty response for chat summary");
            throw new RuntimeException("Gemini returned empty response");
        }

        return result;
    }

    @Retryable(
            retryFor = {ServerException.class, RuntimeException.class},
            maxAttemptsExpression = "${gemini.retry.max-attempts:20}",
            backoff = @Backoff(delayExpression = "${gemini.retry.delay:10000}")
    )
    public String summarizeMonthly(List<DailySummary> summaries, String language) {
        if (summaries.isEmpty()) {
            return EMPTY_MONTHLY_SUMMARY;
        }

        var allSummariesText = summaries.stream()
                .map(summary -> """
                        --- Daily Summary [%s] ---
                        %s""".formatted(timeFormatter.format(summary.timestamp()), summary.text()))
                .collect(java.util.stream.Collectors.joining("\n\n"));

        var prompt = """
                You are a helpful assistant that creates a monthly digest from daily chat summaries.
                Below are the daily summaries for the past month.

                When answering prioritize language of the provided summaries, preferring %s over any other languages.

                Please provide a comprehensive monthly report that:
                1. Highlights the most significant events and topics of the month
                2. Tracks the progress of ongoing discussions or projects
                3. Summarizes key outcomes and decisions
                4. Is well-structured with clear sections

                Format the report nicely for Telegram.

                --- Monthly Data ---
                %s
                --- End of Data ---
                """.formatted(language, allSummariesText);

        var response = client.models.generateContent(model, prompt, null);
        var result = response.text();
        if (result == null) {
            throw new RuntimeException("Gemini returned empty response for monthly summary");
        }

        return result;
    }
}
