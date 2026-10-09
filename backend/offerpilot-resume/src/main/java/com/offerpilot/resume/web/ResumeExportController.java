package com.offerpilot.resume.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.resume.application.ResumePdfExporter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/v1/resumes")
public class ResumeExportController {
    private final CurrentUser users;
    private final ResumePdfExporter exporter;

    public ResumeExportController(CurrentUser users, ResumePdfExporter exporter) {
        this.users = users;
        this.exporter = exporter;
    }

    @GetMapping("/{id}/export.pdf")
    public ResponseEntity<byte[]> export(@PathVariable String id,
                                        @RequestParam(defaultValue = "classic") String template,
                                        HttpServletRequest request) {
        String owner = users.require(request.getHeader("Authorization"));
        var result = exporter.export(owner, id, template);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(result.filename(), StandardCharsets.UTF_8).build().toString())
            .cacheControl(CacheControl.noStore()).body(result.bytes());
    }
}
