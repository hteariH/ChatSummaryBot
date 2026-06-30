package com.chatsummary.bot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;
import org.telegram.telegrambots.meta.api.objects.payments.PreCheckoutQuery;

import java.util.List;

/**
 * Owns everything related to the "remove ads" upsell: answering Telegram Stars
 * pre-checkout queries, crediting successful payments, and sending the localized
 * ad/invoice. Self-contained with its own Telegram client to avoid a circular
 * dependency on {@link com.chatsummary.bot.telegram.ChatSummaryBot}.
 */
@Slf4j
@Service
public class AdService {

    private static final int CREDITS_PER_PURCHASE = 300;

    private final ChatConfigService chatConfigService;
    private final AdminNotificationService adminNotificationService;
    private final OkHttpTelegramClient telegramClient;

    @Autowired
    public AdService(
            @Value("${telegram.bot.token}") String botToken,
            ChatConfigService chatConfigService,
            AdminNotificationService adminNotificationService) {
        this(new OkHttpTelegramClient(botToken), chatConfigService, adminNotificationService);
    }

    AdService(
            OkHttpTelegramClient telegramClient,
            ChatConfigService chatConfigService,
            AdminNotificationService adminNotificationService) {
        this.chatConfigService = chatConfigService;
        this.adminNotificationService = adminNotificationService;
        this.telegramClient = telegramClient;
    }

    /**
     * Accept the Stars pre-checkout so the payment can complete.
     */
    public void answerPreCheckout(PreCheckoutQuery query) {
        try {
            telegramClient.execute(new AnswerPreCheckoutQuery(query.getId(), true));
        } catch (Exception exception) {
            log.warn("Failed to answer pre-checkout query", exception);
        }
    }

    /**
     * Credit a completed payment, thank the donor in the chat language, and notify the admin.
     */
    public void handleSuccessfulPayment(long chatId, String donorName, int stars) {
        chatConfigService.addSummaryCredits(chatId, stars);
        var lang = resolvePayLang(chatId);
        var thanks = switch (lang) {
            case RU -> "✅ Спасибо, %s! Добавлено %d саммари без рекламы.".formatted(donorName, stars);
            case UK -> "✅ Дякуємо, %s! Додано %d саммарі без реклами.".formatted(donorName, stars);
            case EN -> "✅ Thank you, %s! Added %d ad-free summaries.".formatted(donorName, stars);
        };
        sendMessage(chatId, thanks);
        adminNotificationService.notifyPayment(chatId, donorName, stars, stars);
    }

    /**
     * Consume one ad-free credit for the chat and, when they run out, show the ad.
     */
    public void consumeCreditAndMaybeShowAd(long chatId) {
        if (chatConfigService.getChatConfig(chatId).getSummaryCredits() >= 0) {
            var remaining = chatConfigService.consumeSummaryCredit(chatId);
            if (remaining == 0) {
                sendAdWithRemoveOption(chatId);
            }
        }
    }

    /**
     * Send the localized "remove ads" invoice (Telegram Stars).
     */
    public void sendAdWithRemoveOption(long chatId) {
        try {
            var lang = resolvePayLang(chatId);
            var title = switch (lang) {
                case RU -> "Убрать рекламу";
                case UK -> "Прибрати рекламу";
                case EN -> "Remove ads";
            };
            var description = switch (lang) {
                case RU -> "300 ⭐ = 30 саммари без рекламы для этого чата.";
                case UK -> "300 ⭐ = 30 саммарі без реклами для цього чату.";
                case EN -> "300 ⭐ = 30 ad-free summaries for this chat.";
            };
            var priceLabel = switch (lang) {
                case RU -> "300 звёзд = 30 саммари";
                case UK -> "300 зірок = 30 саммарі";
                case EN -> "300 ⭐ = 30 summaries";
            };
            var invoice = SendInvoice.builder()
                    .chatId(Long.toString(chatId))
                    .title(title)
                    .description(description)
                    .payload("summary_credits")
                    .currency("XTR")
                    .price(new LabeledPrice(priceLabel, CREDITS_PER_PURCHASE))
                    .build();
            telegramClient.execute(invoice);
        } catch (Exception exception) {
            log.error("Failed to send invoice to chat {}", chatId, exception);
        }
    }

    private void sendMessage(long chatId, String text) {
        try {
            var message = SendMessage.builder()
                    .chatId(Long.toString(chatId))
                    .parseMode("HTML")
                    .text(text)
                    .build();
            telegramClient.execute(message);
        } catch (Exception exception) {
            log.error("Failed to send message to chat {}", chatId, exception);
        }
    }

    private enum PayLang {EN, RU, UK}

    private PayLang resolvePayLang(long chatId) {
        return resolvePayLang(chatConfigService.getChatConfig(chatId).getLanguage());
    }

    private PayLang resolvePayLang(String language) {
        if (language == null) {
            return PayLang.EN;
        }
        var normalized = language.trim().toLowerCase();
        if (normalized.startsWith("russ") || normalized.contains("русск")) {
            return PayLang.RU;
        }
        if (normalized.startsWith("ukrain") || normalized.contains("укра")) {
            return PayLang.UK;
        }
        return PayLang.EN;
    }
}
