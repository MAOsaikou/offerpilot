package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JobMatcherTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode report() throws Exception {
        return (ObjectNode)json.readTree("""
            {"summary":"项目证据可用", "requirements":[{"requirement":"Java","status":"MET","jdQuote":"熟悉Java","resumePath":"/summary","resumeQuote":"Java项目","reason":"有相关项目","suggestion":"补充项目细节"}]}
            """);
    }
    @Test void acceptsOnlyExactQuotesAtStringPaths() throws Exception {
        var resume=json.readTree("{\"summary\":\"完成Java项目\"}");
        assertThatCode(()->JobMatcher.validate(report(),"熟悉Java及SQL",resume)).doesNotThrowAnyException();
        var wrong=report(); ((ObjectNode)wrong.path("requirements").get(0)).put("resumeQuote","大型分布式系统");
        assertThatThrownBy(()->JobMatcher.validate(wrong,"熟悉Java",resume)).isInstanceOf(BusinessException.class);
        var path=report(); ((ObjectNode)path.path("requirements").get(0)).put("resumePath","/missing");
        assertThatThrownBy(()->JobMatcher.validate(path,"熟悉Java",resume)).isInstanceOf(BusinessException.class);
    }
    @Test void rejectsInventedRequirementsAndUnbackedScores() throws Exception {
        var resume=json.readTree("{\"summary\":\"完成Java项目\"}");
        assertThatThrownBy(()->JobMatcher.validate(report(),"熟悉Python",resume)).isInstanceOf(BusinessException.class);
        var missing=report(); var item=(ObjectNode)missing.path("requirements").get(0); item.put("status","MISSING");
        assertThatThrownBy(()->JobMatcher.validate(missing,"熟悉Java",resume)).isInstanceOf(BusinessException.class);
        item.put("resumePath","").put("resumeQuote","");
        assertThatCode(()->JobMatcher.validate(missing,"熟悉Java",resume)).doesNotThrowAnyException();
    }
    @Test void duplicateEvidenceCannotInflateCoverage() throws Exception {
        var report=report(); ((com.fasterxml.jackson.databind.node.ArrayNode)report.path("requirements")).add(report.path("requirements").get(0).deepCopy());
        assertThatThrownBy(()->JobMatcher.validate(report,"熟悉Java",json.readTree("{\"summary\":\"Java项目\"}"))).isInstanceOf(BusinessException.class);
    }
}
