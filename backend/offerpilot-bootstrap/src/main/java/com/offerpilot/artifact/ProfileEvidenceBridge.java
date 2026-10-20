package com.offerpilot.artifact;

import com.offerpilot.career.application.ProfileEvidenceSource;
import com.offerpilot.resume.application.*;
import com.offerpilot.resume.github.GitHubEvidenceService;
import com.offerpilot.interview.application.InterviewService;
import com.offerpilot.auth.application.UserDirectory;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class ProfileEvidenceBridge implements ProfileEvidenceSource {
    private final ResumeService resumes; private final ResumeMatchSource snapshots;
    private final GitHubEvidenceService github; private final InterviewService interviews; private final UserDirectory identities;
    public ProfileEvidenceBridge(ResumeService resumes,ResumeMatchSource snapshots,GitHubEvidenceService github,InterviewService interviews,UserDirectory identities) {
        this.resumes=resumes;this.snapshots=snapshots;this.github=github;this.interviews=interviews;this.identities=identities;
    }
    public List<String> owners(String after,int limit) { return identities.activeAfter(after,limit); }
    public List<Observation> read(String owner) {
        var result=new ArrayList<Observation>();
        var resume=resumes.latestReady(owner);
        if(resume!=null) {
            var data=snapshots.snapshot(owner,resume.id(),resume.currentVersionId()).structured();
            var skills=new LinkedHashSet<String>();
            for(var group:data.path("skills")) for(var skill:group.path("items")) add(skills,skill.asText());
            for(var project:data.path("projects")) for(var tech:project.path("tech")) add(skills,tech.asText());
            if(!skills.isEmpty()) result.add(new Observation("RESUME",resume.id(),resume.currentVersionId(),List.copyOf(skills),
                List.of("简历版本 "+resume.currentVersionId()+" 列出的技能与项目技术；不代表已验证熟练度。")));
        }
        var gh=github.get(owner);
        if(gh.snapshot()!=null && !gh.stale()) {
            var skills=new LinkedHashSet<String>(); var facts=new ArrayList<String>();
            for(var repo:gh.snapshot().repositories()) if(!repo.fork() && repo.commits90d()>0) {
                repo.languages().keySet().forEach(s->add(skills,s));
                facts.add("GitHub "+repo.url()+"：仓库语言 "+String.join("、",repo.languages().keySet())+"；本人近90天提交"+(repo.commitsTruncated()?"至少":"")+repo.commits90d()+"次。仓库语言不证明本人使用或掌握该语言。");
            }
            if(!facts.isEmpty()) result.add(new Observation("GITHUB",gh.username(),gh.snapshot().id(),List.copyOf(skills),java.util.stream.Stream.concat(java.util.stream.Stream.of("GitHub 快照 "+gh.snapshot().id()+"，抓取于 "+gh.fetchedAt()),facts.stream().limit(20)).toList()));
        }
        var interview=interviews.latestCompleted(owner);
        if(interview!=null) {
            var report=interviews.report(owner,interview.id());
            if(report.overall()!=null) {
                var facts=new ArrayList<String>();
                report.strengths().stream().limit(5).forEach(s->facts.add("面试报告优点："+s));
                report.weaknesses().stream().limit(5).forEach(s->facts.add("面试报告待练习："+s));
                if(!facts.isEmpty()) result.add(new Observation("INTERVIEW",interview.id(),interview.updatedAt()+report.toString(),List.of(),facts));
            }
        }
        return List.copyOf(result);
    }
    private static void add(Set<String> values,String raw) {
        String value=raw.strip(); if(!value.isEmpty() && value.codePointCount(0,value.length())<=100 && values.size()<50) values.add(value);
    }
}
