package com.chatsummary.bot.repository;

import com.chatsummary.bot.model.ChatMessage;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends MongoRepository<ChatMessage, String> {

    List<ChatMessage> findByChatIdAndTimestampAfter(long chatId, Instant since);

    List<ChatMessage> findByChatIdAndTimestampBefore(long chatId, Instant before);

    void deleteByChatIdAndTimestampBefore(long chatId, Instant before);

    @Query(value = "{}", fields = "{ 'chatId': 1 }")
    List<ChatIdOnly> findAllChatIds();

    /** Re-point every stored message of a chat at a new chat id (basic group → supergroup). */
    @Query("{ 'chatId': ?0 }")
    @Update("{ '$set': { 'chatId': ?1 } }")
    long updateChatId(long oldChatId, long newChatId);
}
