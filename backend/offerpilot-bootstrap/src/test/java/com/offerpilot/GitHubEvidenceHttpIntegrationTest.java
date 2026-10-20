package com.offerpilot;

import com.fasterxml.jackson.databind.*;
import com.offerpilot.auth.application.GuestIdentityService;
import com.offerpilot.github.*;
import com.offerpilot.resume.github.*;
import com.offerpilot.resume.application.ResumeStructure;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real Flyway V46, PostgreSQL, authentication and REST. GitHub/FLASH are local in-memory stubs. */
@TestPropertySource(properties="offerpilot.encryption.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class GitHubEvidenceHttpIntegrationTest extends SharedHttpIntegrationTest {
    @Autowired GuestIdentityService guests;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired GitHubTokenCipher cipher;
    @Autowired GitHubEvidenceService evidence;
    @MockBean GitHubClient client;
    @MockBean GitHubHighlights highlights;
    @LocalServerPort int port;
    GitHubClient.Snapshot fixture() {
        return new GitHubClient.Snapshot(List.of(new GitHubClient.Repository("fixture/project","https://github.com/fixture/project",false,5,Map.of("Java",100L),2,false,"Java API service.")),false,Instant.now().minusSeconds(90*86400));
    }
    @Test void encryptsCredentialScopesCacheHandlesFailuresAndDiscardsLateFetch() throws Exception {
        String token = guests.create().accessToken(), other = guests.create().accessToken(), owner = guests.require("Bearer " + token);
        assertThat(request("GET","/resumes/github",null,null).statusCode()).isEqualTo(401);
        var saved = request("PUT","/resumes/github",token,Map.of("username","fixture","token","fictional_only"));
        assertThat(saved.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(saved.body()).doesNotContain("fictional_only","token_ciphertext");
        String stored = jdbc.queryForObject("SELECT token_ciphertext FROM github_evidence WHERE user_id=?",String.class,owner);
        assertThat(stored).doesNotContain("fictional_only"); assertThat(cipher.decrypt(owner,stored)).isEqualTo("fictional_only");
        assertThat(data(request("GET","/resumes/github",other,null)).path("username").asText()).isEmpty();
        when(client.fetch("fixture","fictional_only")).thenReturn(fixture());
        when(highlights.extract(eq(owner),any())).thenThrow(new IllegalStateException("fixture model unavailable"));
        var fetched = data(request("POST","/resumes/github/refresh",token,null));
        assertThat(fetched.path("status").asText()).isEqualTo("STATISTICS_ONLY");
        assertThat(fetched.path("snapshot").path("stars").asInt()).isEqualTo(5);
        assertThat(fetched.path("snapshot").path("repositories").get(0).path("highlights")).isEmpty();
        data(request("POST","/resumes/github/refresh",token,null)); verify(client,times(1)).fetch("fixture","fictional_only");
        assertThat(request("POST","/resumes/github/refresh?force=true",token,null).statusCode()).isEqualTo(429);
        jdbc.update("UPDATE github_evidence SET last_attempt_at=now()-interval '2 minutes' WHERE user_id=?",owner);
        when(client.fetch("fixture","fictional_only")).thenThrow(new GitHubClient.Failure("RATE_LIMITED","GitHub 请求已限流"));
        var failed = data(request("POST","/resumes/github/refresh?force=true",token,null));
        assertThat(failed.path("stale").asBoolean()).isTrue(); assertThat(failed.path("status").asText()).isEqualTo("RATE_LIMITED");
        assertThat(failed.path("fetchedAt")).isEqualTo(fetched.path("fetchedAt"));
        jdbc.update("UPDATE github_evidence SET last_attempt_at=now()-interval '2 minutes' WHERE user_id=?",owner);
        doAnswer(invocation -> { evidence.save(owner,"changed",null,false); return fixture(); }).when(client).fetch("fixture","fictional_only");
        var late = data(request("POST","/resumes/github/refresh?force=true",token,null));
        assertThat(late.path("username").asText()).isEqualTo("changed"); assertThat(late.path("tokenConfigured").asBoolean()).isFalse();
        assertThat(late.path("snapshot").isNull()).isTrue();
        request("DELETE","/resumes/github",token,null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_evidence WHERE user_id=?",Integer.class,owner)).isZero();
    }
    @Test void adoptionNeedsConfirmationOwnerAndCurrentVersionAndFeedsSkillGap() throws Exception {
        String token = guests.create().accessToken(), other = guests.create().accessToken(), owner = guests.require("Bearer " + token);
        var resume = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(ResumeStructure.SCHEMA);
        ((com.fasterxml.jackson.databind.node.ArrayNode) resume.path("skills").get(0).path("items")).add("Java");
        String rid = "rs-gh-" + UUID.randomUUID(), vid = "rv-gh-" + UUID.randomUUID(), file = "file-gh-" + UUID.randomUUID();
        jdbc.update("INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256) VALUES (?,?,'RESUME','test',?,'fixture.txt','text/plain',1,'fixture')",file,owner,file);
        jdbc.update("INSERT INTO resumes(id,user_id,title,source_file_id,parse_status) VALUES (?,?,'Fixture',?,'READY')",rid,owner,file);
        jdbc.update("INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text) VALUES (?,?,?,1,'UPLOAD',?::jsonb,'Fixture')",vid,rid,owner,resume.toString());
        jdbc.update("UPDATE resumes SET current_version_id=? WHERE id=?",vid,rid);
        evidence.save(owner,"fixture",null,false);
        when(client.fetch("fixture",null)).thenReturn(fixture());
        when(highlights.extract(eq(owner),any())).thenReturn(Map.of("fixture/project",List.of("Java API service.")));
        var view = evidence.refresh(owner,false);
        var body = new LinkedHashMap<String,Object>(Map.of("resumeId",rid,"baseVersionId",vid,"snapshotId",view.snapshot().id(),"repository","fixture/project","confirmed",false));
        assertThat(request("POST","/resumes/github/adopt",token,body).statusCode()).isEqualTo(400);
        body.put("confirmed",true);
        assertThat(request("POST","/resumes/github/adopt",other,body).statusCode()).isEqualTo(400);
        var adopted = data(request("POST","/resumes/github/adopt",token,body));
        assertThat(adopted.path("currentVersion").path("versionNo").asInt()).isEqualTo(2);
        assertThat(request("POST","/resumes/github/adopt",token,body).statusCode()).isEqualTo(409);
        String occupation = "occ-gh-" + UUID.randomUUID();
        jdbc.update("INSERT INTO occupations(id,name,family,description,source,core_skills) VALUES (?,?,'test','fixture','curated','[{\"name\":\"Java\"},{\"name\":\"Docker\"}]')",occupation,occupation);
        var gap = data(request("GET","/career/skill-gap?occupationId="+occupation,token,null));
        assertThat(gap.path("skills").get(0).path("level").asText()).isEqualTo("POSSESSED");
        assertThat(gap.path("skills").get(0).path("sources").toString()).contains("GITHUB","RESUME","OCCUPATION");
        assertThat(gap.path("skills").get(1).path("level").asText()).isEqualTo("MISSING");
        assertThat(request("GET","/career/skill-gap?occupationId="+occupation+"&resumeId="+rid+"&versionId="+vid,other,null).statusCode()).isEqualTo(404);
        jdbc.update("DELETE FROM users WHERE id=?",owner);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM github_evidence WHERE user_id=?",Integer.class,owner)).isZero();
    }
    private HttpResponse<String> request(String method,String path,String token,Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).header("Content-Type","application/json");
        if (token != null) builder.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode data(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200); return json.readTree(response.body()).path("data");
    }
}
