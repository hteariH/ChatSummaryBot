package com.chatsummary.bot.repository;

import com.chatsummary.bot.model.DailySummary;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import org.springframework.stereotype.Repository;

@Repository
public interface DailySummaryRepository extends MongoRepository<DailySummary, String> {

    List<DailySummary> findByChatIdAndTimestampAfter(long chatId, Instant since);
    List<DailySummary> findByChatId(long chatId);

    void deleteByChatIdAndTimestampBefore(long chatId, Instant before);
    void deleteByChatId(long chatId);

    /** Re-point every stored daily summary of a chat at a new chat id. */
    @Query("{ 'chatId': ?0 }")
    @Update("{ '$set': { 'chatId': ?1 } }")
    long updateChatId(long oldChatId, long newChatId);
}
