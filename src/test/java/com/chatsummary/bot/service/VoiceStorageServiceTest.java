package com.chatsummary.bot.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceStorageServiceTest {

    private static final long CHAT_ID = -100L;
    private static final long GENEROUS = 10_000_000L;

    private VoiceStorageService newService(Path root, long dailyLimit, long globalLimit) {
        return new VoiceStorageService(root.toString(), dailyLimit, globalLimit);
    }

    @Test
    void saveVoiceWritesFileAndReturnsAttachment(@TempDir Path root) {
        var service = newService(root, GENEROUS, GENEROUS);
        var data = new byte[]{1, 2, 3, 4};

        var attachment = service.saveVoice(CHAT_ID, 7, data);

        assertThat(attachment).isNotNull();
        assertThat(attachment.contentType()).isEqualTo("audio/ogg");
        assertThat(attachment.fileSize()).isEqualTo(4L);
        assertThat(Path.of(attachment.filePath())).exists();
        assertThat(attachment.filePath()).endsWith("7.ogg");
    }

    @Test
    void saveVideoNoteUsesMp4ContentType(@TempDir Path root) {
        var service = newService(root, GENEROUS, GENEROUS);

        var attachment = service.saveVideoNote(CHAT_ID, 8, new byte[]{9});

        assertThat(attachment).isNotNull();
        assertThat(attachment.contentType()).isEqualTo("video/mp4");
        assertThat(attachment.filePath()).endsWith("8.mp4");
    }

    @Test
    void returnsNullWhenGlobalLimitExceeded(@TempDir Path root) {
        var service = newService(root, GENEROUS, 3L);

        assertThat(service.saveVoice(CHAT_ID, 9, new byte[]{1, 2, 3, 4})).isNull();
    }

    @Test
    void returnsNullWhenDailyLimitExceeded(@TempDir Path root) {
        var service = newService(root, 3L, GENEROUS);

        assertThat(service.saveVoice(CHAT_ID, 10, new byte[]{1, 2, 3, 4})).isNull();
    }

    @Test
    void loadVoiceRoundTripsBytes(@TempDir Path root) {
        var service = newService(root, GENEROUS, GENEROUS);
        var data = new byte[]{5, 6, 7};
        var attachment = service.saveVoice(CHAT_ID, 11, data);

        assertThat(service.loadVoice(attachment.filePath())).containsExactly(data);
    }

    @Test
    void deleteFileRemovesIt(@TempDir Path root) throws Exception {
        var service = newService(root, GENEROUS, GENEROUS);
        var attachment = service.saveVoice(CHAT_ID, 12, new byte[]{1});
        assertThat(Files.exists(Path.of(attachment.filePath()))).isTrue();

        service.deleteFile(attachment.filePath());

        assertThat(Files.exists(Path.of(attachment.filePath()))).isFalse();
    }
}
