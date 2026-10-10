package com.offerpilot;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Shared paid-call counter for baseline, post-fix diagnostics and seven-case live smoke. */
final class DiagnosisLiveBudget {
    private DiagnosisLiveBudget() {}
    static void reserve() {
        String configured = System.getProperty("offerpilot.diag.budget-file");
        if (configured == null) return;
        Path path = Path.of(configured);
        if (!path.toAbsolutePath().toString().startsWith("/tmp/offerpilot-diag-salvage-")) throw new IllegalArgumentException("isolated budget path required");
        try (var channel = FileChannel.open(path,StandardOpenOption.CREATE,StandardOpenOption.READ,StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            var bytes = ByteBuffer.allocate(32); channel.read(bytes); bytes.flip();
            String text = StandardCharsets.UTF_8.decode(bytes).toString().strip();
            int used = text.isEmpty() ? 0 : Integer.parseInt(text);
            if (used >= 60) throw new IllegalStateException("60 paid calls exhausted");
            channel.truncate(0); channel.position(0);
            channel.write(StandardCharsets.UTF_8.encode(Integer.toString(used+1))); channel.force(true);
        } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
    }
}
