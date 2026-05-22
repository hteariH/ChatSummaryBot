package com.chatsummary.bot.telegram;

import com.chatsummary.bot.service.AdminNotificationService;
import com.chatsummary.bot.service.ChatConfigService;
import com.chatsummary.bot.service.GeminiSummaryService;
import com.chatsummary.bot.service.MessageService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerPreCheckoutQuery;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.invoices.SendInvoice;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.payments.LabeledPrice;

@Slf4j
@Component
public class ChatSummaryBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    private static final List<String> ADMIN_STATUSES = List.of("administrator", "creator");
    private static final List<String> INACTIVE_STATUSES = List.of("left", "kicked");
    private static final List<String> ACTIVE_STATUSES = List.of("member", "administrator");
    private static final Set<String> ADMIN_COMMANDS = Set.of(
            "/summary",
            "/setcron",
            "/enable",
            "/disable",
            "/setlanguage",
            "/setmonthly",
            "/setprompt"
    );

    @Getter
    private final String botToken;
    private final MessageService messageService;
    private final GeminiSummaryService geminiSummaryService;
    private final ChatConfigService chatConfigService;
    private final AdminNotificationService adminNotificationService;
    private final OkHttpTelegramClient telegramClient;

    public ChatSummaryBot(
            @Value("${telegram.bot.token}") String botToken,
            MessageService messageService,
            GeminiSummaryService geminiSummaryService,
            ChatConfigService chatConfigService,
            AdminNotificationService adminNotificationService
    ) {
        this.botToken = botToken;
        this.messageService = messageService;
        this.geminiSummaryService = geminiSummaryService;
        this.chatConfigService = chatConfigService;
        this.adminNotificationService = adminNotificationService;
        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        if (update.hasPreCheckoutQuery()) {
            var query = update.getPreCheckoutQuery();
            try {
                telegramClient.execute(new AnswerPreCheckoutQuery(query.getId(), true));
            } catch (Exception exception) {
                log.warn("Failed to answer pre-checkout query", exception);
            }
            return;
        }

        if (update.hasMyChatMember()) {
            handleChatMembershipChange(update);
            return;
        }

        if (!update.hasMessage()) {
            return;
        }

        var message = update.getMessage();

        if (message.hasSuccessfulPayment()) {
            var payment = message.getSuccessfulPayment();
            var stars = payment.getTotalAmount();
            var donorName = displayName(message.getFrom(), "Someone");
            chatConfigService.addSummaryCredits(message.getChatId(), stars);
            sendMessage(message.getChatId(), "✅ Спасибо, %s! Добавлено %d саммари без рекламы.".formatted(donorName, stars));
            adminNotificationService.notifyPayment(message.getChatId(), donorName, stars, stars);
            return;
        }

        var chatId = message.getChatId();
        var senderName = displayName(message.getFrom(), "Unknown");

        if (message.hasPhoto()) {
            var config = chatConfigService.getChatConfig(chatId);
            if (config.isEnabled()) {
                var text = firstNonNull(message.getCaption(), message.getText(), "");
                messageService.savePhotoMessage(chatId, message.getMessageId(), senderName, message.getPhoto(), text);
            }
            return;
        }

        if (!message.hasText()) {
            return;
        }

        var text = message.getText();
        if (handleCommand(chatId, message.getFrom().getId(), text)) {
            return;
        }

        if (!text.startsWith("/")) {
            var config = chatConfigService.getChatConfig(chatId);
            if (config.isEnabled()) {
                messageService.saveMessage(chatId, message.getMessageId(), senderName, text);
            }
        }
    }

    private void handleChatMembershipChange(Update update) {
        var member = update.getMyChatMember();
        var oldStatus = member.getOldChatMember().getStatus();
        var newStatus = member.getNewChatMember().getStatus();

        if (INACTIVE_STATUSES.contains(oldStatus) && ACTIVE_STATUSES.contains(newStatus)) {
            var chat = member.getChat();
            var addedBy = displayName(member.getFrom(), "Unknown");
            adminNotificationService.notifyNewChat(
                    chat.getId(),
                    firstNonNull(chat.getTitle(), "Unknown"),
                    chat.getType(),
                    addedBy
            );
        }
    }

    private boolean handleCommand(long chatId, long userId, String text) {
        var command = commandOf(text);
        if (!ADMIN_COMMANDS.contains(command)) {
            return false;
        }

        if (!requireAdmin(chatId, userId)) {
            return true;
        }

        switch (command) {
            case "/summary" ->
                handleSummaryCommand(chatId);
            case "/setcron" ->
                handleSetCronCommand(chatId, text);
            case "/enable", "/disable" -> {
                var enable = text.startsWith("/enable");
                chatConfigService.setEnabled(chatId, enable);
                sendMessage(chatId, enable
                        ? "✅ Bot enabled for this chat. Messages will be saved."
                        : "🚫 Bot disabled for this chat. Messages will no longer be saved.");
                log.info("{} chat {}", enable ? "Enabled" : "Disabled", chatId);
            }
            case "/setlanguage" ->
                handleSetLanguageCommand(chatId, text);
            case "/setmonthly" ->
                handleSetMonthlyCommand(chatId, text);
            case "/setprompt" ->
                handleSetPromptCommand(chatId, text);
            default -> throw new IllegalStateException("Admin command is not handled: " + command);
        }

        return true;
    }

    private boolean requireAdmin(long chatId, long userId) {
        if (isUserAdmin(chatId, userId)) {
            return true;
        }

        sendMessage(chatId, "⛔ Only group admins can use this command.");
        return false;
    }

    private void handleSetPromptCommand(long chatId, String text) {
        var parts = text.split(" ", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            chatConfigService.setCustomPrompt(chatId, null);
            sendMessage(chatId, "✅ Custom prompt cleared.");
            log.info("Cleared custom prompt for chat {}", chatId);
            return;
        }

        var customPrompt = parts[1].trim();
        chatConfigService.setCustomPrompt(chatId, customPrompt);
        sendMessage(chatId, "✅ Custom prompt set: " + customPrompt);
        log.info("Updated custom prompt for chat {}: {}", chatId, customPrompt);
    }

    private void handleSetLanguageCommand(long chatId, String text) {
        var parts = text.split(" ", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            sendMessage(chatId, "⚠️ Usage: /setlanguage English\nExamples: English, Russian, Spanish, German, French");
            return;
        }

        var language = parts[1].trim();
        chatConfigService.setLanguage(chatId, language);
        sendMessage(chatId, "✅ Summary language set to: " + language);
        log.info("Updated language for chat {}: {}", chatId, language);
    }

    private void handleSetMonthlyCommand(long chatId, String text) {
        var parts = text.split(" ", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            sendMessage(chatId, "⚠️ Usage: /setmonthly on/off");
            return;
        }

        var enabled = "on".equalsIgnoreCase(parts[1].trim());
        chatConfigService.setMonthlySummaryEnabled(chatId, enabled);
        sendMessage(chatId, enabled
                ? "✅ Monthly summary enabled for this chat."
                : "🚫 Monthly summary disabled for this chat.");
        log.info("Updated monthly summary status for chat {}: {}", chatId, enabled);
    }

    private void handleSetCronCommand(long chatId, String text) {
        var parts = text.split(" ", 2);
        if (parts.length < 2) {
            sendMessage(chatId, "⚠️ Usage: `/setcron 0 0 21 * * *` (seconds minutes hours day month day-of-week)");
            return;
        }

        var cron = parts[1].trim();
        if (!CronExpression.isValidExpression(cron)) {
            sendMessage(chatId, "⚠️ Invalid cron expression. Please use the Spring/Quartz format: `sec min hour day month dow`.");
            return;
        }

        try {
            chatConfigService.saveChatConfig(chatId, cron);
            sendMessage(chatId, "✅ Summary schedule updated to: `" + cron + "`");
            log.info("Updated cron for chat {}: {}", chatId, cron);
        } catch (Exception exception) {
            log.error("Failed to save cron for chat {}", chatId, exception);
            sendMessage(chatId, "⚠️ Failed to save cron. Please try again.");
        }
    }

    private void handleSummaryCommand(long chatId) {
        try {
            var config = chatConfigService.getChatConfig(chatId);
            var since = config.getLastProcessedAt() == null
                    ? LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
                    : config.getLastProcessedAt();
            var messages = messageService.getMessagesSince(chatId, since);

            if (messages.isEmpty()) {
                sendMessage(chatId, "📭 No new messages since last summary. Nothing to summarize!");
                return;
            }

            sendMessage(chatId, "⏳ Generating summary of %d messages...".formatted(messages.size()));

            var summary = geminiSummaryService.summarize(messages, config.getLanguage(), config.getCustomPrompt());
            sendMessage(chatId, "📋 *Summary*\n\n" + summary);
            chatConfigService.updateLastProcessedAt(chatId, Instant.now());

            var remaining = chatConfigService.consumeSummaryCredit(chatId);
            if (remaining == 0) {
                sendAdWithRemoveOption(chatId);
            }
        } catch (Exception exception) {
            log.error("Error handling /summary command for chat {}", chatId, exception);
            sendMessage(chatId, "⚠️ Sorry, failed to generate summary. Please try again later.");
            var chatTitle = getChatTitle(chatId);
            adminNotificationService.notifyOnFailure(chatId, chatTitle, "Summary generation (/summary command)", exception);
        }
    }

    public void sendAdWithRemoveOption(long chatId) {
        try {
            var invoice = SendInvoice.builder()
                    .chatId(Long.toString(chatId))
                    .title("Убрать рекламу")
                    .description("30 ⭐ = 30 саммари без рекламы для этого чата.")
                    .payload("summary_credits")
                    .currency("XTR")
                    .price(new LabeledPrice("30 звёзд = 30 саммари", 30))
                    .build();
            telegramClient.execute(invoice);
        } catch (Exception exception) {
            log.error("Failed to send invoice to chat {}", chatId, exception);
        }
    }

    private boolean isUserAdmin(long chatId, long userId) {
        try {
            var member = telegramClient.execute(new GetChatMember(Long.toString(chatId), userId));
            return ADMIN_STATUSES.contains(member.getStatus());
        } catch (Exception exception) {
            log.warn("Failed to check admin status for user {} in chat {}", userId, chatId, exception);
            return false;
        }
    }

    public String getChatTitle(long chatId) {
        try {
            var chat = telegramClient.execute(new GetChat(Long.toString(chatId)));
            return firstNonNull(chat.getTitle(), "Unknown");
        } catch (Exception exception) {
            log.warn("Failed to get chat title for {}", chatId, exception);
            return "Unknown";
        }
    }

    public void sendMessage(long chatId, String text) {
        try {
            var message = SendMessage.builder()
                    .chatId(Long.toString(chatId))
                    .text(text)
                    .build();
            telegramClient.execute(message);
        } catch (Exception exception) {
            log.error("Failed to send message to chat {}", chatId, exception);
        }
    }

    private static String commandOf(String text) {
        return text.split("\\s+", 2)[0];
    }

    private static String displayName(User user, String defaultName) {
        if (user == null) {
            return defaultName;
        }

        var fullName = "%s %s".formatted(
                firstNonNull(user.getFirstName(), ""),
                firstNonNull(user.getLastName(), "")
        ).trim();

        if (!fullName.isBlank()) {
            return fullName;
        }

        return firstNonNull(user.getUserName(), defaultName);
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (var value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
