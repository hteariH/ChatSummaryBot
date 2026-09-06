package com.chatsummary.bot.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chatsummary.bot.model.ChatConfig;
import com.chatsummary.bot.model.ChatMessage;
import com.chatsummary.bot.service.AdService;
import com.chatsummary.bot.service.AdminNotificationService;
import com.chatsummary.bot.service.ChatConfigService;
import com.chatsummary.bot.service.GeminiSummaryService;
import com.chatsummary.bot.service.MessageService;
import com.chatsummary.bot.telegram.ChatSummaryBot;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailySummarySchedulerTest {

    private static final long CHAT_ID = -1001605482413L;
    private static final int DUE_HOUR = java.time.ZonedDateTime.now().getHour();

    @Mock
    private MessageService messageService;
    @Mock
    private GeminiSummaryService geminiSummaryService;
    @Mock
    private ChatSummaryBot chatSummaryBot;
    @Mock
    private ChatConfigService chatConfigService;
    @Mock
    private AdminNotificationService adminNotificationService;
    @Mock
    private AdService adService;

    @InjectMocks
    private DailySummaryScheduler scheduler;

    @BeforeEach
    void disableThrottle() {
        scheduler.geminiThrottleMillis = 0L;
    }

    private ChatConfig dueConfig(long chatId, int hour) {
        var config = new ChatConfig(chatId, hour);
        config.setLastProcessedAt(java.time.ZonedDateTime.now().toLocalDate().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().minusSeconds(3600));
        return config;
    }

    private void mockSuccessfulPipeline() {
        when(messageService.getMessagesSince(eq(CHAT_ID), any()))
                .thenReturn(List.of(new ChatMessage(CHAT_ID, 1, "user", "hello")));
        when(geminiSummaryService.summarize(any(), anyString(), any())).thenReturn("summary text");
        when(chatSummaryBot.sendSummaryMessage(eq(CHAT_ID), anyString()))
                .thenReturn(new ChatSummaryBot.SentMessageResult(10, 10, "chunk"));
        when(chatSummaryBot.getChatTitle(anyLong())).thenReturn("Test Chat");
        when(adService.hasFullSummaryAccess(CHAT_ID)).thenReturn(true);
    }

    @Test
    void brokenConfigForOneChatDoesNotBlockOtherChats() throws InterruptedException {
        long brokenChatId = -100L;
        Set<Long> chatIds = new LinkedHashSet<>(List.of(brokenChatId, CHAT_ID));
        when(messageService.getAllActiveChatIds()).thenReturn(chatIds);
        when(chatConfigService.getChatConfig(brokenChatId)).thenThrow(new RuntimeException("DB error"));
        when(chatConfigService.getChatConfig(CHAT_ID)).thenReturn(dueConfig(CHAT_ID, DUE_HOUR));
        mockSuccessfulPipeline();

        scheduler.sendScheduledSummaries();

        verify(adminNotificationService).notifyOnFailure(
                eq(brokenChatId), any(), eq("Scheduled Summary"), any(), eq(true));
        verify(chatSummaryBot).sendSummaryMessage(eq(CHAT_ID), anyString());
        verify(chatConfigService).updateLastProcessedAt(eq(CHAT_ID), any());
    }

    @Test
    void geminiFailureDoesNotAdvanceWatermark() throws InterruptedException {
        when(messageService.getAllActiveChatIds()).thenReturn(Set.of(CHAT_ID));
        when(chatConfigService.getChatConfig(CHAT_ID)).thenReturn(dueConfig(CHAT_ID, DUE_HOUR));
        when(messageService.getMessagesSince(eq(CHAT_ID), any()))
                .thenReturn(List.of(new ChatMessage(CHAT_ID, 1, "user", "hello")));
        when(geminiSummaryService.summarize(any(), anyString(), any()))
                .thenThrow(new RuntimeException("Gemini down"));
        when(chatSummaryBot.getChatTitle(anyLong())).thenReturn("Test Chat");

        scheduler.sendScheduledSummaries();

        verify(chatConfigService, never()).updateLastProcessedAt(anyLong(), any());
        verify(adminNotificationService).notifyOnFailure(
                eq(CHAT_ID), any(), eq("Scheduled Summary"), any(), eq(true));
    }

    @Test
    void undeliveredSummaryDoesNotAdvanceWatermarkOrConsumeCredit() throws InterruptedException {
        when(messageService.getAllActiveChatIds()).thenReturn(Set.of(CHAT_ID));
        when(chatConfigService.getChatConfig(CHAT_ID)).thenReturn(dueConfig(CHAT_ID, DUE_HOUR));
        mockSuccessfulPipeline();
        when(chatSummaryBot.sendSummaryMessage(eq(CHAT_ID), anyString())).thenReturn(null);

        scheduler.sendScheduledSummaries();

        verify(chatConfigService, never()).updateLastProcessedAt(anyLong(), any());
        verify(adService, never()).applyPaywallAfterSummary(anyLong());
        verify(messageService, never()).clearOldMessages(anyLong(), any());
    }

    @Test
    void postSendFailureStillAdvancesWatermark() throws InterruptedException {
        when(messageService.getAllActiveChatIds()).thenReturn(Set.of(CHAT_ID));
        when(chatConfigService.getChatConfig(CHAT_ID)).thenReturn(dueConfig(CHAT_ID, DUE_HOUR));
        mockSuccessfulPipeline();
        org.mockito.Mockito.doThrow(new RuntimeException("Mongo down"))
                .when(adService).applyPaywallAfterSummary(CHAT_ID);

        scheduler.sendScheduledSummaries();

        verify(chatConfigService).updateLastProcessedAt(eq(CHAT_ID), any());
        verify(adminNotificationService).notifyOnFailure(
                eq(CHAT_ID), any(), eq("Scheduled Summary Post-Processing"), any(), eq(true));
    }

    @Test
    void emptyChatAdvancesWatermarkWithoutGeminiCall() throws InterruptedException {
        when(messageService.getAllActiveChatIds()).thenReturn(Set.of(CHAT_ID));
        when(chatConfigService.getChatConfig(CHAT_ID)).thenReturn(dueConfig(CHAT_ID, DUE_HOUR));
        when(messageService.getMessagesSince(eq(CHAT_ID), any())).thenReturn(List.of());

        scheduler.sendScheduledSummaries();

        verify(geminiSummaryService, never()).summarize(any(), anyString(), any());
        verify(chatConfigService).updateLastProcessedAt(eq(CHAT_ID), any());
    }
}
