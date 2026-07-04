package com.chatsummary.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chatsummary.bot.model.ChatAttachment;
import com.chatsummary.bot.model.ChatMessage;
import com.chatsummary.bot.repository.ChatIdOnly;
import com.chatsummary.bot.repository.ChatMessageRepository;
import com.chatsummary.bot.repository.DailySummaryRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class MessageServiceTest {

    private static final long CHAT_ID = -100L;

    private ChatMessageRepository chatMessageRepository;
    private DailySummaryRepository dailySummaryRepository;
    private TelegramDownloadService telegramDownloadService;
    private VoiceStorageService voiceStorageService;
    private AudioExtractionService audioExtractionService;
    private MessageService service;

    @BeforeEach
    void setUp() {
        chatMessageRepository = Mockito.mock(ChatMessageRepository.class);
        dailySummaryRepository = Mockito.mock(DailySummaryRepository.class);
        telegramDownloadService = Mockito.mock(TelegramDownloadService.class);
        voiceStorageService = Mockito.mock(VoiceStorageService.class);
        audioExtractionService = Mockito.mock(AudioExtractionService.class);
        service = new MessageService(
                chatMessageRepository, dailySummaryRepository, telegramDownloadService,
                voiceStorageService, audioExtractionService);
    }

    @Test
    void clearOldMessagesDeletesAttachmentFilesThenRows() {
        var before = Instant.now();
        var withFile = new ChatMessage(CHAT_ID, 1, "Alice", "voice",
                List.of(new ChatAttachment("audio/ogg", null, "/data/voices/1.ogg", 100L)));
        var withoutFile = new ChatMessage(CHAT_ID, 2, "Bob", "hi");
        when(chatMessageRepository.findByChatIdAndTimestampBefore(CHAT_ID, before))
                .thenReturn(List.of(withFile, withoutFile));

        service.clearOldMessages(CHAT_ID, before);

        verify(voiceStorageService).deleteFile("/data/voices/1.ogg");
        verify(voiceStorageService, never()).deleteFile(null);
        verify(chatMessageRepository).deleteByChatIdAndTimestampBefore(CHAT_ID, before);
    }

    @Test
    void getAllActiveChatIdsMapsAndDeduplicates() {
        when(chatMessageRepository.findAllChatIds())
                .thenReturn(List.of(new ChatIdOnly(1L), new ChatIdOnly(1L), new ChatIdOnly(2L)));

        assertThat(service.getAllActiveChatIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void saveVoiceMessagePersistsWhenStorageReturnsAttachment() {
        var attachment = new ChatAttachment("audio/ogg", null, "/data/voices/5.ogg", 10L);
        when(telegramDownloadService.downloadBytes("file-5")).thenReturn(new byte[]{1, 2, 3});
        when(voiceStorageService.saveVoice(CHAT_ID, 5, new byte[]{1, 2, 3})).thenReturn(attachment);

        service.saveVoiceMessage(CHAT_ID, 5, "Alice", "file-5");

        verify(chatMessageRepository).save(any(ChatMessage.class));
    }

    @Test
    void saveVoiceMessageSkipsWhenStorageRejectsDueToLimits() {
        when(telegramDownloadService.downloadBytes("file-6")).thenReturn(new byte[]{1});
        when(voiceStorageService.saveVoice(CHAT_ID, 6, new byte[]{1})).thenReturn(null);

        service.saveVoiceMessage(CHAT_ID, 6, "Alice", "file-6");

        verify(chatMessageRepository, never()).save(any());
    }
}
