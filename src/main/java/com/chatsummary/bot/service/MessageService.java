package com.chatsummary.bot.service;

import com.chatsummary.bot.model.ChatMessage;
import com.chatsummary.bot.model.DailySummary;
import com.chatsummary.bot.repository.ChatIdOnly;
import com.chatsummary.bot.repository.ChatMessageRepository;
import com.chatsummary.bot.repository.DailySummaryRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;

@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final ChatMessageRepository chatMessageRepository;
    private final DailySummaryRepository dailySummaryRepository;
    private final TelegramDownloadService telegramDownloadService;

    public MessageService(
            ChatMessageRepository chatMessageRepository,
            DailySummaryRepository dailySummaryRepository,
            TelegramDownloadService telegramDownloadService
    ) {
        this.chatMessageRepository = chatMessageRepository;
        this.dailySummaryRepository = dailySummaryRepository;
        this.telegramDownloadService = telegramDownloadService;
    }

    public void saveMessage(long chatId, Integer telegramMessageId, String senderName, String text) {
        chatMessageRepository.save(new ChatMessage(chatId, telegramMessageId, senderName, text));
        log.info("Saved message {} from '{}' in chat {}", telegramMessageId, senderName, chatId);
    }

    public void saveDailySummary(long chatId, String text) {
        dailySummaryRepository.save(new DailySummary(chatId, text));
        log.info("Saved daily summary for chat {}", chatId);
    }

    public List<DailySummary> getDailySummariesSince(long chatId, Instant since) {
        return dailySummaryRepository.findByChatIdAndTimestampAfter(chatId, since);
    }

    public List<DailySummary> getDailySummaries(long chatId) {
        return dailySummaryRepository.findByChatId(chatId);
    }

    public void clearOldDailySummaries(long chatId) {
        dailySummaryRepository.deleteByChatId(chatId);
        log.info("Cleared daily summaries in chat {}", chatId);
    }

    public List<ChatMessage> getMessagesSince(long chatId, Instant since) {
        return chatMessageRepository.findByChatIdAndTimestampAfter(chatId, since);
    }

    public void clearOldMessages(long chatId, Instant before) {
        chatMessageRepository.deleteByChatIdAndTimestampBefore(chatId, before);
        log.info("Cleared messages before {} in chat {}", before, chatId);
    }

    public Set<Long> getAllActiveChatIds() {
        return chatMessageRepository.findAllChatIds()
                .stream()
                .map(ChatIdOnly::chatId)
                .collect(Collectors.toUnmodifiableSet());
    }

    public void savePhotoMessage(long chatId, Integer telegramMessageId, String senderName, List<PhotoSize> photo, String text) {
        var fileId = photo.getFirst().getFileId();
        log.info("Saving photo message {} from '{}' in chat {}", telegramMessageId, senderName, chatId);

        var downloadedPhoto = telegramDownloadService.downloadPhoto(fileId);
        log.info("Downloaded photo message {} from '{}' in chat {}", telegramMessageId, senderName, chatId);

        chatMessageRepository.save(new ChatMessage(chatId, telegramMessageId, senderName, text, List.of(downloadedPhoto)));
        log.debug("Saved photo message {} from '{}' in chat {}", telegramMessageId, senderName, chatId);
    }
}
