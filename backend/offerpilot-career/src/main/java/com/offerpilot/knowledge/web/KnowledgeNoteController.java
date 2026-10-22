package com.offerpilot.knowledge.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.knowledge.application.KnowledgeNoteSearch;
import com.offerpilot.knowledge.application.KnowledgeNoteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge/notes")
public class KnowledgeNoteController {
    private final CurrentUser users;
    private final KnowledgeNoteService notes;
    private final KnowledgeNoteSearch search;
    private final com.offerpilot.knowledge.application.KnowledgeDocuments documents;
    public KnowledgeNoteController(CurrentUser users, KnowledgeNoteService notes, KnowledgeNoteSearch search,
            com.offerpilot.knowledge.application.KnowledgeDocuments documents) {
        this.users = users;
        this.notes = notes;
        this.search = search;
        this.documents = documents;
    }
    public record NoteRequest(
            @JsonDeserialize(using = StringOnly.class) @NotBlank @Pattern(regexp = "(?s).{1,120}") String title,
            @JsonDeserialize(using = StringOnly.class) @NotBlank @Pattern(regexp = "(?s).{1,30000}") String content,
            @JsonDeserialize(using = StringOnly.class) @NotNull @Pattern(regexp = "面试复盘|技术积累|项目经历|求职心得") String category) {
        KnowledgeNoteService.Draft draft() { return new KnowledgeNoteService.Draft(title, content, category); }
    }
    public record ImportNote(
            @JsonDeserialize(using = StringOnly.class) @NotBlank @Pattern(regexp = "(?s).{1,100}") String sourceId,
            @NotNull @Valid NoteRequest note) {}
    public record ImportRequest(@NotNull @Size(min = 1, max = 50) List<@NotNull @Valid ImportNote> notes) {}
    /** Match request hardening: reject scalar coercion; regex length counts Unicode code points. */
    public static class StringOnly extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) return (String) context.handleUnexpectedToken(String.class, parser);
            return parser.getText();
        }
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> invalidQuery(HttpServletRequest request) {
        return org.springframework.http.ResponseEntity.badRequest().body(ApiResponse.fail(
                com.offerpilot.common.error.ErrorCode.VALIDATION_FAILED.code(), "page/size/limit 必须为整数", TraceIdFilter.current(request)));
    }
    @GetMapping
    public ApiResponse<KnowledgeNoteService.Page> list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        return ApiResponse.ok(notes.list(owner, page, size), TraceIdFilter.current(request));
    }
    @GetMapping("/search")
    public ApiResponse<KnowledgeNoteSearch.Result> search(@RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "20") int limit, HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        return ApiResponse.ok(search.search(owner, q, limit), TraceIdFilter.current(request));
    }
    @GetMapping("/{id}")
    public ApiResponse<KnowledgeNoteService.Note> get(@PathVariable String id, HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        return ApiResponse.ok(notes.get(owner, id), TraceIdFilter.current(request));
    }
    @PostMapping
    public ApiResponse<KnowledgeNoteService.Note> create(@Valid @RequestBody NoteRequest body, HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        return ApiResponse.ok(notes.create(owner, body.draft()), TraceIdFilter.current(request));
    }
    @PutMapping("/{id}")
    public ApiResponse<KnowledgeNoteService.Note> update(@PathVariable String id, @Valid @RequestBody NoteRequest body,
            HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        return ApiResponse.ok(notes.update(owner, id, body.draft()), TraceIdFilter.current(request));
    }
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        notes.delete(owner, id);
        return ApiResponse.ok(null, TraceIdFilter.current(request));
    }
    @PostMapping(value = "/upload", consumes = "multipart/form-data")
    public ApiResponse<KnowledgeNoteService.Note> upload(@RequestPart("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String category, HttpServletRequest request) throws IOException {
        String owner = users.require(request.getHeader("Authorization"));
        if (file.getSize() > com.offerpilot.common.document.DocumentTextReader.MAX_BYTES)
            throw new com.offerpilot.common.error.BusinessException(com.offerpilot.common.error.ErrorCode.FILE_TOO_LARGE, "资料文件不能超过 10MB");
        return ApiResponse.ok(documents.upload(owner, file.getOriginalFilename(), file.getContentType(), file.getBytes(), category),
                TraceIdFilter.current(request));
    }
    @GetMapping("/{id}/file")
    public org.springframework.http.ResponseEntity<byte[]> original(@PathVariable String id, HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        var file = documents.read(owner, id);
        return org.springframework.http.ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(file.mime()))
                .header("Content-Disposition", org.springframework.http.ContentDisposition.attachment()
                        .filename(file.filename(), java.nio.charset.StandardCharsets.UTF_8).build().toString())
                .header("Cache-Control", "private, no-store")
                .body(file.bytes());
    }
    @PostMapping("/import")
    public ApiResponse<KnowledgeNoteService.ImportResult> importNotes(@Valid @RequestBody ImportRequest body,
            HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        var entries = body.notes().stream().map(n -> new KnowledgeNoteService.ImportEntry(n.sourceId(), n.note().draft())).toList();
        return ApiResponse.ok(notes.importNotes(owner, entries), TraceIdFilter.current(request));
    }
}
