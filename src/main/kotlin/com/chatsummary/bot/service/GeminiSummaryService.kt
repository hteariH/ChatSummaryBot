package com.chatsummary.bot.service

import com.chatsummary.bot.model.ChatMessage
import com.chatsummary.bot.model.DailySummary
import com.google.genai.Client
import com.google.genai.types.Content
import com.google.genai.types.Part
import com.google.genai.errors.ServerException
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
        value = [ServerException::class, RuntimeException::class],
        maxAttemptsExpression = "\${gemini.retry.max-attempts:20}",
        backoff = Backoff(delayExpression = "\${gemini.retry.delay:10000}")
    )
    fun summarize(messages: List<ChatMessage>, language: String = "English", customPrompt: String? = null): String {
        if (messages.isEmpty()) {
            return "📭 No messages to summarize today."
        }

        val systemPrompt = """
            |You are a helpful assistant that summarizes group chat conversations.
            |Below is a transcript of group chat messages, where some messages might have attached images immediately following their text.
            |
            |When answering prioritize language of the provided transcript, preferring $language over any other languages.
            |
            |Please provide a concise, well-structured summary that:
            |1. Highlights the main topics discussed
            |2. Notes any decisions made or action items
            |3. Mentions key participants where relevant
            |4. Uses bullet points for clarity
            |5. Keeps it concise but informative
            |6. If images are provided, incorporate their context into the summary where relevant.
            |
            |Format the summary nicely for Telegram (use plain text with lots of emojis for readability and structure).
            |
            |Negative prompt: markdown, HTML, code blocks, tables, lists, or any formatting that may not render well in Telegram.
            |${if (!customPrompt.isNullOrBlank()) "Additional instructions: $customPrompt" else ""}
            |--- Chat Transcript Start ---
        """.trimMargin()

        val parts = mutableListOf<Part>()
        parts.add(Part.fromText(systemPrompt))

        // Interleave message text with its attachments
        messages.forEach { msg ->
            val msgText = "[${timeFormatter.format(msg.timestamp)}] ${msg.senderName}: ${msg.text}"
            parts.add(Part.fromText(msgText))
            
            msg.attachments.forEach { attachment ->
                parts.add(Part.fromBytes(attachment.data.data, attachment.contentType))
            }
        }

        parts.add(Part.fromText("--- End of Transcript ---"))

        val content = Content.builder().parts(parts).build()

        val response = client.models.generateContent(
            model,
            content,
            null
        )

        val result = response.text()
        if (result == null) {
            log.warn("Gemini returned empty response for chat summary")
            throw RuntimeException("Gemini returned empty response")
        }

        return result
    }

    @Retryable(
        value = [ServerException::class, RuntimeException::class],
        maxAttemptsExpression = "\${gemini.retry.max-attempts:20}",
        backoff = Backoff(delayExpression = "\${gemini.retry.delay:10000}")
    )
    fun summarizeMonthly(summaries: List<DailySummary>, language: String = "English"): String {
        if (summaries.isEmpty()) {
            return "📭 No daily summaries found for this month."
        }

        val allSummariesText = summaries.joinToString("\n\n") { ds ->
            "--- Daily Summary [${timeFormatter.format(ds.timestamp)}] ---\n${ds.text}"
        }

        val prompt = """
            |You are a helpful assistant that creates a monthly digest from daily chat summaries.
            |Below are the daily summaries for the past month.
            |
            |When answering prioritize language of the provided summaries, preferring $language over any other languages.
            |
            |Please provide a comprehensive monthly report that:
            |1. Highlights the most significant events and topics of the month
            |2. Tracks the progress of ongoing discussions or projects
            |3. Summarizes key outcomes and decisions
            |4. Is well-structured with clear sections
            |
            |Format the report nicely for Telegram.
            |
            |--- Monthly Data ---
            |$allSummariesText
            |--- End of Data ---
        """.trimMargin()

        val response = client.models.generateContent(
            model,
            prompt,
            null
        )

        return response.text() ?: throw RuntimeException("Gemini returned empty response for monthly summary")
    }
}
