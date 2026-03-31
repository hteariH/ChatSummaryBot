package com.chatsummary.bot.service

import com.chatsummary.bot.model.ChatMessage
import com.google.genai.Client
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.slf4j.LoggerFactory
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@ApplicationScoped
class GeminiSummaryService(
    @field:ConfigProperty(name = "gemini.retry.max-attempts", defaultValue = "3")
        var maxAttempts: Int,
    @field:ConfigProperty(name = "gemini.retry.delay", defaultValue = "1000")
        var retryDelay: Long
) {
    private val log = LoggerFactory.getLogger(GeminiSummaryService::class.java)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

    @ConfigProperty(name = "gemini.api-key")
    lateinit var apiKey: String

    @ConfigProperty(name = "gemini.model", defaultValue = "gemini-2.5-flash")
    lateinit var model: String

    private val client: Client by lazy {
        Client.builder().apiKey(apiKey).build()
    }

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
            |Format the summary nicely for Telegram (use plain text with lots of emojis for readability and structure).
            |
            |Negative prompt: markdown, HTML, code blocks, tables, lists, or any formatting that may not render well in Telegram.
            |--- Chat Transcript ---
            |$conversationText
            |--- End of Transcript ---
        """.trimMargin()

        var lastException: Exception? = null
        repeat(maxAttempts) { attempt ->
            try {
                val response = client.models.generateContent(model, prompt, null)
                val result = response.text()
                    ?: throw RuntimeException("Gemini returned empty response")
                return result
            } catch (e: Exception) {
                lastException = e
                log.warn("Gemini attempt ${attempt + 1}/$maxAttempts failed: ${e.message}")
                if (attempt < maxAttempts - 1) Thread.sleep(retryDelay)
            }
        }
        throw lastException ?: RuntimeException("Gemini summarization failed after $maxAttempts attempts")
    }
}
