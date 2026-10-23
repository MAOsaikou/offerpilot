package com.offerpilot.job.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class ScreenshotImagePreprocessor {
    static final int MAX_HEIGHT = 2400;
    static final int MAX_WIDTH = 1400;
    private static final Logger log = LoggerFactory.getLogger(ScreenshotImagePreprocessor.class);

    private ScreenshotImagePreprocessor() {}

    static JobScreenshotService.Image prepare(JobScreenshotService.Image original) {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(original.bytes()))) {
            var readers = ImageIO.getImageReaders(input);
            // Keep formats without a local decoder (e.g. WebP on a stock JDK) on the existing vision path.
            if (!readers.hasNext()) return original;
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (height <= MAX_HEIGHT) return original;

                // Long recruitment pages time out in vision; retain the top where the main posting usually sits.
                var params = reader.getDefaultReadParam();
                params.setSourceRegion(new Rectangle(0, 0, width, MAX_HEIGHT));
                var cropped = reader.read(0, params);
                try {
                    int targetWidth = Math.min(width, MAX_WIDTH);
                    int targetHeight = Math.max(1, (int) Math.round((double) MAX_HEIGHT * targetWidth / width));
                    var output = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB);
                    try {
                        var graphics = output.createGraphics();
                        try {
                            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                            graphics.drawImage(cropped, 0, 0, targetWidth, targetHeight, null);
                        } finally {
                            graphics.dispose();
                        }
                        var bytes = new ByteArrayOutputStream();
                        if (!ImageIO.write(output, "png", bytes)) throw new IOException("PNG encoder unavailable");
                        log.info("Screenshot vision preprocessing: {}x{} -> {}x{}", width, height, targetWidth, targetHeight);
                        return new JobScreenshotService.Image(bytes.toByteArray(), "image/png");
                    } finally {
                        output.flush();
                    }
                } finally {
                    cropped.flush();
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException ex) {
            // Preserve the existing model/error handling for images that cannot be processed locally.
            log.warn("Screenshot vision preprocessing could not decode or encode image; sending original");
            return original;
        }
    }
}
