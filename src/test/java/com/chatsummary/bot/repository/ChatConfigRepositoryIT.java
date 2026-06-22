package com.chatsummary.bot.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.chatsummary.bot.integration.MongoIntegrationTest;
import com.chatsummary.bot.model.ChatConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ChatConfigRepositoryIT extends MongoIntegrationTest {

    @Autowired
    private ChatConfigRepository repository;

    @Test
    void savesAndFindsByChatId() {
        var config = new ChatConfig(-100L, "0 0 9 * * *");
        config.setLanguage("Ukrainian");
        config.setSummaryCredits(12);
        repository.save(config);

        var found = repository.findByChatId(-100L);

        assertThat(found).isPresent();
        assertThat(found.get().getLanguage()).isEqualTo("Ukrainian");
        assertThat(found.get().getSummaryCredits()).isEqualTo(12);
    }

    @Test
    void returnsEmptyForUnknownChat() {
        assertThat(repository.findByChatId(424242L)).isEmpty();
    }
}
