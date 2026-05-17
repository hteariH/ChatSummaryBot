package com.chatsummary.bot.model;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "daily_summaries")
public record DailySummary(
        @Id String id,
        @Indexed long chatId,
        String text,
        @Indexed Instant timestamp
) {

    public DailySummary {
        timestamp = timestamp == null ? Instant.now() : timestamp;
    }

    public DailySummary(long chatId, String text) {
        this(null, chatId, text, Instant.now());
    }
}
