package com.chatsummary.bot.service

import com.chatsummary.bot.model.ChatAttachment
import org.bson.types.Binary
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient
import org.telegram.telegrambots.meta.api.methods.GetFile
import java.io.InputStream

@Service
class TelegramDownloadService (
    @param:Value("\${telegram.bot.token}") private val botToken: String,
    ) {

    private val telegramClient = OkHttpTelegramClient(botToken)

    fun downloadPhoto(fileId: String) : ChatAttachment {

        val getFileMethod = GetFile(fileId)

        // 2. Выполняем запрос к API Telegram для получения метаданных файла (включая путь)

        val fileObj = telegramClient.execute(getFileMethod)
        val filePath = fileObj.filePath
            ?: throw IllegalStateException("Telegram не вернул путь к файлу")

        // 3. Используем встроенный в SDK метод для скачивания файла напрямую в InputStream
        val inputStream: InputStream = telegramClient.downloadFileAsStream(fileObj)

        // 4. Вычитываем поток в массив байт
        val fileBytes = inputStream.use { it.readBytes() }

        // Определяем contentType по расширению
        val contentType = if (filePath.endsWith(".png", true)) "image/png" else "image/jpeg"

        return ChatAttachment(
            contentType = contentType,
            data = Binary(fileBytes)
        )
    }

}
