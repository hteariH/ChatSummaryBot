package com.chatsummary.bot.service;

import com.chatsummary.bot.model.ChatAttachment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VoiceStorageService {

    private static final long DAILY_LIMIT_BYTES = 500 * 1024 * 1024; // 500 MB
    private final Path storageBase;

    public VoiceStorageService(@Value("${storage.path:storage}") String storagePath) {
        this.storageBase = Paths.get(storagePath).toAbsolutePath();
    }

    public String saveVoice(long chatId, int messageId, byte[] data) {
        if (isLimitExceeded(chatId, data.length)) {
            log.warn("Voice message limit exceeded for chat {}. Skipping storage.", chatId);
            return null;
        }

        Path voicePath = storageBase.resolve("voices")
                .resolve(String.valueOf(chatId))
                .resolve(String.valueOf(messageId))
                .resolve("voice.ogg");

        try {
            Files.createDirectories(voicePath.getParent());
            Files.write(voicePath, data);
            log.info("Saved voice message to {}", voicePath);
            return voicePath.toString();
        } catch (IOException e) {
            log.error("Failed to save voice message for chat {} message {}", chatId, messageId, e);
            return null;
        }
    }

    public byte[] loadVoice(String filePath) {
        try {
            return Files.readAllBytes(Paths.get(filePath));
        } catch (IOException e) {
            log.error("Failed to read voice file: {}", filePath, e);
            return null;
        }
    }

    public void deleteChatVoice(long chatId, int messageId) {
        Path messageDir = storageBase.resolve("voices")
                .resolve(String.valueOf(chatId))
                .resolve(String.valueOf(messageId));
        deleteDirectory(messageDir);
    }

    public void deleteOldVoices(long chatId) {
        // This might be called when all messages for a chat are cleared
        Path chatDir = storageBase.resolve("voices").resolve(String.valueOf(chatId));
        deleteDirectory(chatDir);
    }

    private boolean isLimitExceeded(long chatId, int newFileSize) {
        Path chatDir = storageBase.resolve("voices").resolve(String.valueOf(chatId));
        if (!Files.exists(chatDir)) {
            return false;
        }

        try (Stream<Path> walk = Files.walk(chatDir)) {
            long currentSize = walk
                    .filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .sum();
            return (currentSize + newFileSize) > DAILY_LIMIT_BYTES;
        } catch (IOException e) {
            log.error("Failed to calculate directory size for chat {}", chatId, e);
            return false;
        }
    }

    private void deleteDirectory(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder())
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        log.warn("Failed to delete path: {}", p, e);
                    }
                });
        } catch (IOException e) {
            log.error("Failed to delete directory: {}", path, e);
        }
    }
}
