package com.chatsummary.bot.repository

import com.chatsummary.bot.model.DailySummary
import org.springframework.data.mongodb.repository.MongoRepository
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
interface DailySummaryRepository : MongoRepository<DailySummary, String> {
    fun findByChatIdAndTimestampAfter(chatId: Long, since: Instant): List<DailySummary>
    fun deleteByChatIdAndTimestampBefore(chatId: Long, before: Instant)
}
