package com.chatsummary.bot.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Strips the video track out of Telegram video notes (кружочки) with ffmpeg, keeping only the
 * audio. Gemini otherwise tokenizes every sampled video frame (~258 tokens/frame at ~1 fps), which
 * dwarfs the ~32 tokens/sec cost of the audio we actually care about.
 *
 * <p>Fail-safe: if ffmpeg is missing, times out, or produces no output, an empty {@link Optional}
 * is returned and the caller keeps the original MP4.
 */
@Slf4j
@Service
public class AudioExtractionService {

    static final String AUDIO_CONTENT_TYPE = "audio/aac";
    static final String AUDIO_EXTENSION = "aac";

    private static final long TIMEOUT_SECONDS = 60;

    private final String ffmpegPath;

    public AudioExtractionService(@Value("${ffmpeg.path:ffmpeg}") String ffmpegPath) {
        this.ffmpegPath = ffmpegPath;
    }

    /**
     * Returns the AAC audio track extracted from the given MP4 video-note bytes, or empty if
     * extraction is unavailable or fails.
     */
    public Optional<byte[]> extractAudio(byte[] mp4Bytes) {
        if (mp4Bytes == null || mp4Bytes.length == 0) {
            return Optional.empty();
        }

        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("videonote-", ".mp4");
            output = Files.createTempFile("videonote-", "." + AUDIO_EXTENSION);
            Files.write(input, mp4Bytes);

            var process = new ProcessBuilder(
                    ffmpegPath, "-nostdin", "-y",
                    "-i", input.toString(),
                    "-vn", "-c:a", "copy",
                    output.toString())
                    .redirectErrorStream(true)
                    .start();

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                log.warn("ffmpeg timed out extracting audio from video note; keeping original MP4");
                return Optional.empty();
            }

            if (process.exitValue() != 0) {
                log.warn("ffmpeg exited with code {} extracting audio; keeping original MP4", process.exitValue());
                return Optional.empty();
            }

            byte[] audio = Files.readAllBytes(output);
            if (audio.length == 0) {
                log.warn("ffmpeg produced empty audio output; keeping original MP4");
                return Optional.empty();
            }

            log.info("Extracted audio from video note ({} -> {} bytes)", mp4Bytes.length, audio.length);
            return Optional.of(audio);
        } catch (IOException e) {
            log.warn("Failed to extract audio from video note ({}); keeping original MP4", e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while extracting audio from video note; keeping original MP4");
            return Optional.empty();
        } finally {
            deleteQuietly(input);
            deleteQuietly(output);
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to delete temp file {}: {}", path, e.getMessage());
        }
    }
}
