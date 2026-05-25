package com.chatsummary.bot.model;

import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "chat_messages")
public record ChatMessage(
        @Id String id,
        @Indexed long chatId,
        Integer telegramMessageId,
        String senderName,
        String text,
        List<ChatAttachment> attachments,
        @Indexed Instant timestamp
) {

    public ChatMessage {
        attachments = attachments == null ? List.of() : List.copyOf(attachments);
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public ChatMessage(long chatId, Integer telegramMessageId, String senderName, String text) {
        this(null, chatId, telegramMessageId, senderName, text, List.of(), Instant.now());
    }

    public ChatMessage(long chatId, Integer telegramMessageId, String senderName, String text, List<ChatAttachment> attachments) {
        this(null, chatId, telegramMessageId, senderName, text, attachments, Instant.now());
    }
}
