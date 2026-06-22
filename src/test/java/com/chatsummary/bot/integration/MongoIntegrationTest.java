package com.chatsummary.bot.integration;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.data.mongodb.test.autoconfigure.DataMongoTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.MongoDBContainer;

/**
 * Base for repository integration tests: boots the Spring Data Mongo slice against a
 * real MongoDB running in a Testcontainers container (requires a Docker daemon).
 *
 * <p>Uses the Testcontainers singleton pattern — the container is started once and shared
 * across every test class in the suite (Ryuk reaps it at JVM exit). A {@code @Container}-managed
 * lifecycle would stop the container after the first test class, leaving later classes unable
 * to connect.
 */
@Tag("integration")
@DataMongoTest
public abstract class MongoIntegrationTest {

    @ServiceConnection
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7");

    static {
        MONGO.start();
    }
}
