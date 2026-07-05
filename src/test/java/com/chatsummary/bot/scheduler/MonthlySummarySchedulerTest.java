package com.chatsummary.bot.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chatsummary.bot.model.ChatConfig;
import com.chatsummary.bot.model.DailySummary;
import com.chatsummary.bot.service.AdminNotificationService;
import com.chatsummary.bot.service.ChatConfigService;
import com.chatsummary.bot.service.GeminiSummaryService;
import com.chatsummary.bot.service.MessageService;
import com.chatsummary.bot.telegram.ChatSummaryBot;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
class MonthlySummarySchedulerTest {

    private static final long CHAT_ID = -1001605482413L;

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

    @InjectMocks
    private MonthlySummaryScheduler scheduler;

    private ChatConfig config;

    @BeforeEach
    void setUp() {
        scheduler.geminiThrottleMillis = 0L;

        config = new ChatConfig(CHAT_ID, "0 0 21 * * *");
        config.setMonthlySummaryEnabled(true);
        // A watermark 45 days back guarantees the last-day-of-month 21:00 cron
        // has fired since then, whatever today's date is.
        config.setLastMonthlyProcessedAt(Instant.now().minus(45, ChronoUnit.DAYS));
        when(chatConfigService.getAllConfigs()).thenReturn(List.of(config));
        when(messageService.getDailySummaries(CHAT_ID))
                .thenReturn(List.of(new DailySummary(CHAT_ID, "daily summary")));
        when(chatSummaryBot.getChatTitle(anyLong())).thenReturn("Test Chat");
    }

    @Test
    void successAdvancesWatermarkAndClearsDailySummaries() throws InterruptedException {
        when(geminiSummaryService.summarizeMonthly(any(), anyString())).thenReturn("digest");
        when(chatSummaryBot.sendMessageReturningId(eq(CHAT_ID), anyString())).thenReturn(42);

        scheduler.sendMonthlySummaries();

        verify(messageService).clearOldDailySummaries(CHAT_ID);
        verify(chatConfigService).updateLastMonthlyProcessedAt(eq(CHAT_ID), any());
    }

    @Test
    void geminiFailureDoesNotAdvanceWatermarkOrClearSummaries() throws InterruptedException {
        when(geminiSummaryService.summarizeMonthly(any(), anyString()))
                .thenThrow(new RuntimeException("Gemini down"));

        scheduler.sendMonthlySummaries();

        verify(messageService, never()).clearOldDailySummaries(anyLong());
        verify(chatConfigService, never()).updateLastMonthlyProcessedAt(anyLong(), any());
        verify(adminNotificationService).notifyOnFailure(
                eq(CHAT_ID), any(), eq("Monthly Summary"), any(), eq(true));
    }

    @Test
    void failedDigestIsRetriedOnNextTickUntilSuccess() throws InterruptedException {
        when(geminiSummaryService.summarizeMonthly(any(), anyString()))
                .thenThrow(new RuntimeException("Gemini down"))
                .thenReturn("digest");
        when(chatSummaryBot.sendMessageReturningId(eq(CHAT_ID), anyString())).thenReturn(42);

        scheduler.sendMonthlySummaries();
        scheduler.sendMonthlySummaries();

        verify(geminiSummaryService, times(2)).summarizeMonthly(any(), anyString());
        verify(messageService, times(1)).clearOldDailySummaries(CHAT_ID);
        verify(chatConfigService, times(1)).updateLastMonthlyProcessedAt(eq(CHAT_ID), any());
    }

    @Test
    void undeliveredDigestKeepsDailySummariesAndWatermark() throws InterruptedException {
        when(geminiSummaryService.summarizeMonthly(any(), anyString())).thenReturn("digest");
        when(chatSummaryBot.sendMessageReturningId(eq(CHAT_ID), anyString())).thenReturn(null);

        scheduler.sendMonthlySummaries();

        verify(messageService, never()).clearOldDailySummaries(anyLong());
        verify(chatConfigService, never()).updateLastMonthlyProcessedAt(anyLong(), any());
    }

    @Test
    void chatWithoutDailySummariesIsMarkedProcessedWithoutGeminiCall() throws InterruptedException {
        when(messageService.getDailySummaries(CHAT_ID)).thenReturn(List.of());

        scheduler.sendMonthlySummaries();

        verify(geminiSummaryService, never()).summarizeMonthly(any(), anyString());
        verify(chatConfigService).updateLastMonthlyProcessedAt(eq(CHAT_ID), any());
    }

    @Test
    void chatProcessedRecentlyIsNotDue() throws InterruptedException {
        config.setLastMonthlyProcessedAt(Instant.now());

        scheduler.sendMonthlySummaries();

        verify(geminiSummaryService, never()).summarizeMonthly(any(), anyString());
        verify(chatConfigService, never()).updateLastMonthlyProcessedAt(anyLong(), any());
    }

    @Test
    void chatWithMonthlyDisabledIsSkipped() throws InterruptedException {
        config.setMonthlySummaryEnabled(false);

        scheduler.sendMonthlySummaries();

        verify(geminiSummaryService, never()).summarizeMonthly(any(), anyString());
        verify(chatConfigService, never()).updateLastMonthlyProcessedAt(anyLong(), any());
    }
}
