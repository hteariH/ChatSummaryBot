package com.chatsummary.bot.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AudioExtractionServiceTest {

    @Test
    void returnsEmptyForNullOrEmptyInput() {
        var service = new AudioExtractionService("ffmpeg");

        assertThat(service.extractAudio(null)).isEmpty();
        assertThat(service.extractAudio(new byte[0])).isEmpty();
    }

    @Test
    void returnsEmptyWhenFfmpegBinaryIsMissing() {
        var service = new AudioExtractionService("nonexistent-ffmpeg-binary-xyz");

        assertThat(service.extractAudio(new byte[]{1, 2, 3, 4})).isEmpty();
    }
}
