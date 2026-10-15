package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.llm.ModelGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class JobImportParserTest {
    static final String SOURCE = """
            首页 | 社会招聘 | 校园招聘
            职位名称：数据分析实习生
            工作城市：北京
            职位描述
            - 负责业务数据的清洗与报表搭建
            - 支持运营团队的专项分析
            职位要求
            1. 熟练使用 SQL 和 Python
            2. 每周实习不少于 4 天
            """;

    @SuppressWarnings("unchecked")
    final JobImportParser parser = parser();

    static JobImportParser parser() {
        try {
            return new JobImportParser(mock(ObjectProvider.class), new ObjectMapper(), new ByteArrayResource("prompt".getBytes()));
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
    }

    @Test void rulesSplitByHeadingsAndUseLabels() {
        var draft = parser.rules(SOURCE, null);
        assertThat(draft.title()).isEqualTo("数据分析实习生");
        assertThat(draft.locations()).isEqualTo("北京");
        assertThat(draft.nature()).isEqualTo("intern");
        assertThat(draft.description()).contains("报表搭建", "专项分析").doesNotContain("SQL");
        assertThat(draft.requirement()).contains("SQL 和 Python", "不少于 4 天");
        assertThat(draft.parser()).isEqualTo("rule");
    }

    @Test void modelOutputIsKeptOnlyWhereItAppearsInTheSource() {
        var rules = parser.rules(SOURCE, null);
        String output = """
                {"title":"数据分析实习生","company":"字节跳动","locations":"北京、上海","department":"",
                 "nature":"intern",
                 "description":"1. 负责业务数据的清洗与报表搭建\\n2. 支持运营团队的专项分析",
                 "requirement":"熟练使用 SQL 和 Python\\n每周实习不少于 4 天\\n有大厂实习经历优先",
                 "skills":["SQL","Python","Tableau"]}
                """;
        var draft = parser.ground(output, SOURCE, rules);
        assertThat(draft.parser()).isEqualTo("model");
        // Company and Shanghai are not in the text: dropped rather than guessed.
        assertThat(draft.company()).isEmpty();
        assertThat(draft.locations()).isEqualTo("北京");
        assertThat(draft.description()).contains("报表搭建", "专项分析");
        // Two of three requirement lines are verbatim; the invented one is removed.
        assertThat(draft.requirement()).contains("SQL 和 Python").doesNotContain("大厂");
        assertThat(draft.skills()).containsExactly("SQL", "Python");
    }

    @Test void mostlyParaphrasedSectionsFallBackToTheRuleSplit() {
        var rules = parser.rules(SOURCE, null);
        String output = """
                {"title":"数据分析师","description":"做数据分析\\n写报告\\n负责业务数据的清洗与报表搭建","requirement":"会编程","nature":"campus"}
                """;
        var draft = parser.ground(output, SOURCE, rules);
        assertThat(draft.title()).isEqualTo("数据分析实习生");
        assertThat(draft.description()).isEqualTo(rules.description());
        assertThat(draft.requirement()).isEqualTo(rules.requirement());
        assertThat(parser.ground("not json", SOURCE, rules)).isNull();
    }

    @Test void pageFetcherRejectsNonPublicTargets() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1", "169.254.169.254", "100.64.0.1", "0.0.0.0", "::1", "fd00::1", "::ffff:127.0.0.1"})
            assertThat(JobPageFetcher.isPublic(InetAddress.getByName(ip))).as(ip).isFalse();
        assertThat(JobPageFetcher.isPublic(InetAddress.getByName("223.5.5.5"))).isTrue();
        for (String url : new String[]{"file:///etc/passwd", "ftp://example.com/a", "http://user:pw@example.com/", "http://example.com:8080/", "http://127.0.0.1/"})
            assertThatThrownBy(() -> JobPageFetcher.validate(url)).as(url).isInstanceOf(com.offerpilot.common.error.BusinessException.class);
    }

    @Test void htmlToTextDropsScriptsAndKeepsLines() {
        String html = "<html><head><title>后端工程师 - 星河招聘</title><script>var x='岗位职责';</script></head>"
                + "<body><nav>首页</nav><h1>后端工程师</h1><p>岗位职责：</p><ul><li>写服务</li><li>做优化</li></ul></body></html>";
        assertThat(HtmlText.title(html)).isEqualTo("后端工程师 - 星河招聘");
        assertThat(HtmlText.text(html)).isEqualTo("后端工程师\n岗位职责：\n• 写服务\n• 做优化");
        assertThat(parser.rules(HtmlText.text(html), HtmlText.title(html)).title()).isEqualTo("后端工程师");
    }
}
