package com.chatsummary.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.chatsummary.bot.telegram.ChatSummaryBot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AdminNotificationServiceTest {

    private static final long ADMIN_CHAT_ID = 42L;

    private ChatSummaryBot bot;
    private AdminNotificationService service;

    @BeforeEach
    void setUp() {
        bot = mock(ChatSummaryBot.class);
        service = new AdminNotificationService(ADMIN_CHAT_ID, bot);
    }

    private String secondMessage() {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(bot, Mockito.times(2)).sendMessage(eq(ADMIN_CHAT_ID), captor.capture());
        return captor.getAllValues().get(1);
    }

    @Test
    void sendsThoughtProcessAsSeparateMessageAfterUsage() {
        service.notifyTokenUsage("summary", -100123L, 10, 20, 30, 60, "I read the transcript.");

        var thoughtMessage = secondMessage();
        assertThat(thoughtMessage).contains("Gemini thought process");
        assertThat(thoughtMessage).contains("I read the transcript.");
    }

    @Test
    void skipsThoughtMessageWhenThereAreNoThoughts() {
        service.notifyTokenUsage("summary", -100123L, 10, 0, 30, 40, null);

        verify(bot, Mockito.times(1)).sendMessage(eq(ADMIN_CHAT_ID), Mockito.anyString());
    }

    @Test
    void escapesHtmlInThoughtsSoTelegramDoesNotRejectTheMessage() {
        service.notifyTokenUsage("summary", -100123L, 1, 1, 1, 3, "compare a < b && <b>bold");

        var thoughtMessage = secondMessage();
        assertThat(thoughtMessage).contains("a &lt; b &amp;&amp; &lt;b&gt;bold");
        // Only the wrapper markup may remain as real tags.
        assertThat(thoughtMessage.replace("<b>", "").replace("</b>", "")
                .replace("<blockquote expandable>", "").replace("</blockquote>", ""))
                .doesNotContain("<");
    }

    @Test
    void truncatesLongThoughtsToASingleTelegramMessage() {
        service.notifyTokenUsage("summary", -100123L, 1, 1, 1, 3, "&".repeat(5_000));

        var thoughtMessage = secondMessage();
        assertThat(thoughtMessage).contains("… (truncated)");
        assertThat(thoughtMessage.length()).isLessThan(4096); // Telegram's per-message limit
        // A cut must never leave a half-written entity like "&am".
        assertThat(thoughtMessage).doesNotContain("&am<");
        var blockquote = thoughtMessage.substring(
                thoughtMessage.indexOf("<blockquote expandable>") + "<blockquote expandable>".length(),
                thoughtMessage.indexOf("</blockquote>"));
        assertThat(blockquote.replace("&amp;", "")).isEmpty();
    }
}
