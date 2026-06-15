package com.chatsummary.bot.util;

import java.util.Optional;

public final class TelegramLinks {

    private TelegramLinks() {
    }

    public static Optional<String> messageUrl(long chatId, Integer messageId) {
        if (messageId == null) {
            return Optional.empty();
        }

        var id = Long.toString(chatId);
        if (id.startsWith("-100")) {
            return Optional.of("https://t.me/c/%s/%d".formatted(id.substring(4), messageId));
        }

        return Optional.empty();
    }
}
