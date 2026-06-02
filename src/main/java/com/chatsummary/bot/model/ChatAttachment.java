package com.chatsummary.bot.model;

import org.bson.types.Binary;

public record ChatAttachment(
        String contentType,
        Binary data,
        String filePath,
        Long fileSize
) {
    public ChatAttachment(String contentType, Binary data) {
        this(contentType, data, null, null);
    }
}
