package com.offerpilot.knowledge.web;

import com.offerpilot.auth.application.PasswordLoginService;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.document.DocumentTextReader;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.knowledge.application.StaffKnowledgeDocuments;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/v1/staff/knowledge/documents")
public class StaffKnowledgeController {
    private final PasswordLoginService staff;
    private final StaffKnowledgeDocuments documents;
    public StaffKnowledgeController(PasswordLoginService staff, StaffKnowledgeDocuments documents) {
        this.staff = staff; this.documents = documents;
    }
    private String requireStaff(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return staff.requireStaffId(request.getHeader("Authorization"));
    }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> invalidQuery(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return org.springframework.http.ResponseEntity.badRequest().body(ApiResponse.fail(
                ErrorCode.VALIDATION_FAILED.code(),"page/size 必须为正整数",TraceIdFilter.current(request)));
    }
    @GetMapping
    public ApiResponse<StaffKnowledgeDocuments.Page> list(@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size, HttpServletRequest request, HttpServletResponse response) {
        requireStaff(request,response);
        return ApiResponse.ok(documents.list(page,size),TraceIdFilter.current(request));
    }
    @GetMapping("/{id}")
    public ApiResponse<StaffKnowledgeDocuments.Document> get(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        requireStaff(request,response);
        return ApiResponse.ok(documents.get(id),TraceIdFilter.current(request));
    }
    @PostMapping(value="/upload",consumes="multipart/form-data")
    public ApiResponse<StaffKnowledgeDocuments.Document> upload(@RequestPart("file") MultipartFile file,
            HttpServletRequest request, HttpServletResponse response) throws IOException {
        String actor = requireStaff(request,response);
        if (file.getSize() > DocumentTextReader.MAX_BYTES)
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE,"资料文件不能超过 10MB");
        return ApiResponse.ok(documents.upload(actor,file.getOriginalFilename(),file.getContentType(),file.getBytes()),TraceIdFilter.current(request));
    }
    @PostMapping("/{id}/reindex")
    public ApiResponse<StaffKnowledgeDocuments.Document> reindex(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        requireStaff(request,response);
        return ApiResponse.ok(documents.reindex(id),TraceIdFilter.current(request));
    }
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id, HttpServletRequest request, HttpServletResponse response) {
        requireStaff(request,response);
        documents.delete(id);
        return ApiResponse.ok(null,TraceIdFilter.current(request));
    }
}
