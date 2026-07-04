package com.chatsummary.bot.service;

import com.chatsummary.bot.model.ChatAttachment;
import com.chatsummary.bot.util.ImageDownscaler;
import java.io.IOException;
import org.bson.types.Binary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

@Service
public class TelegramDownloadService {

    private final OkHttpTelegramClient telegramClient;
    private final int imageMaxDimension;

    public TelegramDownloadService(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${image.max-dimension:1024}") int imageMaxDimension
    ) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
        this.imageMaxDimension = imageMaxDimension;
    }

    public ChatAttachment downloadPhoto(String fileId) {
        return download(fileId, null);
    }

    public byte[] downloadBytes(String fileId) {
        // Raw bytes; used for voice messages and video notes, which VoiceStorageService persists to disk.
        try {
            var file = telegramClient.execute(new GetFile(fileId));
            if (file.getFilePath() == null) {
                throw new IllegalStateException("Telegram didn't return file path");
            }
            try (var inputStream = telegramClient.downloadFileAsStream(file)) {
                return inputStream.readAllBytes();
            }
        } catch (TelegramApiException | IOException exception) {
            throw new IllegalStateException("Failed to download Telegram file", exception);
        }
    }

    private ChatAttachment download(String fileId, String contentType) {
        try {
            var file = telegramClient.execute(new GetFile(fileId));
            var filePath = file.getFilePath();
            if (filePath == null) {
                throw new IllegalStateException("Telegram didn't return file path");
            }

            try (var inputStream = telegramClient.downloadFileAsStream(file)) {
                var fileBytes = inputStream.readAllBytes();
                var resolvedType = contentType != null
                        ? contentType
                        : (filePath.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg");
                var downscaled = ImageDownscaler.downscale(fileBytes, resolvedType, imageMaxDimension);
                return new ChatAttachment(resolvedType, new Binary(downscaled));
            }
        } catch (TelegramApiException | IOException exception) {
            throw new IllegalStateException("Failed to download Telegram file", exception);
        }
    }
}
