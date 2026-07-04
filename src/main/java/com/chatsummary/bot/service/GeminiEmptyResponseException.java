package com.chatsummary.bot.service;

/**
 * Gemini returned a response with no text. Treated as transient and retried,
 * unlike {@link com.google.genai.errors.ClientException} (4xx) which is not.
 */
public class GeminiEmptyResponseException extends RuntimeException {

    public GeminiEmptyResponseException(String message) {
        super(message);
    }
}
