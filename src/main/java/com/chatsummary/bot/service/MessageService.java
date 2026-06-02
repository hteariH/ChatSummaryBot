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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.meta.api.objects.photo.PhotoSize;

@Slf4j
@RequiredArgsConstructor
@Service
public class MessageService {

    private final ChatMessageRepository chatMessageRepository;
    private final DailySummaryRepository dailySummaryRepository;
    private final TelegramDownloadService telegramDownloadService;
    private final VoiceStorageService voiceStorageService;

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
        var messagesToDelete = chatMessageRepository.findByChatIdAndTimestampBefore(chatId, before);
        for (var message : messagesToDelete) {
            for (var attachment : message.attachments()) {
                if (attachment.filePath() != null) {
                    voiceStorageService.deleteFile(attachment.filePath());
                }
            }
        }
        chatMessageRepository.deleteByChatIdAndTimestampBefore(chatId, before);
        log.info("Cleared {} messages before {} in chat {}", messagesToDelete.size(), before, chatId);
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
        log.info("Saved photo message {} from '{}' in chat {}", telegramMessageId, senderName, chatId);
    }

    public void saveVoiceMessage(long chatId, Integer telegramMessageId, String senderName, String fileId) {
        log.info("Saving voice message {} from '{}' in chat {}", telegramMessageId, senderName, chatId);

        var voiceData = telegramDownloadService.downloadBytes(fileId);
        var attachment = voiceStorageService.saveVoice(chatId, telegramMessageId, voiceData);

        if (attachment != null) {
            chatMessageRepository.save(new ChatMessage(chatId, telegramMessageId, senderName, "[Voice message]", List.of(attachment)));
            log.info("Saved voice message {} from '{}' in chat {}", telegramMessageId, senderName, chatId);
        } else {
            log.warn("Failed to save voice message {} from '{}' in chat {} due to storage limits", telegramMessageId, senderName, chatId);
        }
    }

    public void saveVideoNoteMessage(long chatId, Integer telegramMessageId, String senderName, String fileId) {
        log.info("Saving video note {} from '{}' in chat {}", telegramMessageId, senderName, chatId);

        var data = telegramDownloadService.downloadBytes(fileId);
        var attachment = voiceStorageService.saveVideoNote(chatId, telegramMessageId, data);

        if (attachment != null) {
            chatMessageRepository.save(new ChatMessage(chatId, telegramMessageId, senderName, "[video note]", List.of(attachment)));
            log.info("Saved video note {} from '{}' in chat {}", telegramMessageId, senderName, chatId);
        } else {
            log.warn("Failed to save video note {} from '{}' in chat {} due to storage limits", telegramMessageId, senderName, chatId);
        }
    }

}
