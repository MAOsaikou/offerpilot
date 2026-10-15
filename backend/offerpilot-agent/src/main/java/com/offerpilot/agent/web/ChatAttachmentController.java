package com.offerpilot.agent.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.conversation.application.ChatAttachments;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** AG-02: upload an image for the next chat turn, and read it back (owner only) for the message history. */
@RestController
@RequestMapping("/api/v1/agent/attachments")
public class ChatAttachmentController {
    private final ChatAttachments attachments;
    private final CurrentUser users;

    public ChatAttachmentController(ChatAttachments attachments, CurrentUser users) {
        this.attachments = attachments;
        this.users = users;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ChatAttachments.Uploaded> upload(@RequestParam MultipartFile file, HttpServletRequest request) throws IOException {
        String owner = users.require(request.getHeader("Authorization"));
        if (file.getSize() > ChatAttachments.MAX_BYTES)
            throw new com.offerpilot.common.error.BusinessException(com.offerpilot.common.error.ErrorCode.VALIDATION_FAILED, "图片不能超过 5MB");
        return ApiResponse.ok(attachments.upload(owner, file.getOriginalFilename(), file.getBytes()), TraceIdFilter.current(request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<byte[]> read(@PathVariable String id, HttpServletRequest request) {
        var image = attachments.read(users.require(request.getHeader("Authorization")), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mime()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }
}
