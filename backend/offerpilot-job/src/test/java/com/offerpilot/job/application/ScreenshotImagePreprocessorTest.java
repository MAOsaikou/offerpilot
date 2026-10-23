package com.offerpilot.job.application;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class ScreenshotImagePreprocessorTest {
    @ParameterizedTest
    @ValueSource(ints = {12000, 14000})
    void tallImagesKeepExactlyTheTop2400Pixels(int height) throws IOException {
        var source = new BufferedImage(32, height, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        source.setRGB(31, 2399, Color.BLUE.getRGB());
        source.setRGB(31, 2400, Color.GREEN.getRGB());
        var original = encode(source, "png");

        var prepared = ScreenshotImagePreprocessor.prepare(original);
        var result = decode(prepared);

        assertThat(result.getWidth()).isEqualTo(32);
        assertThat(result.getHeight()).isEqualTo(2400);
        assertThat(result.getRGB(0, 0)).isEqualTo(Color.RED.getRGB());
        assertThat(result.getRGB(31, 2399)).isEqualTo(Color.BLUE.getRGB());
        assertThat(decode(original).getHeight()).isEqualTo(height);
    }

    @ParameterizedTest
    @ValueSource(ints = {800, 2399, 2400})
    void normalAndBoundaryImagesKeepOriginalBytesEvenWhenWide(int height) throws IOException {
        var original = encode(new BufferedImage(1500, height, BufferedImage.TYPE_INT_RGB), "png");

        assertThat(ScreenshotImagePreprocessor.prepare(original)).isSameAs(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg"})
    void onePixelAboveHeightThresholdIsCroppedWithMatchingPngMime(String format) throws IOException {
        var original = encode(new BufferedImage(32, 2401, BufferedImage.TYPE_INT_RGB), format);

        var prepared = ScreenshotImagePreprocessor.prepare(original);

        assertThat(prepared.mime()).isEqualTo("image/png");
        assertThat(JobScreenshotService.mime("prepared.png", prepared.bytes())).isEqualTo("image/png");
        assertThat(decode(prepared).getHeight()).isEqualTo(2400);
        assertThat(decode(prepared).getWidth()).isEqualTo(32);
    }

    @ParameterizedTest
    @ValueSource(ints = {1400, 1401, 2800})
    void onlyTallImagesShrinkToWidthLimitAfterCropping(int width) throws IOException {
        var source = new BufferedImage(width, 2401, BufferedImage.TYPE_INT_RGB);
        var graphics = source.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, width, 2400);
            graphics.setColor(Color.RED);
            graphics.fillRect(0, 2400, width, 1);
        } finally {
            graphics.dispose();
        }

        var result = decode(ScreenshotImagePreprocessor.prepare(encode(source, "png")));

        int expectedWidth = Math.min(width, 1400);
        assertThat(result.getWidth()).isEqualTo(expectedWidth);
        assertThat(result.getHeight()).isEqualTo((int) Math.round(2400.0 * expectedWidth / width));
        assertThat(result.getRGB(expectedWidth - 1, result.getHeight() - 1)).isEqualTo(Color.BLUE.getRGB());
    }

    @Test
    void missingDecoderAndUnreadableImagePreserveExistingVisionPath() {
        var unsupported = new JobScreenshotService.Image(new byte[]{1}, "image/webp");
        var truncated = new JobScreenshotService.Image(new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}, "image/png");

        assertThat(ScreenshotImagePreprocessor.prepare(unsupported)).isSameAs(unsupported);
        assertThat(ScreenshotImagePreprocessor.prepare(truncated)).isSameAs(truncated);
    }

    static JobScreenshotService.Image encode(BufferedImage image, String format) throws IOException {
        var bytes = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, bytes)).isTrue();
        return new JobScreenshotService.Image(bytes.toByteArray(), format.equals("jpg") ? "image/jpeg" : "image/png");
    }

    static BufferedImage decode(JobScreenshotService.Image image) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(image.bytes()));
    }
}
