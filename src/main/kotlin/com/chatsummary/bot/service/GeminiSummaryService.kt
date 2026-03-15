package com.chatsummary.bot.service

import com.chatsummary.bot.model.ChatMessage
import com.google.genai.Client
import com.google.genai.types.GenerateContentConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.retry.annotation.Backoff
import org.springframework.retry.annotation.Retryable
import org.springframework.stereotype.Service
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Service
class GeminiSummaryService(
    @param:Value("\${gemini.api-key}") private val apiKey: String,
    @param:Value("\${gemini.model}") private val model: String
) {
    private val log = LoggerFactory.getLogger(GeminiSummaryService::class.java)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    private val client: Client by lazy {
        Client.builder()
            .apiKey(apiKey)
            .build()
    }

    @Retryable(
        value = [Exception::class],
        maxAttemptsExpression = "\${gemini.retry.max-attempts:3}",
        backoff = Backoff(delayExpression = "\${gemini.retry.delay:1000}")
    )
    fun summarize(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) {
            return "📭 No messages to summarize today."
        }

        val conversationText = messages.joinToString("\n") { msg ->
            "[${timeFormatter.format(msg.timestamp)}] ${msg.senderName}: ${msg.text}"
        }

        val prompt = """
            |You are a helpful assistant that summarizes group chat conversations.
            |Below is a transcript of today's group chat messages.
            |
            |When answering prioritize language of the provided transcript, preferring Russian over any other languages.
            |
            |Please provide a concise, well-structured summary that:
            |1. Highlights the main topics discussed
            |2. Notes any decisions made or action items
            |3. Mentions key participants where relevant
            |4. Uses bullet points for clarity
            |5. Keeps it concise but informative
            |
            |Format the summary nicely for Telegram (use plain text with emojis for readability, no markdown).
            |
            |--- Chat Transcript ---
            |$conversationText
            |--- End of Transcript ---
        """.trimMargin()

        val config = GenerateContentConfig.builder()
            .maxOutputTokens(2048)
            .temperature(0.3f)
            .build()

        val response = client.models.generateContent(
            model,
            prompt,
            config
        )

        val result = response.text()
        if (result == null) {
            log.warn("Gemini returned empty response for chat summary")
            throw RuntimeException("Gemini returned empty response")
        }

        return result
    }
}
