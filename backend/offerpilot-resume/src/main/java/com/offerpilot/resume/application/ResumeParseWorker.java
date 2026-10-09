package com.offerpilot.resume.application;

import com.offerpilot.common.error.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResumeParseWorker {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResumeParseWorker.class);
    private final ResumeService service;
    private final ResumeObjectStore storage;
    private final ResumeDocumentReader reader;
    private final ResumeExtractor extractor;
    public ResumeParseWorker(ResumeService service, ResumeObjectStore storage, ResumeDocumentReader reader, ResumeExtractor extractor) {
        this.service = service; this.storage = storage; this.reader = reader; this.extractor = extractor;
    }
    // Public for deterministic integration testing without a scheduler or real provider.
    public void processNext() {
        var job = service.claim();
        if (job == null) return;
        try {
            var document = reader.read(storage.get(job.bucket(), job.key()), job.mime());
            service.progress(job);
            var result = com.offerpilot.llm.quota.LlmUserContext.asUser(job.owner(),
                    () -> extractor.extractResult(document, job.textTier(), job.visionTier()));
            service.complete(job, result.structured(), result.metadata());
        } catch (BusinessException ex) {
            log.warn("简历解析失败 resumeId={} code={}", job.id(), ex.errorCode().code());
            service.fail(job, ex.getMessage());
        } catch (Exception ex) {
            // 不记录供应商响应正文、简历内容或凭据。
            log.warn("简历解析失败 resumeId={} type={}", job.id(), ex.getClass().getSimpleName());
            service.fail(job, "解析服务暂时异常，请稍后重试。原文件已保存。");
        }
    }
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name="offerpilot.resume.worker-enabled", havingValue="true", matchIfMissing=true)
    static class Scheduling {
        private final ResumeParseWorker worker;
        Scheduling(ResumeParseWorker worker) { this.worker = worker; }
        @Scheduled(fixedDelayString="${offerpilot.resume.poll-delay:2000}")
        public void poll() { worker.processNext(); }
    }
}
