package com.offerpilot.resume.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.resume.application.ResumeService;
import com.offerpilot.resume.application.ResumeDocumentReader;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/resumes")
public class ResumeController {
    private final ResumeService resumes;
    private final CurrentUser users;

    public ResumeController(ResumeService resumes, CurrentUser users) {
        this.resumes = resumes;
        this.users = users;
    }

    private String owner(HttpServletRequest r) {
        return users.require(r.getHeader("Authorization"));
    }

    private <T> ApiResponse<T> ok(T value, HttpServletRequest r) {
        return ApiResponse.ok(value, TraceIdFilter.current(r));
    }

    public record Rename(@NotBlank @Size(max = 100) String title) {}

    @PostMapping("/privacy-check")
    public ApiResponse<?> privacyCheck(@RequestParam MultipartFile file, HttpServletRequest request) throws IOException {
        String owner = users.require(request.getHeader("Authorization"));
        return ok(resumes.privacyCheck(owner, file.getOriginalFilename(), file.getBytes()), request);
    }
    @PostMapping(consumes = "multipart/form-data")
    public ApiResponse<?> upload(@RequestParam MultipartFile file, @RequestParam(required = false) String title,
                                HttpServletRequest r) throws IOException {
        String user = owner(r);
        if (file.getSize() > ResumeDocumentReader.MAX_BYTES)
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        return ok(resumes.upload(user, file.getOriginalFilename(), title, file.getBytes()), r);
    }

    @GetMapping
    public ApiResponse<?> list(@RequestParam(defaultValue = "1") int page,
                              @RequestParam(defaultValue = "20") int size, HttpServletRequest r) {
        return ok(resumes.list(owner(r), page, size), r);
    }

    @GetMapping("/{id}")
    public ApiResponse<?> get(@PathVariable String id, HttpServletRequest r) {
        return ok(resumes.get(owner(r), id), r);
    }

    @GetMapping("/{id}/parse-status")
    public ApiResponse<?> status(@PathVariable String id, HttpServletRequest r) {
        var resume = resumes.get(owner(r), id);
        return ok(new Status(resume.parseStatus(), resume.parseProgress(), resume.parseError()), r);
    }

    public record Status(String status, int progress, String error) {}

    @PostMapping("/{id}/retry")
    public ApiResponse<?> retry(@PathVariable String id, HttpServletRequest r) {
        return ok(resumes.retry(owner(r), id), r);
    }

    @PatchMapping("/{id}")
    public ApiResponse<?> rename(@PathVariable String id, @Valid @RequestBody Rename body, HttpServletRequest r) {
        return ok(resumes.rename(owner(r), id, body.title()), r);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<?> delete(@PathVariable String id, HttpServletRequest r) {
        resumes.delete(owner(r), id);
        return ok(java.util.Map.of("deleted", true), r);
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<?> versions(@PathVariable String id, HttpServletRequest r) {
        return ok(resumes.versions(owner(r), id), r);
    }

    @GetMapping("/{id}/versions/{vid}")
    public ApiResponse<?> version(@PathVariable String id, @PathVariable String vid, HttpServletRequest r) {
        return ok(resumes.version(owner(r), id, vid), r);
    }

    public record RestoreRequest(String baseVersionId) {}

    /** Rolls back to version {vid} by creating a new current version with its content. */
    @PostMapping("/{id}/versions/{vid}/restore")
    public ApiResponse<?> restore(@PathVariable String id, @PathVariable String vid, @RequestBody RestoreRequest body, HttpServletRequest r) {
        return ok(resumes.restore(owner(r), id, body == null ? null : body.baseVersionId(), vid), r);
    }

    @PutMapping("/{id}/versions/{vid}/structured")
    public ApiResponse<?> save(@PathVariable String id, @PathVariable String vid, @RequestBody JsonNode structured,
                              HttpServletRequest r) {
        return ok(resumes.save(owner(r), id, vid, structured), r);
    }
}
