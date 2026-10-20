package com.offerpilot.knowledge.web;

import com.offerpilot.auth.application.CurrentUser;
import com.offerpilot.common.api.ApiResponse;
import com.offerpilot.common.web.TraceIdFilter;
import com.offerpilot.knowledge.application.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/knowledge/builtin")
public class BuiltinKnowledgeController {
    private final CurrentUser users;
    private final BuiltinKnowledgeRepository repository;
    private final BuiltinKnowledgeSearch search;
    public BuiltinKnowledgeController(CurrentUser users,BuiltinKnowledgeRepository repository,BuiltinKnowledgeSearch search) { this.users=users;this.repository=repository;this.search=search; }
    private void require(HttpServletRequest r) { users.require(r.getHeader("Authorization")); }
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public org.springframework.http.ResponseEntity<ApiResponse<Void>> invalidQuery(HttpServletRequest request) {
        require(request);
        return org.springframework.http.ResponseEntity.badRequest().body(ApiResponse.fail(com.offerpilot.common.error.ErrorCode.VALIDATION_FAILED.code(),"page/size/limit 必须为整数",TraceIdFilter.current(request)));
    }
    @GetMapping("/facets") public ApiResponse<BuiltinKnowledgeRepository.Facets> facets(HttpServletRequest r) {
        require(r);return ApiResponse.ok(repository.facets(),TraceIdFilter.current(r));
    }
    @GetMapping("/occupations") public ApiResponse<BuiltinKnowledgeRepository.Page<BuiltinKnowledgeRepository.Occupation>> occupations(
        @RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String family,@RequestParam(defaultValue="") String holland,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest r) {
        require(r);return ApiResponse.ok(repository.occupations(q,family,holland,page,size),TraceIdFilter.current(r));
    }
    @GetMapping("/occupations/{id}") public ApiResponse<BuiltinKnowledgeRepository.Occupation> occupation(@PathVariable String id,HttpServletRequest r) {
        require(r);return ApiResponse.ok(repository.occupation(id),TraceIdFilter.current(r));
    }
    @GetMapping("/entries") public ApiResponse<BuiltinKnowledgeRepository.Page<BuiltinKnowledgeRepository.Entry>> entries(
        @RequestParam(defaultValue="") String kind,@RequestParam(defaultValue="") String q,@RequestParam(defaultValue="") String industry,
        @RequestParam(defaultValue="") String role,@RequestParam(defaultValue="") String questionType,@RequestParam(defaultValue="") String difficulty,
        @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size,HttpServletRequest r) {
        require(r);return ApiResponse.ok(repository.entries(new BuiltinKnowledgeRepository.Filters(kind,q,industry,role,questionType,difficulty,page,size)),TraceIdFilter.current(r));
    }
    @GetMapping("/entries/{id}") public ApiResponse<BuiltinKnowledgeRepository.Entry> entry(@PathVariable String id,HttpServletRequest r) {
        require(r);return ApiResponse.ok(repository.entry(id),TraceIdFilter.current(r));
    }
    @GetMapping("/search") public ApiResponse<BuiltinKnowledgeSearch.Result> search(@RequestParam String q,
        @RequestParam(defaultValue="") String kind,@RequestParam(defaultValue="20") int limit,HttpServletRequest r) {
        require(r);return ApiResponse.ok(search.search(q,kind,limit),TraceIdFilter.current(r));
    }
}
