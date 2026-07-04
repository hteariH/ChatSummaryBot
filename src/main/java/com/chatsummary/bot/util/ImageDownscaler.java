package com.chatsummary.bot.util;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import lombok.extern.slf4j.Slf4j;

/**
 * Downscales photos before they are stored / sent to Gemini, to keep multimodal input token usage
 * bounded. Larger images are tiled by Gemini (each tile costs tokens), so capping the longest side
 * meaningfully reduces cost for busy chats.
 *
 * <p>Fail-safe by design: if the bytes cannot be decoded or re-encoded (unknown format, corrupt
 * data, animated content, etc.) the original bytes are returned unchanged.
 */
@Slf4j
public final class ImageDownscaler {

    private static final float JPEG_QUALITY = 0.85f;

    private ImageDownscaler() {
    }

    /**
     * Returns a downscaled copy of {@code imageBytes} whose longest side is at most
     * {@code maxDimension}px. If the image already fits, is undecodable, or re-encoding fails, the
     * original bytes are returned.
     *
     * @param contentType MIME type of the image (e.g. {@code image/png}); decides output encoding.
     */
    public static byte[] downscale(byte[] imageBytes, String contentType, int maxDimension) {
        if (imageBytes == null || imageBytes.length == 0 || maxDimension <= 0) {
            return imageBytes;
        }

        try {
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (original == null) {
                return imageBytes;
            }

            int width = original.getWidth();
            int height = original.getHeight();
            int longest = Math.max(width, height);
            if (longest <= maxDimension) {
                return imageBytes;
            }

            double scale = (double) maxDimension / longest;
            int targetWidth = Math.max(1, (int) Math.round(width * scale));
            int targetHeight = Math.max(1, (int) Math.round(height * scale));

            boolean png = contentType != null && contentType.toLowerCase().contains("png");
            int imageType = png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, imageType);

            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.drawImage(original, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }

            byte[] encoded = encode(scaled, png);
            if (encoded == null || encoded.length == 0) {
                return imageBytes;
            }

            log.info("Downscaled image {}x{} -> {}x{} ({} -> {} bytes)",
                    width, height, targetWidth, targetHeight, imageBytes.length, encoded.length);
            return encoded;
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to downscale image, keeping original ({} bytes): {}",
                    imageBytes.length, e.getMessage());
            return imageBytes;
        }
    }

    private static byte[] encode(BufferedImage image, boolean png) throws IOException {
        if (png) {
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) {
                return null;
            }
            return output.toByteArray();
        }

        var writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return null;
        }
        ImageWriter writer = writers.next();
        var output = new ByteArrayOutputStream();
        try (var imageOutput = new MemoryCacheImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }
}