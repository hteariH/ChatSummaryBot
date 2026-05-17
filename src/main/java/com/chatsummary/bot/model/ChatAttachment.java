package com.chatsummary.bot.model;

import org.bson.types.Binary;

public record ChatAttachment(
        String contentType,
        Binary data
) {
}
