package com.chatsummary.bot.service;

import com.chatsummary.bot.telegram.ChatSummaryBot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AdminNotificationService {

    private final long adminChatId;
    private final ChatSummaryBot chatSummaryBot;

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
        var message = """
                📊 *Gemini token usage*
                *Operation:* %s
                *Chat ID:* %d
                *Prompt:* %d
                *Thoughts:* %d
                *Candidates:* %d
                *Total:* %d""".formatted(operation, chatId, promptTokens, thoughtsTokens, candidatesTokens, totalTokens);
        chatSummaryBot.sendMessage(adminChatId, message);
        log.info("Gemini token usage ({}) for chat {}: prompt={}, thoughts={}, candidates={}, total={}",
                operation, chatId, promptTokens, thoughtsTokens, candidatesTokens, totalTokens);
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
