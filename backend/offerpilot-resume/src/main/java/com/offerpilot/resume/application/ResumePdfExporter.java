package com.offerpilot.resume.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.springframework.stereotype.Service;

@Service
public class ResumePdfExporter {
    private final ResumeService resumes;
    private final ResumePdfRenderer renderer;
    public record Export(String filename, byte[] bytes) {}

    public ResumePdfExporter(ResumeService resumes, ResumePdfRenderer renderer) {
        this.resumes = resumes;
        this.renderer = renderer;
    }

    public Export export(String owner, String id, String template) {
        var resume = resumes.get(owner, id);
        ResumePdfRenderer.validateTemplate(template);
        if (!"READY".equals(resume.parseStatus()) || resume.currentVersion() == null)
            throw new BusinessException(ErrorCode.RESUME_BUSY, "简历尚无可导出的已保存版本，请等待解析完成后重试");
        String filename = resume.title().replaceAll("[\\p{Cntrl}<>:\"/\\\\|?*]", "_").strip();
        filename = filename.replaceAll("^[. ]+|[. ]+$", "");
        if (filename.isBlank()) filename = "简历";
        return new Export(filename + ".pdf", renderer.render(resume.title(), resume.currentVersion().structured(), template));
    }
}
