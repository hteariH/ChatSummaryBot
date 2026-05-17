package com.chatsummary.bot.repository;

import com.chatsummary.bot.model.DailySummary;
import java.time.Instant;
import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DailySummaryRepository extends MongoRepository<DailySummary, String> {

    List<DailySummary> findByChatIdAndTimestampAfter(long chatId, Instant since);

    void deleteByChatIdAndTimestampBefore(long chatId, Instant before);
}
