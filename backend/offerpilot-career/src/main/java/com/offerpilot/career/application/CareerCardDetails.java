package com.offerpilot.career.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.*;

/** Catalog-only card facts and exact saved user evidence; no generative completion of missing data. */
@Component
public class CareerCardDetails {
    public record Evidence(String field, String value, String source) {}
    public record Details(List<Evidence> evidence, List<String> typicalTitles, List<String> coreSkills,
                          String growthPath, String salaryRange, List<String> companies, String concerns,
                          String sourceOccupationId, String source) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CareerCardDetails(JdbcTemplate jdbc,ObjectMapper json) { this.jdbc=jdbc; this.json=json; }
    public Details load(String occupationId, List<Evidence> evidence) {
        var rows=jdbc.query("SELECT * FROM occupations WHERE id=?",(rs,n) -> {
            try {
                var skills=new ArrayList<String>();
                for (var skill : json.readTree(rs.getString("core_skills"))) {
                    String name=skill.path("name").asText("");
                    if (!name.isBlank()) skills.add(name);
                }
                var salary=json.readTree(Objects.toString(rs.getString("salary_range"),"null"));
                String salaryText=salary.path("campus").asText("");
                if (!salaryText.isBlank()) salaryText+="（"+provided(salary.path("source").asText(""))+"）";
                return new Details(evidence,strings(rs.getArray("typical_titles")),provided(skills),provided(rs.getString("growth_path")),
                        provided(salaryText),strings(rs.getArray("companies")),"未提供",occupationId,provided(rs.getString("source")));
            } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
        },occupationId);
        if (rows.isEmpty()) throw new IllegalStateException("推荐职业不在目录中");
        return rows.get(0);
    }
    static List<String> strings(java.sql.Array array) throws java.sql.SQLException {
        return array==null?List.of("未提供"):provided(Arrays.asList((String[])array.getArray()));
    }
    static List<String> provided(List<String> values) {
        var clean=values.stream().filter(Objects::nonNull).filter(s -> !s.isBlank()).toList();
        return clean.isEmpty()?List.of("未提供"):clean;
    }
    static String provided(String text) { return text==null||text.isBlank()?"未提供":text; }
    public static List<Evidence> evidence(CareerProfileService.Profile profile, CareerAssessmentService.Assessment assessment) {
        var out=new ArrayList<Evidence>();
        if (assessment!=null) for (int i=0;i<assessment.answers().size();i++)
            add(out,"answers/"+i,assessment.answers().get(i),"career/assessment");
        add(out,"major",profile.major(),"career/profile");
        add(out,"target",profile.target(),"career/profile");
        add(out,"city",profile.city(),"career/profile");
        add(out,"skills",profile.skills(),"career/profile");
        for (String interest : profile.interests()) add(out,"interests",interest,"career/profile");
        if (assessment!=null && assessment.confirmed()!=null) assessment.confirmed().riasec().forEach((axis,value) ->
                add(out,"riasec/"+axis,String.valueOf(value),"career/assessment/confirmed"));
        return out.stream().limit(2).toList();
    }
    private static void add(List<Evidence> out,String field,String value,String source) {
        if (value!=null&&!value.isBlank()&&out.stream().noneMatch(e -> e.value().equals(value)))
            out.add(new Evidence(field,value.codePointCount(0,value.length())>120 ? value.substring(0,value.offsetByCodePoints(0,120)) : value,source));
    }
}
