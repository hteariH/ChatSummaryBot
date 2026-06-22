package com.chatsummary.bot.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.chatsummary.bot.integration.MongoIntegrationTest;
import com.chatsummary.bot.model.ChatMessage;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ChatMessageRepositoryIT extends MongoIntegrationTest {

    private static final long CHAT_A = -100L;
    private static final long CHAT_B = -200L;

    @Autowired
    private ChatMessageRepository repository;

    private final Instant now = Instant.now();

    @BeforeEach
    void seed() {
        repository.deleteAll();
        repository.save(message(CHAT_A, 1, now.minus(2, ChronoUnit.HOURS)));
        repository.save(message(CHAT_A, 2, now.minus(30, ChronoUnit.MINUTES)));
        repository.save(message(CHAT_B, 3, now.minus(10, ChronoUnit.MINUTES)));
    }

    private ChatMessage message(long chatId, int telegramId, Instant timestamp) {
        return new ChatMessage(null, chatId, telegramId, "sender", "text", List.of(), timestamp);
    }

    @Test
    void findByChatIdAndTimestampAfterRespectsWindowAndChat() {
        var recent = repository.findByChatIdAndTimestampAfter(CHAT_A, now.minus(1, ChronoUnit.HOURS));

        assertThat(recent).extracting(ChatMessage::telegramMessageId).containsExactly(2);
    }

    @Test
    void findByChatIdAndTimestampBeforeRespectsWindow() {
        var old = repository.findByChatIdAndTimestampBefore(CHAT_A, now.minus(1, ChronoUnit.HOURS));

        assertThat(old).extracting(ChatMessage::telegramMessageId).containsExactly(1);
    }

    @Test
    void deleteByChatIdAndTimestampBeforeOnlyRemovesMatching() {
        repository.deleteByChatIdAndTimestampBefore(CHAT_A, now.minus(1, ChronoUnit.HOURS));

        assertThat(repository.findAll()).extracting(ChatMessage::telegramMessageId)
                .containsExactlyInAnyOrder(2, 3);
    }

    @Test
    void findAllChatIdsProjectsChatIdPerDocument() {
        assertThat(repository.findAllChatIds()).extracting(ChatIdOnly::chatId)
                .containsExactlyInAnyOrder(CHAT_A, CHAT_A, CHAT_B);
    }
}
