package com.offerpilot.resume.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ResumeTitlesTest {
    @Test void filenameTitlesTakeTheParsedNameAndObjective() throws Exception {
        var structured = new ObjectMapper().readTree("""
            {"basics":{"name":"林晓舟","title":"Java 后端开发（实习）"}}
            """);
        assertThat(ResumeTitles.suggest("demo-resume.pdf", "demo-resume", structured))
                .isEqualTo("林晓舟 · Java 后端开发（实习）");
        assertThat(ResumeTitles.suggest("demo-resume.pdf", "投递用简历", structured)).isNull();
        assertThat(ResumeTitles.suggest("demo-resume.pdf", "demo-resume", new ObjectMapper().readTree("{\"basics\":{}}"))).isNull();
    }
}
