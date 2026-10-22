package com.offerpilot.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.artifact.BackgroundWork;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.resume.application.*;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GenerationWorkTest {
    @Test void diagnosisUsesExistingApplicationLifecycleAndPublishesResultReference() throws Exception {
        var diagnoses=mock(ResumeDiagnosisService.class);
        var json=new ObjectMapper();
        var worker=new GenerationWork(diagnoses,null,null,null,null,null,null,null,null,json,null);
        var input=new GenerationTaskService.Start("RESUME_DIAGNOSIS","resume", "request-fixture-0001",json.readTree("{\"versionId\":\"version\"}"));
        var prepared=new ResumeDiagnosisService.Work("diagnosis","owner","resume",null,null,null);
        when(diagnoses.begin("owner","resume","version","request-fixture-0001")).thenReturn(prepared);
        when(diagnoses.execute(prepared)).thenReturn(Mono.just(json.readTree("{\"reportId\":\"diagnosis\"}")));
        var references=new java.util.ArrayList<String>();
        assertThat(worker.execute("owner",input,references::add).path("reportId").asText()).isEqualTo("diagnosis");
        assertThat(references).containsExactly("diagnosis");
    }
    @Test void screenshotImportUsesOwnedFilesAndRejectsMixedOrMalformedSources() throws Exception {
        var imports=mock(com.offerpilot.job.application.JobImportService.class);
        var screenshots=mock(com.offerpilot.job.application.JobScreenshotService.class);
        var json=new ObjectMapper();
        var worker=new GenerationWork(null,null,null,null,null,null,imports,null,null,json,screenshots);
        var input=new GenerationTaskService.Start("JOB_IMPORT","current","request-fixture-0002",json.readTree("{\"fileIds\":[\"file_1\",\"file_2\"]}"));
        worker.validate("owner",input);
        verify(screenshots).validate("owner",java.util.List.of("file_1","file_2"));
        worker.execute("owner",input,id -> {});
        verify(screenshots).parse("owner",java.util.List.of("file_1","file_2"));
        verifyNoInteractions(imports);
        for (String body:java.util.List.of("{\"fileIds\":[]}","{\"fileIds\":[1]}","{\"fileIds\":[\"file_1\"],\"text\":\"text\"}")) {
            var invalid=new GenerationTaskService.Start("JOB_IMPORT","current","request-fixture-0003",json.readTree(body));
            assertThatThrownBy(() -> worker.validate("owner",invalid)).isInstanceOf(BusinessException.class);
        }
    }
    @Test void cooperativeCancellationStopsLateWritesAndAlwaysClearsThreadScope() {
        assertThatThrownBy(() -> BackgroundWork.run(() -> false, () -> "late result")).isInstanceOf(BusinessException.class);
        assertThatCode(BackgroundWork::check).doesNotThrowAnyException();
        assertThat(BackgroundWork.run(() -> true, () -> "saved")).isEqualTo("saved");
    }
}
