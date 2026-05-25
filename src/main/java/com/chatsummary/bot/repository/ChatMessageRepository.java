package com.chatsummary.bot.repository;

import com.chatsummary.bot.model.ChatMessage;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface ChatMessageRepository extends MongoRepository<ChatMessage, String> {

    List<ChatMessage> findByChatIdAndTimestampAfter(long chatId, Instant since);

    List<ChatMessage> findByChatIdAndTimestampBefore(long chatId, Instant before);

    void deleteByChatIdAndTimestampBefore(long chatId, Instant before);

    @Query(value = "{}", fields = "{ 'chatId': 1 }")
    List<ChatIdOnly> findAllChatIds();
}
