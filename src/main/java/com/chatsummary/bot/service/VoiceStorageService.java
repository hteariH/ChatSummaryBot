package com.chatsummary.bot.service;

import com.chatsummary.bot.model.ChatAttachment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class VoiceStorageService {

    private final Path storageRoot;
    private final long dailyLimit;
    private final long globalLimit;

    public VoiceStorageService(
            @Value("${storage.path}") String storagePath,
            @Value("${storage.limits.daily-per-chat}") long dailyLimit,
            @Value("${storage.limits.global}") long globalLimit
    ) {
        this.storageRoot = Paths.get(storagePath, "voices");
        this.dailyLimit = dailyLimit;
        this.globalLimit = globalLimit;
    }

    public ChatAttachment saveVoice(long chatId, int messageId, byte[] data) {
        if (getGlobalSize() + data.length > globalLimit) {
            log.warn("Global storage limit reached. Cannot save voice message.");
            return null;
        }

        if (getDailyChatSize(chatId) + data.length > dailyLimit) {
            log.warn("Daily storage limit for chat {} reached. Cannot save voice message.", chatId);
            return null;
        }

        Path chatDir = storageRoot.resolve(String.valueOf(chatId)).resolve(LocalDate.now().toString());
        try {
            Files.createDirectories(chatDir);
            Path filePath = chatDir.resolve(messageId + ".ogg");
            Files.write(filePath, data);

            log.info("Saved voice message to {}", filePath);
            return new ChatAttachment("audio/ogg", null, filePath.toString(), (long) data.length);
        } catch (IOException e) {
            log.error("Failed to save voice message to disk", e);
            return null;
        }
    }

    public byte[] loadVoice(String filePath) {
        try {
            return Files.readAllBytes(Paths.get(filePath));
        } catch (IOException e) {
            log.error("Failed to read voice message from {}", filePath, e);
            return null;
        }
    }

    public void deleteFile(String filePath) {
        if (filePath == null) return;
        try {
            Files.deleteIfExists(Paths.get(filePath));
            log.info("Deleted voice message: {}", filePath);
        } catch (IOException e) {
            log.error("Failed to delete voice message: {}", filePath, e);
        }
    }

    private long getDailyChatSize(long chatId) {
        Path chatDir = storageRoot.resolve(String.valueOf(chatId)).resolve(LocalDate.now().toString());
        if (!Files.exists(chatDir)) return 0;
        try (Stream<Path> walk = Files.walk(chatDir)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> p.toFile().length())
                    .sum();
        } catch (IOException e) {
            log.error("Error calculating daily chat size", e);
            return 0;
        }
    }

    private long getGlobalSize() {
        if (!Files.exists(storageRoot)) return 0;
        try (Stream<Path> walk = Files.walk(storageRoot)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> p.toFile().length())
                    .sum();
        } catch (IOException e) {
            log.error("Error calculating global storage size", e);
            return 0;
        }
    }
}
