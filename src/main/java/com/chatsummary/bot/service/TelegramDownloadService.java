package com.chatsummary.bot.service;

import com.chatsummary.bot.model.ChatAttachment;
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

    public TelegramDownloadService(@Value("${telegram.bot.token}") String botToken) {
        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    public ChatAttachment downloadPhoto(String fileId) {
        try {
            var file = telegramClient.execute(new GetFile(fileId));
            var filePath = file.getFilePath();
            if (filePath == null) {
                throw new IllegalStateException("Telegram не вернул путь к файлу");
            }

            try (var inputStream = telegramClient.downloadFileAsStream(file)) {
                var fileBytes = inputStream.readAllBytes();
                var contentType = filePath.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
                return new ChatAttachment(contentType, new Binary(fileBytes));
            }
        } catch (TelegramApiException | IOException exception) {
            throw new IllegalStateException("Failed to download Telegram photo", exception);
        }
    }
}
