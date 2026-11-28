package com.offerpilot.knowledge.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.resume.application.ResumeService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeNotesForChatTest {
    private final KnowledgeNoteSearch search = mock(KnowledgeNoteSearch.class);
    private final ResumeService resumes = mock(ResumeService.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test void currentResumeOutranksADifferentPersonaNoteAndDoesNotInventAScore() throws Exception {
        var structured = json.readTree("""
                {"basics":{"name":"林晓舟","title":"Java后端实习","phone":"","email":"","city":"","links":[]},
                 "projects":[{"name":"校园二手交易平台","role":"","start":"","end":"","tech":["Java"],"bullets":["完成后端"]}],
                 "skills":[],"education":[],"experience":[],"certificates":[],"summary":"Java 后端实习"}
                """);
        var version = new ResumeService.Version("rv", 1, null, "UPLOAD", structured,
                "林晓舟 Java 后端实习 校园二手交易平台", json.createObjectNode(), Instant.EPOCH);
        var resume = new ResumeService.Resume("rs_current", "demo-resume", "READY", 100, null, "rv",
                "demo-resume.pdf", Instant.EPOCH, Instant.EPOCH, version);
        when(resumes.latestReady("alice")).thenReturn(resume);
        when(search.search(org.mockito.ArgumentMatchers.eq("alice"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn(new KnowledgeNoteSearch.Result(List.of(
                        hit("note-library", "林同学项目", "校园借阅系统，星河大学"),
                        hit("note-same", "补充记录", "校园二手交易平台的接口笔记")), false));
        var notes = new KnowledgeNotesForChat(search);
        notes.setResumes(resumes);
        var cited = notes.retrieve("alice", "结合已保存的 Java 后端实习指出最该补的证据");
        assertThat(cited).isNotEmpty();
        assertThat(cited.get(0).title()).startsWith("当前简历");
        assertThat(cited.get(0).title()).contains("林晓舟");
        assertThat(cited.get(0).category()).isEqualTo("CURRENT_RESUME");
        assertThat(cited.get(0).score()).isNull();
        assertThat(cited).extracting(KnowledgeNotesForChat.Citation::title).doesNotContain("林同学项目");
        assertThat(cited).extracting(KnowledgeNotesForChat.Citation::title).contains("补充记录");
    }

    @Test void unrelatedQueryKeepsNoteRankingWhenTheResumeDoesNotMatch() throws Exception {
        var structured = json.readTree("""
                {"basics":{"name":"林晓舟"},"projects":[{"name":"校园二手交易平台"}],"summary":"Java"}
                """);
        var version = new ResumeService.Version("rv", 1, null, "UPLOAD", structured,
                "林晓舟 Java 校园二手交易平台", json.createObjectNode(), Instant.EPOCH);
        when(resumes.latestReady("alice")).thenReturn(new ResumeService.Resume("rs_current", "demo-resume", "READY",
                100, null, "rv", "demo-resume.pdf", Instant.EPOCH, Instant.EPOCH, version));
        when(search.search(org.mockito.ArgumentMatchers.eq("alice"), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(3)))
                .thenReturn(new KnowledgeNoteSearch.Result(List.of(hit("note-library", "林同学项目", "校园借阅")), false));
        var notes = new KnowledgeNotesForChat(search);
        notes.setResumes(resumes);
        assertThat(notes.retrieve("alice", "烹饪课表")).extracting(KnowledgeNotesForChat.Citation::title)
                .containsExactly("林同学项目");
    }

    private static KnowledgeNoteSearch.Hit hit(String id, String title, String content) {
        return new KnowledgeNoteSearch.Hit(id, title, "项目经历", content, "2026-11-27T00:00:00Z",
                0.016, true, false, "READY", null, null, false);
    }
}
