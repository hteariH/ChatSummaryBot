package com.chatsummary.bot.model;

import org.bson.types.Binary;

public record ChatAttachment(
        String contentType,
        Binary data,
        String filePath
) {
    public ChatAttachment(String contentType, Binary data) {
        this(contentType, data, null);
    }

    public ChatAttachment(String contentType, String filePath) {
        this(contentType, null, filePath);
    }
}
