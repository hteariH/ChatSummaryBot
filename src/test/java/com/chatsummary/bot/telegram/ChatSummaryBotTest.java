package com.chatsummary.bot.telegram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chatsummary.bot.service.AdService;
import com.chatsummary.bot.service.AdminNotificationService;
import com.chatsummary.bot.service.ChatConfigService;
import com.chatsummary.bot.service.GeminiSummaryService;
import com.chatsummary.bot.service.MessageService;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;

class ChatSummaryBotTest {

    @Test
    void splitMessageReturnsSingleChunkWhenTextFitsLimit() {
        var chunks = ChatSummaryBot.splitMessage("short text");

        assertThat(chunks).containsExactly("short text");
    }

    @Test
    void splitMessageSplitsLongTextIntoAllowedChunks() {
        var longText = "x".repeat(ChatSummaryBot.TELEGRAM_MAX_MESSAGE_LENGTH + 25);

        var chunks = ChatSummaryBot.splitMessage(longText);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(ChatSummaryBot.TELEGRAM_MAX_MESSAGE_LENGTH);
        assertThat(chunks.get(1)).hasSize(25);
        assertThat(chunks.stream().collect(Collectors.joining())).isEqualTo(longText);
    }

    @Test
    void splitMessagePrefersNewlineBoundary() {
        var prefix = "x".repeat(ChatSummaryBot.TELEGRAM_MAX_MESSAGE_LENGTH - 1);
        var text = prefix + "\nsecond line";

        var chunks = ChatSummaryBot.splitMessage(text);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).endsWith("\n");
        assertThat(chunks.stream().collect(Collectors.joining())).isEqualTo(text);
    }

    @Test
    void splitMessageSkipsWhitespaceOnlyChunks() {
        var newlineBlock = IntStream.range(0, ChatSummaryBot.TELEGRAM_MAX_MESSAGE_LENGTH + 10)
                .mapToObj(i -> "\n")
                .collect(Collectors.joining());

        var chunks = ChatSummaryBot.splitMessage(newlineBlock);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(newlineBlock.substring(0, ChatSummaryBot.TELEGRAM_MAX_MESSAGE_LENGTH));
    }

    @Test
    void splitMessageCanReserveSpaceInEveryChunk() {
        var maxChunkLength = ChatSummaryBot.TELEGRAM_MAX_MESSAGE_LENGTH
                - ChatSummaryBot.SUMMARY_NAVIGATION_LINK_RESERVE;
        var longText = "x".repeat(maxChunkLength + 25);

        var chunks = ChatSummaryBot.splitMessage(longText, maxChunkLength);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0)).hasSize(maxChunkLength);
        assertThat(chunks.get(1)).hasSize(25);
        assertThat(chunks.stream().collect(Collectors.joining())).isEqualTo(longText);
    }

    @Test
    void cleanHtmlRemovesUnsupportedTags() {
        var bot = new ChatSummaryBot(
                "token",
                mock(MessageService.class),
                mock(GeminiSummaryService.class),
                mock(ChatConfigService.class),
                mock(AdminNotificationService.class),
                mock(AdService.class)
        );

        String input = "Hello<br>World<br/>Next<BR />Line<p>Paragraph</p><b>Bold</b>";
        String expected = "Hello\nWorld\nNext\nLineParagraph\n<b>Bold</b>";
        
        assertThat(bot.cleanHtml(input)).isEqualTo(expected);
    }

    @Test
    void isChatGoneErrorDetectsTelegramChatGoneMessages() {
        assertThat(ChatSummaryBot.isChatGoneError(
                new RuntimeException("[400] Bad Request: chat not found"))).isTrue();
        assertThat(ChatSummaryBot.isChatGoneError(
                new RuntimeException("Forbidden: bot was kicked from the group chat"))).isTrue();
        // Signature can hide in a nested cause.
        assertThat(ChatSummaryBot.isChatGoneError(
                new RuntimeException("wrapper", new RuntimeException("PEER_ID_INVALID")))).isTrue();
        // The old id of a group that became a supergroup is dead for good, not a transient error.
        assertThat(ChatSummaryBot.isChatGoneError(new RuntimeException(
                "[400] Bad Request: group chat was upgraded to a supergroup chat"))).isTrue();
    }

    @Test
    void isChatGoneErrorIgnoresTransientErrors() {
        assertThat(ChatSummaryBot.isChatGoneError(
                new RuntimeException("[429] Too Many Requests"))).isFalse();
        assertThat(ChatSummaryBot.isChatGoneError(new RuntimeException((String) null))).isFalse();
    }

    @Test
    void purgeRemovedChatClearsDataAndNotifiesAdmin() {
        var messageService = mock(MessageService.class);
        var chatConfigService = mock(ChatConfigService.class);
        var adminNotificationService = mock(AdminNotificationService.class);
        var bot = new ChatSummaryBot(
                "token",
                messageService,
                mock(GeminiSummaryService.class),
                chatConfigService,
                adminNotificationService,
                mock(AdService.class)
        );

        bot.purgeRemovedChat(-1003427022824L, "kicked");

        verify(messageService).purgeChat(-1003427022824L);
        verify(chatConfigService).deleteChatConfig(-1003427022824L);
        verify(adminNotificationService).notifyChatRemoved(-1003427022824L, "kicked");
    }

    @Test
    void sendSummaryMessageReportsPartialDeliveryWhenALaterChunkFails() throws Exception {
        var telegramClient = mock(OkHttpTelegramClient.class);
        var delivered = new Message();
        delivered.setMessageId(42);
        // First chunk lands, the second one is rejected (e.g. a 429 or an HTML parse error).
        when(telegramClient.execute(any(SendMessage.class)))
                .thenReturn(delivered)
                .thenThrow(new TelegramApiRequestException("[429] Too Many Requests"));
        var bot = new ChatSummaryBot(
                "token",
                mock(MessageService.class),
                mock(GeminiSummaryService.class),
                mock(ChatConfigService.class),
                mock(AdminNotificationService.class),
                mock(AdService.class),
                telegramClient
        );
        var maxChunkLength = ChatSummaryBot.TELEGRAM_MAX_MESSAGE_LENGTH
                - ChatSummaryBot.SUMMARY_NAVIGATION_LINK_RESERVE;

        var result = bot.sendSummaryMessage(-1001605482413L, "x".repeat(maxChunkLength + 25));

        // Something already reached the chat, so the caller must treat it as delivered: reporting
        // null here makes the scheduler regenerate and re-send the summary on every tick.
        assertThat(result).isNotNull();
        assertThat(result.firstMessageId()).isEqualTo(42);
        assertThat(result.lastMessageId()).isEqualTo(42);
    }
}
