package com.offerpilot.resume.application;

import com.offerpilot.common.error.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ResumeParseWorker {
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
            service.complete(job, extractor.extract(document), document);
        } catch (BusinessException ex) { service.fail(job, ex.getMessage()); }
        catch (Exception ex) { service.fail(job, "解析暂未完成，请检查模型配置与网络后重试。原文件已保存。"); }
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
