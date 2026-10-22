package com.offerpilot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class Sc01EvaluationOutputTest {
    @TempDir Path temp;

    @Test void requiresExternalAbsolutePathAndResolvesSymlinkedAncestors() throws Exception {
        Path repo = Files.createDirectory(temp.resolve("repo"));
        assertThatIllegalArgumentException().isThrownBy(() -> Sc01EvaluationOutput.prepare(null, repo));
        assertThatIllegalArgumentException().isThrownBy(() -> Sc01EvaluationOutput.prepare("relative", repo));
        assertThatIllegalArgumentException().isThrownBy(() -> Sc01EvaluationOutput.prepare(repo.resolve("out").toString(), repo));
        Path link = Files.createSymbolicLink(temp.resolve("alias"), repo);
        assertThatIllegalArgumentException().isThrownBy(() -> Sc01EvaluationOutput.prepare(link.resolve("nested/out").toString(), repo));
        assertThat(repo.resolve("nested")).doesNotExist();
        Path out = Sc01EvaluationOutput.prepare(temp.resolve("external/nested").toString(), repo);
        assertThat(out).isDirectory();
        assertThat(out.startsWith(repo)).isFalse();
        assertThat(Sc01EvaluationOutput.prepare(temp.resolve("external/nested").toString(), repo)).isNotEqualTo(out);
    }
}
