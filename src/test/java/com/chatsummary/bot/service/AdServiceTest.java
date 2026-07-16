package com.chatsummary.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
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

        adService.handleSuccessfulPayment(CHAT_ID, "summary_credits:" + CHAT_ID, "Alice", 30);

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
            "Russian,   Полные саммари",
            "Ukrainian, Повні саммарі",
            "English,   Full summaries",
            "Spanish,   Full summaries"
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

    private void stubCredits(int credits) {
        var config = new ChatConfig(CHAT_ID, "0 0 9 * * *");
        config.setLanguage("English");
        config.setSummaryCredits(credits);
        when(chatConfigService.getChatConfig(CHAT_ID)).thenReturn(config);
    }

    @Test
    void applyPaywallDoesNotShowAdWhileCreditsRemain() throws Exception {
        stubCredits(6);
        when(chatConfigService.consumeSummaryCredit(CHAT_ID)).thenReturn(5);

        adService.applyPaywallAfterSummary(CHAT_ID);

        verify(chatConfigService).consumeSummaryCredit(CHAT_ID);
        verify(telegramClient, never()).execute(any(SendInvoice.class));
    }

    @Test
    void applyPaywallShowsAdWhenLastCreditIsConsumed() throws Exception {
        stubCredits(1);
        when(chatConfigService.consumeSummaryCredit(CHAT_ID)).thenReturn(0);

        adService.applyPaywallAfterSummary(CHAT_ID);

        verify(telegramClient).execute(any(SendInvoice.class));
    }

    @Test
    void applyPaywallKeepsOfferingWhenAlreadyExhausted() throws Exception {
        stubCredits(0);

        adService.applyPaywallAfterSummary(CHAT_ID);

        verify(chatConfigService, never()).consumeSummaryCredit(anyLong());
        verify(telegramClient).execute(any(SendInvoice.class));
    }

    @Test
    void applyPaywallDoesNothingWhenCreditsDisabled() {
        stubCredits(-1);

        adService.applyPaywallAfterSummary(CHAT_ID);

        verify(chatConfigService, never()).consumeSummaryCredit(anyLong());
    }

    @Test
    void hasFullSummaryAccessOnlyWhenCreditsAreNonZero() {
        stubCredits(3);
        assertThat(adService.hasFullSummaryAccess(CHAT_ID)).isTrue();

        stubCredits(-1);
        assertThat(adService.hasFullSummaryAccess(CHAT_ID)).isTrue();

        stubCredits(0);
        assertThat(adService.hasFullSummaryAccess(CHAT_ID)).isFalse();
    }

    @Test
    void buildPaywalledSummaryTruncatesToOneHundredCharsAndStripsHtml() {
        stubCredits(0);
        var summary = "<b>Alice</b> and Bob argued about the new deployment schedule for the whole "
                + "afternoon, then moved on to debate the quarterly budget in great detail.";

        var paywalled = adService.buildPaywalledSummary(CHAT_ID, summary);

        assertThat(paywalled).doesNotContain("<b>");
        assertThat(paywalled.split("\n\n")[0]).hasSize(101).endsWith("…"); // 100 chars + ellipsis
        assertThat(paywalled).contains("out of summaries");
    }

    @Test
    void paymentCreditsTheChatFromThePayloadNotThePaymentChat() {
        // successful_payment arrives in the payer's private chat; the group id rides in the payload.
        long privateChatId = 777L;
        stubLanguage("English");
        var privateConfig = new ChatConfig(privateChatId, "0 0 9 * * *");
        when(chatConfigService.getChatConfig(privateChatId)).thenReturn(privateConfig);

        adService.handleSuccessfulPayment(privateChatId, "summary_credits:" + CHAT_ID, "Alice", 300);

        verify(chatConfigService).addSummaryCredits(CHAT_ID, 30);
        verify(chatConfigService, never()).addSummaryCredits(eq(privateChatId), anyInt());
    }

    @Test
    void paymentFallsBackToThePaymentChatForLegacyPayloads() {
        stubLanguage("English");

        adService.handleSuccessfulPayment(CHAT_ID, "summary_credits", "Alice", 300);

        verify(chatConfigService).addSummaryCredits(CHAT_ID, 30);
    }

    @Test
    void paymentPostsFullSummaryAnewAndDeletesTeaser() throws Exception {
        var config = new ChatConfig(CHAT_ID, "0 0 9 * * *");
        config.setLanguage("English");
        config.setPendingFullSummaryMessageId(42);
        config.setPendingFullSummaryText("📋 <b>Summary</b>\n\nThe full text the chat paid to read.");
        when(chatConfigService.getChatConfig(CHAT_ID)).thenReturn(config);
        var sent = Mockito.mock(Message.class);
        when(sent.getMessageId()).thenReturn(99);
        when(telegramClient.execute(any(SendMessage.class))).thenReturn(sent);

        adService.handleSuccessfulPayment(CHAT_ID, "summary_credits:" + CHAT_ID, "Alice", 300);

        // Full text is posted as a new message (id 99), the old teaser (id 42) is deleted, stash cleared.
        var delete = ArgumentCaptor.forClass(DeleteMessage.class);
        verify(telegramClient).execute(delete.capture());
        assertThat(delete.getValue().getMessageId()).isEqualTo(42);
        verify(chatConfigService).updateLastSummary(eq(CHAT_ID), eq(99), eq(99), anyString());
        verify(chatConfigService).clearPendingFullSummary(CHAT_ID);
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
