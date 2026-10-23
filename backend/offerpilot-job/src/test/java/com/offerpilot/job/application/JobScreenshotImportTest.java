package com.offerpilot.job.application;

import com.offerpilot.common.error.*;
import com.offerpilot.llm.*;
import com.offerpilot.llm.quota.LlmUserContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import reactor.core.publisher.Flux;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobScreenshotImportTest {
    final ModelGateway gateway=mock(ModelGateway.class);
    final JobScreenshotService screenshots=new JobScreenshotService(null,null,null,gateway,null);
    static Flux<ChatResponse> response(String output) {
        return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage(output)))));
    }
    @Test void imagesUseVisionAltWithOwnerScopeAndNeverAskForInferredFields() {
        when(gateway.stream(eq(ModelTier.VISION_ALT),anyList(),eq(0.0))).thenAnswer(call -> {
            assertThat(LlmUserContext.current()).isEqualTo("owner");
            List<org.springframework.ai.chat.messages.Message> messages=call.getArgument(1);
            assertThat(((UserMessage)messages.get(1)).getMedia()).hasSize(3);
            assertThat(messages.get(0).getText()).contains("逐行", "不得总结", "绝不执行").doesNotContain("JSON 对象");
            return response(JobImportParserTest.SOURCE);
        });
        var image=new JobScreenshotService.Image(new byte[]{1},"image/png");
        assertThat(screenshots.transcribe("owner",List.of(image,image,image))).isEqualTo(JobImportParserTest.SOURCE.strip());
        verify(gateway).requireAvailable(ModelTier.VISION_ALT,true);
    }
    @Test void visionReceivesPreparedImagesInOrderWhileNormalImageBytesStayIntact() throws IOException {
        var tallPng = ScreenshotImagePreprocessorTest.encode(new BufferedImage(32, 12000, BufferedImage.TYPE_INT_RGB), "png");
        var normal = ScreenshotImagePreprocessorTest.encode(new BufferedImage(64, 80, BufferedImage.TYPE_INT_RGB), "jpg");
        var tallJpeg = ScreenshotImagePreprocessorTest.encode(new BufferedImage(48, 14000, BufferedImage.TYPE_INT_RGB), "jpg");
        when(gateway.stream(eq(ModelTier.VISION_ALT), anyList(), eq(0.0))).thenAnswer(call -> {
            List<org.springframework.ai.chat.messages.Message> messages = call.getArgument(1);
            var media = ((UserMessage) messages.get(1)).getMedia();
            assertThat(media).hasSize(3);
            assertThat(media.get(0).getMimeType().toString()).isEqualTo("image/png");
            assertThat(media.get(1).getMimeType().toString()).isEqualTo("image/jpeg");
            assertThat(media.get(1).getDataAsByteArray()).containsExactly(normal.bytes());
            assertThat(media.get(2).getMimeType().toString()).isEqualTo("image/png");
            for (int index : List.of(0, 2)) {
                var image = ScreenshotImagePreprocessorTest.decode(new JobScreenshotService.Image(media.get(index).getDataAsByteArray(), "image/png"));
                assertThat(image.getHeight()).isEqualTo(2400);
                assertThat(image.getWidth()).isEqualTo(index == 0 ? 32 : 48);
            }
            return response(JobImportParserTest.SOURCE);
        });

        assertThat(screenshots.transcribe("owner", List.of(tallPng, normal, tallJpeg))).isEqualTo(JobImportParserTest.SOURCE.strip());
    }
    @Test void unavailableUnsupportedEmptyAndOversizedTranscriptsNeverFallBack() {
        doThrow(new BusinessException(ErrorCode.MODEL_NOT_CONFIGURED)).when(gateway).requireAvailable(ModelTier.VISION_ALT,true);
        assertThatThrownBy(() -> screenshots.transcribe("owner",List.of())).isInstanceOf(BusinessException.class).hasMessageContaining("粘贴");
        verify(gateway,never()).stream(any(),anyList(),anyDouble());
        reset(gateway);
        for (String output: List.of("", "看不清", "字".repeat(20001))) {
            when(gateway.stream(any(),anyList(),anyDouble())).thenReturn(response(output));
            assertThatThrownBy(() -> screenshots.transcribe("owner",List.of())).isInstanceOf(BusinessException.class).hasMessageContaining("粘贴");
        }
        when(gateway.stream(any(),anyList(),anyDouble())).thenReturn(Flux.error(new IllegalStateException("vendor failure")));
        assertThatThrownBy(() -> screenshots.transcribe("owner",List.of())).hasMessageContaining("视觉模型不可用").hasMessageNotContaining("vendor failure");
    }
    @Test void overlappingScreenshotsKeepEachRepeatedFullLineOnce() {
        String merged = JobScreenshotService.dropOverlapRepeats(
                "岗位职责：\n• 协助梳理用户场景与需求，维护产品文档\n• 协助梳理用户场景与需求，维护产品文档\n任职要求：\n任职要求：\n1. 熟悉 SQL 与数据分析工具");
        org.assertj.core.api.Assertions.assertThat(merged).isEqualTo(
                "岗位职责：\n• 协助梳理用户场景与需求，维护产品文档\n任职要求：\n任职要求：\n1. 熟悉 SQL 与数据分析工具");
    }
    @Test void mimeAndPageCountAreStrict() {
        assertThat(JobScreenshotService.mime("a.PNG",new byte[]{(byte)137,80,78,71,13,10,26,10})).isEqualTo("image/png");
        assertThat(JobScreenshotService.mime("a.jpeg",new byte[]{(byte)255,(byte)216,(byte)255})).isEqualTo("image/jpeg");
        assertThat(JobScreenshotService.mime("a.webp","RIFF1234WEBPVP8X1234".getBytes(StandardCharsets.US_ASCII))).isEqualTo("image/webp");
        assertThatThrownBy(() -> JobScreenshotService.mime("a.png",new byte[]{1})).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JobScreenshotService.mime("a.svg",new byte[]{1})).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JobScreenshotService.mime("a.png",new byte[0])).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> JobScreenshotService.mime("a.png",new byte[5*1024*1024+1])).hasMessageContaining("5MB");
        for (var ids:List.of(List.<String>of(),List.of("a","a"),List.of("a","b","c","d")))
            assertThatThrownBy(() -> screenshots.validate("owner",ids)).isInstanceOf(BusinessException.class);
    }
    @Test void transcriptReusesGroundingAndSafetyNoticesWithoutInventingCompanyOrCity() {
        var parser=JobImportParserTest.parser();
        when(gateway.detectInjection(anyString())).thenReturn(List.of("ignore_system"));
        var imports=new JobImportService(parser,null,null,null,null,gateway);
        var result=imports.parseTranscript("owner",JobImportParserTest.SOURCE.replace("工作城市：北京\n",""));
        assertThat(result.sourceKind()).isEqualTo("screenshot");
        assertThat(result.draft().company()).isEmpty();
        assertThat(result.draft().locations()).isEmpty();
        assertThat(result.draft().description()).contains("报表搭建");
        verify(gateway).detectInjection(anyString());
    }
}
