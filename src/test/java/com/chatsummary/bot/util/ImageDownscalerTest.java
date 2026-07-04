package com.chatsummary.bot.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class ImageDownscalerTest {

    private static byte[] image(int width, int height, String format) throws IOException {
        var image = new BufferedImage(width, height,
                "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static int[] dimensions(byte[] bytes) throws IOException {
        var read = ImageIO.read(new ByteArrayInputStream(bytes));
        return new int[]{read.getWidth(), read.getHeight()};
    }

    @Test
    void capsLongestSidePreservingAspectRatio() throws IOException {
        byte[] source = image(2000, 1000, "jpg");

        byte[] result = ImageDownscaler.downscale(source, "image/jpeg", 1024);

        assertThat(dimensions(result)).containsExactly(1024, 512);
    }

    @Test
    void leavesSmallImagesUntouched() throws IOException {
        byte[] source = image(800, 600, "jpg");

        byte[] result = ImageDownscaler.downscale(source, "image/jpeg", 1024);

        assertThat(result).isSameAs(source);
    }

    @Test
    void preservesPngFormat() throws IOException {
        byte[] source = image(1500, 1500, "png");

        byte[] result = ImageDownscaler.downscale(source, "image/png", 512);

        assertThat(dimensions(result)).containsExactly(512, 512);
        assertThat(ImageIO.read(new ByteArrayInputStream(result))).isNotNull();
    }

    @Test
    void returnsOriginalWhenBytesAreNotAnImage() {
        byte[] garbage = "not an image".getBytes();

        byte[] result = ImageDownscaler.downscale(garbage, "image/jpeg", 1024);

        assertThat(result).isSameAs(garbage);
    }

    @Test
    void returnsOriginalForNullOrEmpty() {
        assertThat(ImageDownscaler.downscale(null, "image/jpeg", 1024)).isNull();
        byte[] empty = new byte[0];
        assertThat(ImageDownscaler.downscale(empty, "image/jpeg", 1024)).isSameAs(empty);
    }
}
