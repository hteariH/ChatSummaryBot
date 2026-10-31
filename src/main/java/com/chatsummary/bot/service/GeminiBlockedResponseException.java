package com.chatsummary.bot.service;

/**
 * Gemini refused the prompt itself ({@code promptFeedback.blockReason} is set, so the response
 * carries no candidates). The block is decided by the content filter on the input, so it is
 * deterministic for a given transcript and is <b>not</b> retried — retrying only burns quota for
 * the same empty result.
 */
public class GeminiBlockedResponseException extends RuntimeException {

    public GeminiBlockedResponseException(String message) {
        super(message);
    }
}
