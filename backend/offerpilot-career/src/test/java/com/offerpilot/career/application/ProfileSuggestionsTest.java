package com.offerpilot.career.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.notification.NotificationSink;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class ProfileSuggestionsTest {
    @Test void mergePreservesManualTextDeduplicatesAndBoundsWithoutTruncating() {
        assertThat(ProfileSuggestions.merge("Java、SQL\n真实项目",List.of("java","Python","Python"))).isEqualTo("Java、SQL\n真实项目\nPython");
        assertThatThrownBy(()->ProfileSuggestions.merge("字".repeat(3000),List.of("新技能"))).isInstanceOf(BusinessException.class);
    }
    @Test void fingerprintPinsTypeSourceVersionAndFacts() {
        var a=new ProfileEvidenceSource.Observation("RESUME","r","v1",List.of("Java"),List.of("原文"));
        assertThat(ProfileSuggestions.fingerprint(new ProfileEvidenceSource.Observation("RESUME","r","v",List.of("a, b"),List.of())))
            .isNotEqualTo(ProfileSuggestions.fingerprint(new ProfileEvidenceSource.Observation("RESUME","r","v",List.of("a","b"),List.of())));
        assertThat(ProfileSuggestions.fingerprint(a)).isEqualTo(ProfileSuggestions.fingerprint(a)).hasSize(64);
        assertThat(ProfileSuggestions.fingerprint(a)).isNotEqualTo(ProfileSuggestions.fingerprint(new ProfileEvidenceSource.Observation("RESUME","r","v2",a.skills(),a.facts())));
    }
    private TransactionTemplate transaction() {
        var tx=mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(c->c.<TransactionCallback<Object>>getArgument(0).doInTransaction(mock(org.springframework.transaction.TransactionStatus.class)));
        return tx;
    }
    @Test void staleAndUnproposedSkillsCannotMutateProfile() {
        var jdbc=mock(JdbcTemplate.class);var source=mock(ProfileEvidenceSource.class);var profile=mock(CareerProfileService.class);
        when(jdbc.queryForList(contains("FROM users"),eq(String.class),eq("alice"))).thenReturn(List.of("alice"));
        var observation=new ProfileEvidenceSource.Observation("RESUME","r","v1",List.of("Java"),List.of("已存"));
        when(jdbc.queryForList(contains("FOR UPDATE"),eq("alice"),eq("suggestion"))).thenReturn(List.of(Map.of("source_type","RESUME","source_id","r","fingerprint",ProfileSuggestions.fingerprint(observation),"skills","[\"Java\"]","status","PENDING")));
        var service=new ProfileSuggestions(jdbc,new ObjectMapper(),source,profile,mock(NotificationSink.class),transaction());
        when(source.read("alice")).thenReturn(List.of());
        assertThatThrownBy(()->service.decide("alice","suggestion",true,List.of("Java"))).isInstanceOf(BusinessException.class);
        when(source.read("alice")).thenReturn(List.of(observation));
        assertThatThrownBy(()->service.decide("alice","suggestion",true,List.of("invented"))).isInstanceOf(BusinessException.class);
        verifyNoInteractions(profile);
    }
    @Test void acceptedSkillsMergeLatestManualChangesAndRejectNeedsNoSourceOrProfileWrite() {
        var jdbc=mock(JdbcTemplate.class);var source=mock(ProfileEvidenceSource.class);var profiles=mock(CareerProfileService.class);
        when(jdbc.queryForList(contains("FROM users"),eq(String.class),eq("alice"))).thenReturn(List.of("alice"));
        var o=new ProfileEvidenceSource.Observation("RESUME","r","v",List.of("Java"),List.of("原文"));
        when(jdbc.queryForList(contains("FOR UPDATE"),eq("alice"),eq("s"))).thenReturn(List.of(Map.of("fingerprint",ProfileSuggestions.fingerprint(o),"skills","[\"Java\"]","status","PENDING")));
        when(source.read("alice")).thenReturn(List.of(o));
        when(profiles.get("alice")).thenReturn(new CareerProfileService.Profile("目标","上海","SQL",List.of("研究"),List.of(),null));
        var service=new ProfileSuggestions(jdbc,new ObjectMapper(),source,profiles,mock(NotificationSink.class),transaction());
        service.decide("alice","s",true,List.of("Java"));
        verify(profiles).save("alice","目标","上海","SQL\nJava",List.of("研究"));
        clearInvocations(profiles,source);
        service.decide("alice","s",false,List.of());
        verify(profiles,never()).save(anyString(),anyString(),anyString(),anyString(),anyList());verifyNoInteractions(source);
    }
}
