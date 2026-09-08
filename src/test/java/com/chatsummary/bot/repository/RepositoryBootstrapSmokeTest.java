package com.chatsummary.bot.repository;

import com.mongodb.client.MongoClients;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/**
 * Boots the Mongo repository proxies without a server: query/@Update definitions are parsed and
 * verified at proxy creation, so a malformed one fails here instead of at container startup.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = RepositoryBootstrapSmokeTest.Config.class)
class RepositoryBootstrapSmokeTest {

    @Configuration
    @EnableMongoRepositories(basePackageClasses = ChatMessageRepository.class)
    static class Config {
        @Bean
        MongoTemplate mongoTemplate() {
            return new MongoTemplate(MongoClients.create("mongodb://localhost:27017"), "bootstrap-smoke-test");
        }
    }

    @Test
    void repositoryQueryMethodsAreValid() {
        // Reaching here means every derived query and @Query/@Update pair was accepted.
    }
}
