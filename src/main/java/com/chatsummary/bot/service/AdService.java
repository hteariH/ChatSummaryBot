package com.chatsummary.bot.service;

import com.chatsummary.bot.telegram.ChatSummaryBot;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
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

    /** Price of one purchase, in Telegram Stars. */
    private static final int STAR_PRICE = 300;
    /** Ad-free summaries granted per purchase. */
    private static final int SUMMARIES_PER_PURCHASE = 30;
    /** Characters of the summary shown to chats that have run out of credits. */
    private static final int TEASER_CHARS = 100;

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
     * Each purchase grants a fixed {@link #SUMMARIES_PER_PURCHASE} credits regardless of the paid amount.
     */
    public void handleSuccessfulPayment(long chatId, String donorName, int stars) {
        chatConfigService.addSummaryCredits(chatId, SUMMARIES_PER_PURCHASE);
        revealPendingSummary(chatId);
        var lang = resolvePayLang(chatId);
        var thanks = switch (lang) {
            case RU -> "✅ Спасибо, %s! Добавлено %d саммари для этого чата.".formatted(donorName, SUMMARIES_PER_PURCHASE);
            case UK -> "✅ Дякуємо, %s! Додано %d саммарі для цього чату.".formatted(donorName, SUMMARIES_PER_PURCHASE);
            case EN -> "✅ Thank you, %s! Added %d summaries for this chat.".formatted(donorName, SUMMARIES_PER_PURCHASE);
        };
        sendMessage(chatId, thanks);
        adminNotificationService.notifyPayment(chatId, donorName, stars, SUMMARIES_PER_PURCHASE);
    }

    /**
     * Whether the chat may receive the full summary text. {@code false} only once the paid
     * credits are exhausted ({@code == 0}); a negative credit count disables the paywall entirely.
     */
    public boolean hasFullSummaryAccess(long chatId) {
        return chatConfigService.getChatConfig(chatId).getSummaryCredits() != 0;
    }

    /**
     * Build the paywalled summary shown to chats that have run out of credits: the first
     * {@link #TEASER_CHARS} characters of the summary (HTML stripped) plus a localized pay prompt.
     */
    public String buildPaywalledSummary(long chatId, String summary) {
        var plain = summary == null
                ? ""
                : summary.replaceAll("(?s)<[^>]+>", " ").replaceAll("\\s+", " ").strip();
        var teaser = plain.length() > TEASER_CHARS
                ? plain.substring(0, TEASER_CHARS).strip() + "…"
                : plain;
        return teaser + "\n\n" + paywallNotice(chatId);
    }

    /**
     * Update the paywall after a summary was delivered: consume a credit while any remain and,
     * once they run out (or are already gone), (re)send the purchase offer. A negative credit
     * count means the paywall is disabled for the chat.
     */
    public void applyPaywallAfterSummary(long chatId) {
        var credits = chatConfigService.getChatConfig(chatId).getSummaryCredits();
        if (credits < 0) {
            return;
        }
        if (credits == 0) {
            // Already exhausted: only a teaser was sent, so keep offering the purchase.
            sendAdWithRemoveOption(chatId);
            return;
        }
        var remaining = chatConfigService.consumeSummaryCredit(chatId);
        if (remaining == 0) {
            sendAdWithRemoveOption(chatId);
        }
    }

    /**
     * If the chat's most recent summary was delivered truncated (paywalled), post the full text as
     * a fresh message now that the chat has paid, delete the old teaser, and forget the stash.
     */
    private void revealPendingSummary(long chatId) {
        var config = chatConfigService.getChatConfig(chatId);
        var teaserMessageId = config.getPendingFullSummaryMessageId();
        var fullText = config.getPendingFullSummaryText();
        if (teaserMessageId == null || fullText == null) {
            return;
        }
        var chunks = ChatSummaryBot.splitMessage(cleanHtml(fullText));
        Integer firstNewId = null;
        Integer lastNewId = null;
        for (var chunk : chunks) {
            var sentId = sendMessageReturningId(chatId, chunk);
            if (sentId != null) {
                if (firstNewId == null) {
                    firstNewId = sentId;
                }
                lastNewId = sentId;
            }
        }
        // Drop the truncated teaser now that the full summary has been posted anew.
        deleteMessage(chatId, teaserMessageId);
        if (firstNewId != null && lastNewId != null) {
            // Point navigation at the fresh message so the next summary's back-link lands on it.
            chatConfigService.updateLastSummary(chatId, firstNewId, lastNewId, chunks.get(chunks.size() - 1));
        }
        chatConfigService.clearPendingFullSummary(chatId);
    }

    private void deleteMessage(long chatId, Integer messageId) {
        try {
            telegramClient.execute(DeleteMessage.builder()
                    .chatId(Long.toString(chatId))
                    .messageId(messageId)
                    .build());
        } catch (Exception exception) {
            log.warn("Failed to delete teaser message {} in chat {}", messageId, chatId, exception);
        }
    }

    private static String cleanHtml(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)<p\\s*>", "")
                .replaceAll("(?i)</p\\s*>", "\n");
    }

    private String paywallNotice(long chatId) {
        return switch (resolvePayLang(chatId)) {
            case RU -> "🔒 Саммари для этого чата закончились. Оплатите %d ⭐, чтобы снова получать полные саммари."
                    .formatted(STAR_PRICE);
            case UK -> "🔒 Саммарі для цього чату закінчилися. Сплатіть %d ⭐, щоб знову отримувати повні саммарі."
                    .formatted(STAR_PRICE);
            case EN -> "🔒 This chat is out of summaries. Pay %d ⭐ to get full summaries again."
                    .formatted(STAR_PRICE);
        };
    }

    /**
     * Send the localized "remove ads" invoice (Telegram Stars).
     */
    public void sendAdWithRemoveOption(long chatId) {
        try {
            var lang = resolvePayLang(chatId);
            var title = switch (lang) {
                case RU -> "Полные саммари";
                case UK -> "Повні саммарі";
                case EN -> "Full summaries";
            };
            var description = switch (lang) {
                case RU -> "300 ⭐ = 30 полных саммари для этого чата.";
                case UK -> "300 ⭐ = 30 повних саммарі для цього чату.";
                case EN -> "300 ⭐ = 30 full summaries for this chat.";
            };
            var priceLabel = switch (lang) {
                case RU -> "300 ⭐ = 30 полных саммари";
                case UK -> "300 ⭐ = 30 повних саммарі";
                case EN -> "300 ⭐ = 30 full summaries";
            };
            var invoice = SendInvoice.builder()
                    .chatId(Long.toString(chatId))
                    .title(title)
                    .description(description)
                    .payload("summary_credits")
                    .currency("XTR")
                    .price(new LabeledPrice(priceLabel, STAR_PRICE))
                    .build();
            telegramClient.execute(invoice);
        } catch (Exception exception) {
            log.error("Failed to send invoice to chat {}", chatId, exception);
        }
    }

    private void sendMessage(long chatId, String text) {
        sendMessageReturningId(chatId, text);
    }

    private Integer sendMessageReturningId(long chatId, String text) {
        try {
            var message = SendMessage.builder()
                    .chatId(Long.toString(chatId))
                    .parseMode("HTML")
                    .text(text)
                    .build();
            var sent = telegramClient.execute(message);
            return sent == null ? null : sent.getMessageId();
        } catch (Exception exception) {
            log.error("Failed to send message to chat {}", chatId, exception);
            return null;
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
