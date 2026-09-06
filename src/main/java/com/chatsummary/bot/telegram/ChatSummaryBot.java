package com.chatsummary.bot.telegram;

import com.chatsummary.bot.service.AdService;
import com.chatsummary.bot.service.AdminNotificationService;
import com.chatsummary.bot.service.ChatConfigService;
import com.chatsummary.bot.service.GeminiSummaryService;
import com.chatsummary.bot.service.GeneralLLMService;
import com.chatsummary.bot.service.MessageService;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChat;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatMember;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;

@Slf4j
@Component
public class ChatSummaryBot implements SpringLongPollingBot, LongPollingSingleThreadUpdateConsumer {

    static final int TELEGRAM_MAX_MESSAGE_LENGTH = 4096;
    public static final int SUMMARY_NAVIGATION_LINK_RESERVE = 256;
    private static final List<String> ADMIN_STATUSES = List.of("administrator", "creator");
    private static final List<String> INACTIVE_STATUSES = List.of("left", "kicked");
    private static final List<String> ACTIVE_STATUSES = List.of("member", "administrator");
    // Telegram error fragments that mean the chat is gone / the bot can no longer reach it.
    // Matched case-insensitively against the exception message (and its causes).
    private static final List<String> CHAT_GONE_SIGNATURES = List.of(
            "chat not found",
            "bot was kicked",
            "bot is not a member",
            "bot was blocked",
            "group chat was deactivated",
            "peer_id_invalid");
    private static final Set<String> ADMIN_COMMANDS = Set.of(
            "/summary",
            "/setcron",
            "/sethour",
            "/settime",
            "/enable",
            "/disable",
            "/setlanguage",
            "/setmonthly",
            "/setprompt");

    @Getter
    private final String botToken;
    private final MessageService messageService;
    private final GeminiSummaryService geminiSummaryService;
    private final ChatConfigService chatConfigService;
    private final AdminNotificationService adminNotificationService;
    private final AdService adService;
    private final OkHttpTelegramClient telegramClient;
    private final ExecutorService llmExecutor = Executors.newSingleThreadExecutor();

    @Autowired
    private GeneralLLMService llmService;

    public ChatSummaryBot(
            @Value("${telegram.bot.token}") String botToken,
            MessageService messageService,
            GeminiSummaryService geminiSummaryService,
            ChatConfigService chatConfigService,
            AdminNotificationService adminNotificationService,
            AdService adService) {
        this.botToken = botToken;
        this.messageService = messageService;
        this.geminiSummaryService = geminiSummaryService;
        this.chatConfigService = chatConfigService;
        this.adminNotificationService = adminNotificationService;
        this.adService = adService;
        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    @Override
    public LongPollingUpdateConsumer getUpdatesConsumer() {
        return this;
    }

    @Override
    public void consume(Update update) {
        if (update.hasPreCheckoutQuery()) {
            adService.answerPreCheckout(update.getPreCheckoutQuery());
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
            var donorName = displayName(message.getFrom(), "Someone");
            // The successful_payment message arrives in the payer's private chat, so the chat to
            // credit is carried in the invoice payload rather than taken from the message.
            adService.handleSuccessfulPayment(
                    message.getChatId(), payment.getInvoicePayload(), donorName, payment.getTotalAmount());
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

        if (message.hasVideoNote()) {
            var config = chatConfigService.getChatConfig(chatId);
            if (config.isEnabled()) {
                messageService.saveVideoNoteMessage(chatId, message.getMessageId(), senderName,
                        message.getVideoNote().getFileId());
            }
            return;
        }

        if (message.hasVoice()) {
            var config = chatConfigService.getChatConfig(chatId);
            if (config.isEnabled()) {
                messageService.saveVoiceMessage(chatId, message.getMessageId(), senderName,
                        message.getVoice().getFileId());
            }
            return;
        }

        if (!message.hasText()) {
            return;
        }

        var text = message.getText();
        if (text.startsWith("/")) {
            handleCommand(chatId, message.getFrom().getId(), text);
        } else {
            var config = chatConfigService.getChatConfig(chatId);
            if (config.isEnabled()) {
                messageService.saveMessage(chatId, message.getMessageId(), senderName, text);
            }
            //respondIfAskForLink(chatId, message.getMessageId(), text);
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
                    addedBy);
        } else if (ACTIVE_STATUSES.contains(oldStatus) && INACTIVE_STATUSES.contains(newStatus)) {
            // The bot was removed/kicked/blocked: drop the chat's stored data so the schedulers
            // stop retrying (and re-notifying) forever for a chat we can no longer post to.
            purgeRemovedChat(member.getChat().getId(), "membership change → " + newStatus);
        }
    }

    /**
     * Forget everything about a chat the bot can no longer reach: its stored messages, daily
     * summaries and settings. Safe to call repeatedly (deletes are idempotent). Never calls back
     * into Telegram for the removed chat, so it can be invoked from a failed-send handler.
     */
    public void purgeRemovedChat(long chatId, String reason) {
        log.info("Purging data for chat {} ({})", chatId, reason);
        try {
            messageService.purgeChat(chatId);
            chatConfigService.deleteChatConfig(chatId);
            adminNotificationService.notifyChatRemoved(chatId, reason);
        } catch (Exception exception) {
            log.error("Failed to purge data for removed chat {}", chatId, exception);
        }
    }

    /** True when a Telegram failure means the chat is gone / unreachable, not a transient error. */
    static boolean isChatGoneError(Throwable error) {
        for (var cause = error; cause != null; cause = cause.getCause()) {
            var message = cause.getMessage();
            if (message != null) {
                var lower = message.toLowerCase(Locale.ROOT);
                if (CHAT_GONE_SIGNATURES.stream().anyMatch(lower::contains)) {
                    return true;
                }
            }
        }
        return false;
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
            case "/summary" -> handleSummaryCommand(chatId);
            case "/sethour", "/settime" -> handleSetHourCommand(chatId, text);
            case "/setcron" -> handleSetCronCommand(chatId, text);
            case "/enable", "/disable" -> {
                var enable = text.startsWith("/enable");
                chatConfigService.setEnabled(chatId, enable);
                sendMessage(chatId, enable
                        ? "✅ Bot enabled for this chat. Messages will be saved."
                        : "🚫 Bot disabled for this chat. Messages will no longer be saved.");
                log.info("{} chat {}", enable ? "Enabled" : "Disabled", chatId);
            }
            case "/setlanguage" -> handleSetLanguageCommand(chatId, text);
            case "/setmonthly" -> handleSetMonthlyCommand(chatId, text);
            case "/setprompt" -> handleSetPromptCommand(chatId, text);
            case "/testlink" -> handleTestLink(chatId, text);
            default -> throw new IllegalStateException("Admin command is not handled: " + command);
        }

        return true;
    }

    private void handleTestLink(long chatId, String text) {
        sendMessage(chatId, "[\\(link\\)](https://t.me/c/1605482413/704539)");
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

    private void handleSetHourCommand(long chatId, String text) {
        var parts = text.split(" ", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            sendMessage(chatId, "⚠️ Usage: `/sethour 21` (0-23, daily summary hour in UTC/system time)");
            return;
        }

        var hourStr = parts[1].trim();
        try {
            int hour = Integer.parseInt(hourStr);
            if (hour < 0 || hour > 23) {
                sendMessage(chatId, "⚠️ Invalid hour. Please provide an integer between 0 and 23.");
                return;
            }
            chatConfigService.saveChatConfig(chatId, hour);
            sendMessage(chatId, String.format("✅ Summary schedule updated to daily at %02d:00", hour));
            log.info("Updated summary hour for chat {}: {}", chatId, hour);
        } catch (NumberFormatException e) {
            sendMessage(chatId, "⚠️ Invalid hour format. Please provide a number from 0 to 23 (e.g. `/sethour 21`).");
        } catch (Exception exception) {
            log.error("Failed to save summary hour for chat {}", chatId, exception);
            sendMessage(chatId, "⚠️ Failed to save summary hour. Please try again.");
        }
    }

    private void handleSetCronCommand(long chatId, String text) {
        var parts = text.split(" ", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            sendMessage(chatId, "⚠️ `/setcron` is deprecated. Please use `/sethour <0-23>` (e.g., `/sethour 21`).");
            return;
        }

        var param = parts[1].trim();
        try {
            int hour;
            if (param.matches("^\\d+$")) {
                hour = Integer.parseInt(param);
            } else {
                hour = com.chatsummary.bot.service.ChatConfigMigrationService.extractHourFromCron(param, 21);
            }
            if (hour < 0 || hour > 23) {
                sendMessage(chatId, "⚠️ Invalid hour. Please use `/sethour <0-23>` (e.g., `/sethour 21`).");
                return;
            }
            chatConfigService.saveChatConfig(chatId, hour);
            sendMessage(chatId, String.format("✅ Summary schedule updated to daily at %02d:00 (Note: please use `/sethour <0-23>`)", hour));
            log.info("Updated summary hour via /setcron for chat {}: {}", chatId, hour);
        } catch (Exception exception) {
            log.error("Failed to save schedule for chat {}", chatId, exception);
            sendMessage(chatId, "⚠️ Failed to save schedule. Please use `/sethour <0-23>`.");
        }
    }

    private void handleSummaryCommand(long chatId) {
        try {
            var config = chatConfigService.getChatConfig(chatId);
            var since = config.getLastProcessedAt() == null
                    ? LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant()
                    : config.getLastProcessedAt();
            var messages = messageService.getMessagesSince(chatId, since);

            // if (messages.isEmpty()) {
                // sendMessage(chatId, "📭 No new messages since last summary. Nothing to summarize!");
                // return;
            // }

            sendMessage(chatId, ":( Summary command is discontinued, sorry...  Only daily summaries now");

            // var summary = geminiSummaryService.summarize(messages, config.getLanguage(), config.getCustomPrompt());
            // var sentMessageId = sendMessageReturningId(chatId, "📋 *Summary*\n\n" + summary);
            // if (sentMessageId == null) {
                // throw new IllegalStateException("Failed to deliver summary to chat " + chatId);
            // }
            // chatConfigService.updateLastProcessedAt(chatId, Instant.now());
            // adService.consumeCreditAndMaybeShowAd(chatId);
        } catch (Exception exception) {
            log.error("Error handling /summary command for chat {}", chatId, exception);
            sendMessage(chatId, "⚠️ Sorry, failed to generate summary. Please try again later.");
            var chatTitle = getChatTitle(chatId);
            adminNotificationService.notifyOnFailure(chatId, chatTitle, "Summary generation (/summary command)",
                    exception);
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
            String cleanText = cleanHtml(text);
            log.info("Sending message to chat {}: {}", chatId, cleanText);
            for (var chunk : splitMessage(cleanText)) {
                var message = SendMessage.builder()
                        .chatId(Long.toString(chatId))
                        .parseMode("HTML")
                        .text(chunk)
                        .build();
                telegramClient.execute(message);
            }
        } catch (Exception exception) {
            log.error("Failed to send message to chat {}", chatId, exception);
        }
    }

    public Integer sendMessageReturningId(long chatId, String text) {
        var sent = sendMessageReturningResult(chatId, text, TELEGRAM_MAX_MESSAGE_LENGTH);
        return sent == null ? null : sent.firstMessageId();
    }

    public SentMessageResult sendSummaryMessage(long chatId, String text) {
        return sendMessageReturningResult(
                chatId,
                text,
                TELEGRAM_MAX_MESSAGE_LENGTH - SUMMARY_NAVIGATION_LINK_RESERVE);
    }

    private SentMessageResult sendMessageReturningResult(long chatId, String text, int maxChunkLength) {
        try {
            String cleanText = cleanHtml(text);
            log.info("Sending message to chat {}: {}", chatId, cleanText);
            Integer firstMessageId = null;
            Integer lastMessageId = null;
            String lastChunk = null;
            for (var chunk : splitMessage(cleanText, maxChunkLength)) {
                var message = SendMessage.builder()
                        .chatId(Long.toString(chatId))
                        .parseMode("HTML")
                        .text(chunk)
                        .build();
                var sent = telegramClient.execute(message);
                if (firstMessageId == null) {
                    firstMessageId = sent.getMessageId();
                }
                lastMessageId = sent.getMessageId();
                lastChunk = chunk;
            }
            return new SentMessageResult(firstMessageId, lastMessageId, lastChunk);
        } catch (Exception exception) {
            log.error("Failed to send message to chat {}", chatId, exception);
            // Safety net for a missed my_chat_member update (e.g. bot was offline when kicked):
            // if the send failed because the chat is gone, purge it so we stop retrying.
            if (isChatGoneError(exception)) {
                purgeRemovedChat(chatId, "send failed: " + exception.getMessage());
            }
            return null;
        }
    }

    public void editMessageText(long chatId, Integer messageId, String text) {
        try {
            String cleanText = cleanHtml(text);
            var chunks = splitMessage(cleanText);
            var edit = EditMessageText.builder()
                    .chatId(Long.toString(chatId))
                    .messageId(messageId)
                    .parseMode("HTML")
                    .text(chunks.get(0))
                    .build();
            telegramClient.execute(edit);
            for (int i = 1; i < chunks.size(); i++) {
                sendMessage(chatId, chunks.get(i));
            }
        } catch (Exception exception) {
            log.warn("Failed to edit message {} in chat {}", messageId, chatId, exception);
        }
    }

    public void sendMessage(long chatId, Integer messageId, String text) {
        try {
            String cleanText = cleanHtml(text);
            log.info("Sending message to chat {}: {}", chatId, cleanText);
            var chunks = splitMessage(cleanText);
            for (int i = 0; i < chunks.size(); i++) {
                var messageBuilder = SendMessage.builder()
                        .chatId(Long.toString(chatId))
                        .parseMode("HTML")
                        .text(chunks.get(i));
                if (i == 0) {
                    messageBuilder.replyToMessageId(messageId);
                }
                telegramClient.execute(messageBuilder.build());
            }
        } catch (Exception exception) {
            log.error("Failed to send message to chat {}", chatId, exception);
        }
    }

    String cleanHtml(String text) {
        if (text == null) return null;
        return text.replaceAll("(?i)<br\\s*/?>", "\n")
                   .replaceAll("(?i)<p\\s*>", "")
                   .replaceAll("(?i)</p\\s*>", "\n");
    }

    public static List<String> splitMessage(String text) {
        return splitMessage(text, TELEGRAM_MAX_MESSAGE_LENGTH);
    }

    public static List<String> splitMessage(String text, int maxChunkLength) {
        if (maxChunkLength <= 0 || maxChunkLength > TELEGRAM_MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("maxChunkLength must be between 1 and "
                    + TELEGRAM_MAX_MESSAGE_LENGTH);
        }

        if (text == null || text.isEmpty()) {
            return List.of("");
        }

        var chunks = new ArrayList<String>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + maxChunkLength, text.length());
            if (end < text.length()) {
                int splitAt = text.lastIndexOf('\n', end - 1);
                if (splitAt >= start) {
                    end = splitAt + 1;
                }
            }

            var chunk = text.substring(start, end);
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            start = end;
        }

        if (chunks.isEmpty()) {
            chunks.add(text.substring(0, Math.min(text.length(), maxChunkLength)));
        }
        return chunks;
    }

    public record SentMessageResult(Integer firstMessageId, Integer lastMessageId, String lastChunk) {
    }

    private static String commandOf(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        // Сначала берем первое слово (до пробела)
        String firstWord = text.split("\\s+", 2)[0];

        // Затем делим его по символу '@' и берем только левую часть (саму команду)
        return firstWord.split("@", 2)[0];
    }

    private static String displayName(User user, String defaultName) {
        if (user == null) {
            return defaultName;
        }

        var fullName = "%s %s".formatted(
                firstNonNull(user.getFirstName(), ""),
                firstNonNull(user.getLastName(), "")).trim();

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
