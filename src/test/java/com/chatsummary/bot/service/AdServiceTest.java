package com.chatsummary.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chatsummary.bot.model.ChatConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;

class AdServiceTest {

    private static final long CHAT_ID = -100L;

    private OkHttpTelegramClient telegramClient;
    private ChatConfigService chatConfigService;
    private AdminNotificationService adminNotificationService;
    private AdService adService;

    @BeforeEach
    void setUp() {
        telegramClient = Mockito.mock(OkHttpTelegramClient.class);
        chatConfigService = Mockito.mock(ChatConfigService.class);
        adminNotificationService = Mockito.mock(AdminNotificationService.class);
        adService = new AdService(telegramClient, chatConfigService, adminNotificationService);
    }

    private void stubLanguage(String language) {
        var config = new ChatConfig(CHAT_ID, "0 0 9 * * *");
        config.setLanguage(language);
        when(chatConfigService.getChatConfig(CHAT_ID)).thenReturn(config);
    }

    @ParameterizedTest
    @CsvSource({
            "Russian,   Спасибо",
            "Ukrainian, Дякуємо",
            "English,   Thank you",
            "Spanish,   Thank you"
    })
    void handleSuccessfulPaymentCreditsThanksAndNotifies(String language, String expectedFragment) throws Exception {
        stubLanguage(language);

        adService.handleSuccessfulPayment(CHAT_ID, "Alice", 30);

        verify(chatConfigService).addSummaryCredits(CHAT_ID, 30);
        verify(adminNotificationService).notifyPayment(CHAT_ID, "Alice", 30, 30);

        var captor = ArgumentCaptor.forClass(SendMessage.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getText())
                .contains(expectedFragment)
                .contains("Alice");
    }

    @ParameterizedTest
    @CsvSource({
            "Russian,   Убрать рекламу",
            "Ukrainian, Прибрати рекламу",
            "English,   Remove ads",
            "Spanish,   Remove ads"
    })
    void sendAdWithRemoveOptionUsesLocalizedTitle(String language, String expectedTitle) throws Exception {
        stubLanguage(language);

        adService.sendAdWithRemoveOption(CHAT_ID);

        var captor = ArgumentCaptor.forClass(SendInvoice.class);
        verify(telegramClient).execute(captor.capture());
        var invoice = captor.getValue();
        assertThat(invoice.getTitle()).isEqualTo(expectedTitle);
        assertThat(invoice.getCurrency()).isEqualTo("XTR");
        assertThat(invoice.getChatId()).isEqualTo(Long.toString(CHAT_ID));
    }

    @Test
    void consumeCreditDoesNotShowAdWhileCreditsRemain() throws Exception {
        stubLanguage("English");
        when(chatConfigService.consumeSummaryCredit(CHAT_ID)).thenReturn(5);

        adService.consumeCreditAndMaybeShowAd(CHAT_ID);

        verify(chatConfigService).consumeSummaryCredit(CHAT_ID);
        verify(telegramClient, never()).execute(any(SendInvoice.class));
    }

    @Test
    void consumeCreditShowsAdWhenCreditsHitZero() throws Exception {
        stubLanguage("English");
        when(chatConfigService.consumeSummaryCredit(CHAT_ID)).thenReturn(0);

        adService.consumeCreditAndMaybeShowAd(CHAT_ID);

        verify(telegramClient).execute(any(SendInvoice.class));
    }

    @Test
    void consumeCreditDoesNothingWhenCreditsDisabled() {
        var config = new ChatConfig(CHAT_ID, "0 0 9 * * *");
        config.setSummaryCredits(-1);
        when(chatConfigService.getChatConfig(CHAT_ID)).thenReturn(config);

        adService.consumeCreditAndMaybeShowAd(CHAT_ID);

        verify(chatConfigService, never()).consumeSummaryCredit(anyLong());
    }

    @Test
    void answerPreCheckoutAcceptsTheQuery() throws Exception {
        var query = Mockito.mock(PreCheckoutQuery.class);
        when(query.getId()).thenReturn("pcq-1");

        adService.answerPreCheckout(query);

        var captor = ArgumentCaptor.forClass(AnswerPreCheckoutQuery.class);
        verify(telegramClient).execute(captor.capture());
        assertThat(captor.getValue().getOk()).isTrue();
        assertThat(captor.getValue().getPreCheckoutQueryId()).isEqualTo("pcq-1");
    }
}
