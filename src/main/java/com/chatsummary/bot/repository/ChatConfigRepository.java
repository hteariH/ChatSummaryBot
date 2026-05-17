package com.chatsummary.bot.repository;

import com.chatsummary.bot.model.ChatConfig;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatConfigRepository extends MongoRepository<ChatConfig, String> {

    Optional<ChatConfig> findByChatId(long chatId);
}
