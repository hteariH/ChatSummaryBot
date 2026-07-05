package com.chatsummary.bot.service;

import com.chatsummary.bot.model.ChatMessage;
import com.chatsummary.bot.model.DailySummary;
import com.chatsummary.bot.util.TelegramLinks;
import com.google.genai.Client;
import com.google.genai.errors.ClientException;
import com.google.genai.errors.ServerException;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class GeminiSummaryService {

    private static final String EMPTY_DAILY_SUMMARY = "📭 No messages to summarize today.";
    private static final String EMPTY_MONTHLY_SUMMARY = "📭 No daily summaries found for this month.";

    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_NOT_FOUND = 404;

    /**
     * Models tried in order. On a 429 (quota / rate limit exhausted) the request falls through to
     * the next model in the list; the first that responds wins.
     */
    private final List<String> models;
    /** Cap on output tokens per response; &lt;= 0 means "unset" (keep the model default). */
    private final int maxOutputTokens;
    /**
     * Thinking-token budget for "thinking" models. A negative value means "unset" (keep the model
     * default); {@code >= 0} caps internal reasoning so the model is forced to leave room for a
     * visible answer, preventing MAX_TOKENS empty responses on large multimodal transcripts
     * ({@code 0} disables thinking entirely).
     */
    private final int thinkingBudget;
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
            .withZone(ZoneId.systemDefault());
    private final Client client;
    private final VoiceStorageService voiceStorageService;
    private final AdminNotificationService adminNotificationService;

    public GeminiSummaryService(
            @Value("${gemini.api-key}") String apiKey,
            @Value("${gemini.models}") List<String> models,
            @Value("${gemini.max-output-tokens:-1}") int maxOutputTokens,
            @Value("${gemini.thinking-budget:-1}") int thinkingBudget,
            VoiceStorageService voiceStorageService,
            AdminNotificationService adminNotificationService
    ) {
        this.models = models.stream()
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .toList();
        if (this.models.isEmpty()) {
            throw new IllegalStateException("gemini.models must list at least one model");
        }
        this.maxOutputTokens = maxOutputTokens;
        this.thinkingBudget = thinkingBudget;
        this.client = Client.builder()
                .apiKey(apiKey)
                .build();
        this.voiceStorageService = voiceStorageService;
        this.adminNotificationService = adminNotificationService;
    }

    /**
     * Builds the per-request generation config, or {@code null} when nothing is configured (so the
     * SDK sends no config, preserving model defaults).
     */
    private GenerateContentConfig buildConfig() {
        if (maxOutputTokens <= 0 && thinkingBudget < 0) {
            return null;
        }
        var builder = GenerateContentConfig.builder();
        if (maxOutputTokens > 0) {
            builder.maxOutputTokens(maxOutputTokens);
        }
        if (thinkingBudget >= 0) {
            builder.thinkingConfig(ThinkingConfig.builder().thinkingBudget(thinkingBudget).build());
        }
        return builder.build();
    }

    /**
     * Calls Gemini, walking the configured model list in order. It falls through to the next model
     * on HTTP 429 (rate limited / quota exhausted) or 404 (model unavailable / bad id) — both mean
     * "this model won't serve the request, try another". Any other {@link ClientException} (e.g.
     * 400/403) is rethrown immediately. If every model is skipped, the last such exception is
     * rethrown (it is not retryable, so the caller does not keep hammering an exhausted quota).
     */
    private GenerateContentResponse generateContent(Content content) {
        var config = buildConfig();
        ClientException lastSkippable = null;
        for (int i = 0; i < models.size(); i++) {
            var current = models.get(i);
            try {
                return client.models.generateContent(current, content, config);
            } catch (ClientException e) {
                if (e.code() != HTTP_TOO_MANY_REQUESTS && e.code() != HTTP_NOT_FOUND) {
                    throw e;
                }
                lastSkippable = e;
                var reason = e.code() == HTTP_NOT_FOUND ? "404 (model unavailable)" : "429 (quota/rate limit)";
                if (i < models.size() - 1) {
                    log.warn("Gemini model {} returned {}, falling back to next model {}",
                            current, reason, models.get(i + 1));
                } else {
                    log.warn("Gemini model {} returned {} and no fallback models remain", current, reason);
                }
            }
        }
        throw lastSkippable;
    }

    /**
     * Extracts the response text, or classifies why it is empty. {@code MAX_TOKENS} means the model
     * ran out of output budget (usually on thinking) and produced no answer — deterministic for the
     * same input, so a {@link GeminiTruncatedResponseException} is thrown and <b>not</b> retried to
     * avoid burning quota. Anything else empty is treated as a transient
     * {@link GeminiEmptyResponseException} and retried.
     */
    private String requireText(GenerateContentResponse response, String operation) {
        var result = response.text();
        if (result != null) {
            return result;
        }
        var finishReason = response.finishReason();
        log.warn("Gemini returned no text for {} (finishReason={})", operation, finishReason);
        if (finishReason.knownEnum() == FinishReason.Known.MAX_TOKENS) {
            throw new GeminiTruncatedResponseException(
                    "Gemini hit MAX_TOKENS with no visible text for " + operation
                            + " — raise gemini.max-output-tokens or cap gemini.thinking-budget");
        }
        throw new GeminiEmptyResponseException("Gemini returned empty response for " + operation);
    }

    @Retryable(
            retryFor = {ServerException.class, GeminiEmptyResponseException.class},
            maxAttemptsExpression = "${gemini.retry.max-attempts:5}",
            backoff = @Backoff(
                    delayExpression = "${gemini.retry.delay:10000}",
                    multiplierExpression = "${gemini.retry.multiplier:2}",
                    maxDelayExpression = "${gemini.retry.max-delay:60000}"
            )
    )
    public String summarize(List<ChatMessage> messages, String language, String customPrompt) {
        if (messages.isEmpty()) {
            return EMPTY_DAILY_SUMMARY;
        }

        var systemPrompt = """
                You are a helpful assistant that summarizes group chat conversations.
                Below is a transcript of group chat messages, where some messages might have attached images or voice messages immediately following their text.
                
                When answering prioritize language of the provided transcript, preferring %s over any other languages.

                Please provide a concise, well-structured summary that:
                1. Highlights the main topics discussed
                2. Notes any decisions made or action items
                3. Mentions key participants where relevant
                4. Uses bullet points for clarity
                5. Keeps it concise but informative
                6. If media is provided, transcribe/interpret the spoken audio in voice messages and round video notes, and incorporate the context of images, into the summary where relevant.
                7. Includes source links for the original messages in key points. Use only the source links provided in the transcript, use source links in format <a href="<source_link>">(link)</a>).
                
                Format the summary nicely for Telegram (use plain text with lots of emojis for readability and structure, you can use next HTML tags for formatting: <b>, <i>, <u>, <s>
                For every key point, add one or more relevant source URLs in parentheses at the end of the point.
                
                Negative prompt: markdown, code blocks, tables, lists, or any formatting that may not render well in Telegram.
                %s
                --- Chat Transcript Start ---
                """.formatted(
                language,
                customPrompt == null || customPrompt.isBlank() ? "" : "Additional instructions: " + customPrompt
        );

        var parts = new ArrayList<Part>();
        parts.add(Part.fromText(systemPrompt));

        for (var message : messages) {
            var messageText = "[%s] %s | source: %s | %s: %s".formatted(
                    timeFormatter.format(message.timestamp()),
                    messageReference(message),
                    messageSource(message),
                    message.senderName(),
                    message.text()
            );
            parts.add(Part.fromText(messageText));

            for (var attachment : message.attachments()) {
                if (attachment.data() != null) {
                    parts.add(Part.fromBytes(attachment.data().getData(), attachment.contentType()));
                } else if (attachment.filePath() != null) {
                    byte[] voiceData = voiceStorageService.loadVoice(attachment.filePath());
                    if (voiceData != null) {
                        parts.add(Part.fromBytes(voiceData, attachment.contentType()));
                    }
                }
            }
        }

        parts.add(Part.fromText("--- End of Transcript ---"));

        var content = Content.builder()
                .parts(parts)
                .build();

        var response = generateContent(content);
        reportTokenUsage("summary", messages.getFirst().chatId(), response);
        return requireText(response, "chat summary");
    }

    private void reportTokenUsage(String operation, long chatId, GenerateContentResponse response) {
        response.usageMetadata().ifPresent(usage -> adminNotificationService.notifyTokenUsage(
                operation,
                chatId,
                usage.promptTokenCount().orElse(0),
                usage.thoughtsTokenCount().orElse(0),
                usage.candidatesTokenCount().orElse(0),
                usage.totalTokenCount().orElse(0)
        ));
    }

    private static String messageReference(ChatMessage message) {
        if (message.telegramMessageId() == null) {
            return "message id unavailable";
        }

        return "message #" + message.telegramMessageId();
    }

    private static String messageSource(ChatMessage message) {
        if (message.telegramMessageId() == null) {
            return "source link unavailable";
        }

        return TelegramLinks.messageUrl(message.chatId(), message.telegramMessageId())
                .orElseGet(() -> messageReference(message));
    }

    @Retryable(
            retryFor = {ServerException.class, GeminiEmptyResponseException.class},
            maxAttemptsExpression = "${gemini.retry.max-attempts:5}",
            backoff = @Backoff(
                    delayExpression = "${gemini.retry.delay:10000}",
                    multiplierExpression = "${gemini.retry.multiplier:2}",
                    maxDelayExpression = "${gemini.retry.max-delay:60000}"
            )
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
                5. Includes source links for key points. Use only the source links already present in the daily summaries below, and keep them in the format <a href="<source_link>">(link)</a>.

                Format the report nicely for Telegram (use plain text with lots of emojis for readability and structure, you can use next HTML tags for formatting: <b>, <i>, <u>, <s>, <a>).
                For every key point, add one or more relevant source URLs in parentheses at the end of the point.

                Negative prompt: markdown, code blocks, tables, lists, or any formatting that may not render well in Telegram.

                --- Monthly Data ---
                %s
                --- End of Data ---
                """.formatted(language, allSummariesText);

        var content = Content.builder()
                .parts(List.of(Part.fromText(prompt)))
                .build();

        var response = generateContent(content);
        reportTokenUsage("monthly summary", summaries.getFirst().chatId(), response);
        return requireText(response, "monthly summary");
    }

}
