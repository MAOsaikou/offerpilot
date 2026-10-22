package com.offerpilot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Validate before any paid call; resolve ancestor symlinks before allowing an external output directory. */
final class Sc01EvaluationOutput {
    static Path prepare(String configured, Path repository) throws IOException {
        if (configured == null || configured.isBlank())
            throw new IllegalArgumentException("Set -Dofferpilot.sc01.output-dir to an absolute directory outside the repository");
        Path requested = Path.of(configured);
        if (!requested.isAbsolute()) throw new IllegalArgumentException("SC-01 output directory must be absolute");
        requested = requested.normalize();
        Path ancestor = requested;
        while (!Files.exists(ancestor)) ancestor = ancestor.getParent();
        Path resolved = ancestor.toRealPath().resolve(ancestor.relativize(requested)).normalize();
        if (resolved.startsWith(repository.toRealPath()))
            throw new IllegalArgumentException("SC-01 live output must be outside the repository");
        Files.createDirectories(resolved);
        return Files.createTempDirectory(resolved, "sc01-live-");
    }

    static Path repository() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.exists(path.resolve(".git"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Cannot locate repository root for output validation");
        return path;
    }
}
