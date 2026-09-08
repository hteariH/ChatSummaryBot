package com.chatsummary.bot.repository;

import com.chatsummary.bot.model.ChatConfig;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatConfigRepository extends MongoRepository<ChatConfig, String> {

    Optional<ChatConfig> findByChatId(long chatId);

    void deleteByChatId(long chatId);

    /** Re-point a chat's settings row at a new chat id (basic group → supergroup). */
    @Query("{ 'chatId': ?0 }")
    @Update("{ '$set': { 'chatId': ?1 } }")
    long updateChatId(long oldChatId, long newChatId);
}
