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
        var worker=new GenerationWork(diagnoses,null,null,null,null,null,null,null,null,json);
        var input=new GenerationTaskService.Start("RESUME_DIAGNOSIS","resume", "request-fixture-0001",json.readTree("{\"versionId\":\"version\"}"));
        var prepared=new ResumeDiagnosisService.Work("diagnosis","owner","resume",null,null,null);
        when(diagnoses.begin("owner","resume","version","request-fixture-0001")).thenReturn(prepared);
        when(diagnoses.execute(prepared)).thenReturn(Mono.just(json.readTree("{\"reportId\":\"diagnosis\"}")));
        var references=new java.util.ArrayList<String>();
        assertThat(worker.execute("owner",input,references::add).path("reportId").asText()).isEqualTo("diagnosis");
        assertThat(references).containsExactly("diagnosis");
    }
    @Test void cooperativeCancellationStopsLateWritesAndAlwaysClearsThreadScope() {
        assertThatThrownBy(() -> BackgroundWork.run(() -> false, () -> "late result")).isInstanceOf(BusinessException.class);
        assertThatCode(BackgroundWork::check).doesNotThrowAnyException();
        assertThat(BackgroundWork.run(() -> true, () -> "saved")).isEqualTo("saved");
    }
}
