package com.offerpilot.resume.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.github.*;
import com.offerpilot.resume.application.ResumeService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GitHubEvidenceServiceTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final GitHubClient client = mock(GitHubClient.class);
    final GitHubHighlights highlights = mock(GitHubHighlights.class);
    final GitHubTokenCipher cipher = mock(GitHubTokenCipher.class);
    final ResumeService resumes = mock(ResumeService.class);
    final GitHubEvidenceService service = new GitHubEvidenceService(jdbc,json,client,cipher,highlights,resumes,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));
    @SuppressWarnings("unchecked") void stored(Instant fetched, String credential) throws Exception {
        var rs = mock(ResultSet.class);
        when(rs.getString("username")).thenReturn("fixture"); when(rs.getString("revision")).thenReturn("rev");
        when(rs.getString("token_ciphertext")).thenReturn(credential); when(rs.getString("status")).thenReturn("READY");
        when(rs.getString("notice")).thenReturn("scope");
        if (fetched != null) {
            var snapshot = new GitHubEvidenceService.Snapshot("snap",fetched.minusSeconds(90*86400),false,List.of(),Map.of(),0,0);
            when(rs.getString("snapshot")).thenReturn(json.writeValueAsString(snapshot)); when(rs.getTimestamp("fetched_at")).thenReturn(Timestamp.from(fetched));
        }
        when(jdbc.query(anyString(),any(RowMapper.class),eq("owner"))).thenAnswer(i -> List.of(((RowMapper<?>) i.getArgument(1)).mapRow(rs,0)));
        when(jdbc.update(contains("last_attempt_at=now()"),eq("owner"),eq("rev"))).thenReturn(1);
        when(jdbc.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE","owner")).thenReturn(List.of(Map.of("id","owner")));
    }
    @Test void cacheHitDoesNotReadCredentialOrInvokeAnyExternalService() throws Exception {
        stored(Instant.now(),"encrypted");
        var view = service.refresh("owner",false);
        assertThat(view.stale()).isFalse(); assertThat(view.snapshot()).isNotNull(); assertThat(view.tokenConfigured()).isTrue();
        assertThat(json.writeValueAsString(view)).doesNotContain("encrypted","token_ciphertext");
        verifyNoInteractions(client,cipher,highlights);
    }
    @Test void providerFailureUpdatesSafeStatusWithoutReplacingPreviousSnapshotOrTime() throws Exception {
        stored(Instant.now().minusSeconds(7*3600),null);
        when(client.fetch("fixture",null)).thenThrow(new GitHubClient.Failure("RATE_LIMITED","GitHub 请求已限流"));
        var result = service.refresh("owner",true);
        assertThat(result.snapshot().id()).isEqualTo("snap");
        verify(jdbc).update("UPDATE github_evidence SET status=?,notice=? WHERE user_id=? AND revision=?","RATE_LIMITED","GitHub 请求已限流","owner","rev");
        verifyNoInteractions(highlights);
    }
    @Test void modelFailureSavesActualStatisticsWithEmptyHighlightsAndRevisionGate() throws Exception {
        stored(null,null);
        var snapshot = new GitHubClient.Snapshot(List.of(new GitHubClient.Repository("fixture/p","https://github.com/fixture/p",false,8,Map.of("Java",20L),3,false,"README")),false,Instant.now());
        when(client.fetch("fixture",null)).thenReturn(snapshot);
        when(highlights.extract("owner",snapshot)).thenThrow(new IllegalStateException("fixture provider failure"));
        service.refresh("owner",true);
        var encoded = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(contains("snapshot=?::jsonb"),encoded.capture(),eq("STATISTICS_ONLY"),contains("降级为仅统计"),eq("owner"),eq("rev"));
        var saved = json.readTree(encoded.getValue());
        assertThat(saved.path("stars").asInt()).isEqualTo(8); assertThat(saved.path("commits90d").asInt()).isEqualTo(3);
        assertThat(saved.path("repositories").get(0).path("highlights")).isEmpty();
        assertThat(encoded.getValue()).doesNotContain("README","fixture provider failure");
    }
    @Test void malformedSettingsAndUnconfirmedAdoptionCannotTouchDatabaseOrCredentials() {
        assertThatThrownBy(() -> service.save("owner","https://github.com/x",null,false)).hasMessageContaining("用户名");
        assertThatThrownBy(() -> service.save("owner","fixture","Bearer invalid\n",false)).hasMessageContaining("格式");
        assertThatThrownBy(() -> service.adopt("owner","r","v","s","p",false)).hasMessageContaining("确认");
        verifyNoInteractions(jdbc,cipher,client,resumes);
    }
    @Test void sameUsernamePreservesCiphertextButNewUsernameDropsCredentialAndCache() throws Exception {
        stored(Instant.now(),"encrypted");
        service.save("owner","fixture",null,false);
        verify(jdbc).update(contains("INSERT INTO github_evidence"),eq("owner"),eq("fixture"),eq("encrypted"),anyString());
        service.save("owner","changed",null,false);
        verify(jdbc).update(contains("INSERT INTO github_evidence"),eq("owner"),eq("changed"),isNull(),anyString());
        verifyNoInteractions(cipher,client);
    }
}
