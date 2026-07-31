package com.chatsummary.bot.service;

import com.chatsummary.bot.telegram.ChatSummaryBot;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AdminNotificationService {

    /**
     * Budget for the escaped thought text. Kept well under Telegram's 4096-character limit so the
     * report stays a single message — {@code sendMessage} splits longer text at newlines, which
     * would tear the surrounding {@code <blockquote>} apart and get the message rejected.
     */
    private static final int MAX_THOUGHT_CHARS = 3500;

    private final long adminChatId;
    private final ChatSummaryBot chatSummaryBot;
    private final AtomicLong totalOutputTokens = new AtomicLong();

    public AdminNotificationService(
            @Value("${summary.admin-chat-id}") long adminChatId,
            @Lazy ChatSummaryBot chatSummaryBot
    ) {
        this.adminChatId = adminChatId;
        this.chatSummaryBot = chatSummaryBot;
    }

    public void notifyNewChat(long chatId, String chatTitle, String chatType, String addedBy) {
        var message = """
                🆕 *Бот добавлен в новый чат!*
                *Название:* %s
                *Тип:* %s
                *ID:* %d
                *Добавил:* %s""".formatted(chatTitle, chatType, chatId, addedBy);
        chatSummaryBot.sendMessage(adminChatId, message);
        log.info("Bot added to new chat: {} ({}), by {}", chatTitle, chatId, addedBy);
    }

    public void notifyChatRemoved(long chatId, String reason) {
        var message = """
                👋 *Бот удалён из чата*
                *ID:* %d
                *Причина:* %s
                Все сообщения, дневные саммари и настройки этого чата очищены.""".formatted(chatId, reason);
        chatSummaryBot.sendMessage(adminChatId, message);
        log.info("Bot removed from chat {} ({}); purged its stored data", chatId, reason);
    }

    public void notifyPayment(long chatId, String donorName, int stars, int creditsAdded) {
        var message = """
                ⭐ *Оплата получена!*
                *От:* %s
                *Чат:* %d
                *Звёзд:* %d
                *Добавлено саммари:* %d""".formatted(donorName, chatId, stars, creditsAdded);
        chatSummaryBot.sendMessage(adminChatId, message);
        log.info("Payment of {} star(s) from {} in chat {}, added {} credits", stars, donorName, chatId, creditsAdded);
    }

    public void notifyTokenUsage(String operation, long chatId, int promptTokens, int thoughtsTokens,
                                 int candidatesTokens, int totalTokens) {
        notifyTokenUsage(operation, chatId, promptTokens, thoughtsTokens, candidatesTokens, totalTokens, null);
    }

    /**
     * Reports token usage to the admin, followed by the model's thought process (when Gemini
     * returned one) as a separate message — separate so a rejected thought message can never take
     * the usage stats down with it.
     */
    public void notifyTokenUsage(String operation, long chatId, int promptTokens, int thoughtsTokens,
                                 int candidatesTokens, int totalTokens, String thoughts) {
        // Gemini bills thinking + answer tokens as output.
        var outputTokens = thoughtsTokens + candidatesTokens;
        var cumulativeOutputTokens = totalOutputTokens.addAndGet(outputTokens);
        var message = """
                📊 *Gemini token usage*
                *Operation:* %s
                *Chat ID:* %d
                *Prompt:* %d
                *Thoughts:* %d
                *Candidates:* %d
                *Total:* %d
                *Output (this call):* %d
                *Output (accumulated):* %d""".formatted(operation, chatId, promptTokens, thoughtsTokens,
                candidatesTokens, totalTokens, outputTokens, cumulativeOutputTokens);
        chatSummaryBot.sendMessage(adminChatId, message);
        log.info("Gemini token usage ({}) for chat {}: prompt={}, thoughts={}, candidates={}, total={}, "
                        + "output={}, accumulatedOutput={}",
                operation, chatId, promptTokens, thoughtsTokens, candidatesTokens, totalTokens,
                outputTokens, cumulativeOutputTokens);

        notifyThoughtProcess(operation, chatId, thoughts);
    }

    private void notifyThoughtProcess(String operation, long chatId, String thoughts) {
        if (thoughts == null || thoughts.isBlank()) {
            return;
        }

        // Outgoing messages are parsed as HTML: the model's reasoning is free-form text, so it must
        // be escaped or Telegram rejects the whole message on a stray '<'. Escaping happens before
        // truncation because it can grow the text up to 5x, and the budget is on what is sent.
        var body = escapeHtml(thoughts.strip());
        var truncated = body.length() > MAX_THOUGHT_CHARS;
        if (truncated) {
            body = trimTrailingPartialEntity(body.substring(0, MAX_THOUGHT_CHARS));
        }

        var message = """
                🧠 <b>Gemini thought process</b>
                <b>Operation:</b> %s
                <b>Chat ID:</b> %d

                <blockquote expandable>%s</blockquote>%s""".formatted(
                escapeHtml(operation),
                chatId,
                body,
                truncated ? "\n… (truncated)" : "");
        chatSummaryBot.sendMessage(adminChatId, message);
    }

    /**
     * Drops a dangling half-written entity (e.g. {@code "&am"}) left by cutting escaped text, which
     * Telegram would reject as malformed HTML.
     */
    private static String trimTrailingPartialEntity(String escaped) {
        var lastAmp = escaped.lastIndexOf('&');
        if (lastAmp >= 0 && escaped.indexOf(';', lastAmp) < 0) {
            return escaped.substring(0, lastAmp);
        }
        return escaped;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    public void notifyOnFailure(long chatId, String groupName, String operation, Exception exception) {
        notifyOnFailure(chatId, groupName, operation, exception, false);
    }

    public void notifyOnFailure(long chatId, String groupName, String operation, Exception exception, boolean scheduled) {
        if (chatId == adminChatId) {
            return;
        }

        var header = scheduled ? "🚨 *Failure Alert (Scheduled)*" : "🚨 *Failure Alert*";
        var errorMessage = """
                %s
                *Operation:* %s
                *Group:* %s
                *Chat ID:* %d
                *Error:* %s""".formatted(
                header,
                operation,
                groupName,
                chatId,
                exception.getMessage() == null ? "Unknown error" : exception.getMessage()
        );

        chatSummaryBot.sendMessage(adminChatId, errorMessage);
        log.info("Notified admin about failure in operation: {}", operation);
    }
}
