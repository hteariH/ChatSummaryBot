package com.chatsummary.bot.service;

/**
 * Gemini finished with {@code MAX_TOKENS} and produced no visible text — the model spent its
 * whole output budget (typically on "thinking" tokens) before emitting an answer. This is
 * deterministic for a given input, so it is <b>not</b> retried: re-running the same request would
 * just burn quota for the same empty result. Raise {@code gemini.max-output-tokens} or cap
 * {@code gemini.thinking-budget} instead.
 */
public class GeminiTruncatedResponseException extends RuntimeException {

    public GeminiTruncatedResponseException(String message) {
        super(message);
    }
}
