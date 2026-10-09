package com.offerpilot.auth.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.ResultSet;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PasswordLoginServiceTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
    private final JwtTokenService tokens = mock(JwtTokenService.class);
    private final PasswordLoginService service = new PasswordLoginService(jdbc, encoder, tokens);

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void missingAccountStillChecksCost12DummyHash(boolean staff) {
        PasswordEncoder checked = mock(PasswordEncoder.class);
        PasswordLoginService login = new PasswordLoginService(jdbc, checked, tokens);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), anyString()))
                .thenReturn(List.of());
        // Even if the supplied password matches the dummy, a missing account must always fail.
        when(checked.matches(anyString(), anyString())).thenReturn(true);
        assertBadCredentials(login, staff, "offerpilot-dummy-password");
        var hash = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(checked).matches(eq("offerpilot-dummy-password"), hash.capture());
        assertThat(hash.getValue()).startsWith("$2a$12$").hasSize(60);
        assertThat(encoder.matches("offerpilot-dummy-password", hash.getValue())).isTrue();
        verifyNoInteractions(tokens);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void wrongPasswordChecksStoredHashAndReturnsSameError(boolean staff) throws Exception {
        PasswordEncoder checked = mock(PasswordEncoder.class);
        PasswordLoginService login = new PasswordLoginService(jdbc, checked, tokens);
        ResultSet row = mock(ResultSet.class);
        when(row.getString("password_hash")).thenReturn("stored-hash");
        when(row.getString("roles_csv")).thenReturn("USER,STAFF");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), anyString()))
                .thenAnswer(call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(row, 0)));
        assertBadCredentials(login, staff, "wrong-password");
        verify(checked).matches("wrong-password", "stored-hash");
        verifyNoInteractions(tokens);
    }

    private void assertBadCredentials(PasswordLoginService login, boolean staff, String password) {
        assertThatThrownBy(() -> {
            if (staff) login.staffLogin("person@example.com", password);
            else login.login("person@example.com", password);
        }).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.BAD_CREDENTIALS));
    }

    static Stream<String> invalidRegistrationPasswords() {
        return Stream.of(null, "", "short", "      ", "\u2003".repeat(6),
                "a".repeat(73), "汉".repeat(24) + "a", "😀".repeat(19));
    }

    @ParameterizedTest
    @MethodSource("invalidRegistrationPasswords")
    void refusesInvalidRegistrationBeforeWritingOrHashing(String password) {
        assertThatThrownBy(() -> service.register("person@example.com", password))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        verifyNoInteractions(jdbc, tokens);
    }

    static Stream<String> validBoundaryPasswords() {
        return Stream.of("a".repeat(72), "汉".repeat(24), "😀".repeat(18), "  abcd  ");
    }

    @ParameterizedTest
    @MethodSource("validBoundaryPasswords")
    void acceptsUtf8BoundaryAndPreservesSignificantSpaces(String password) {
        when(tokens.issue(anyString(), anyList(), anyString())).thenReturn("test-token");
        when(tokens.accessTtl()).thenReturn(Duration.ofHours(12));
        assertThat(service.register("person@example.com", password).accessToken()).isEqualTo("test-token");
        verify(jdbc).update(anyString(), anyString(), eq("person@example.com"),
                argThat((String hash) -> encoder.matches(password, hash)), eq("person"));
    }

    @Test
    void refusesTruncatedPasswordAliasesForUserAndStaffLogin() throws Exception {
        String original = "汉".repeat(24);
        String hash = encoder.encode(original);
        // BCrypt alone accepts both suffixes; the application boundary must reject them.
        assertThat(encoder.matches(original + "a", hash)).isTrue();
        assertThat(encoder.matches(original + "b", hash)).isTrue();
        ResultSet row = mock(ResultSet.class);
        when(row.getString("id")).thenReturn("usr_test");
        when(row.getString("email")).thenReturn("person@example.com");
        when(row.getString("password_hash")).thenReturn(hash);
        when(row.getString("nickname")).thenReturn("person");
        when(row.getString("roles_csv")).thenReturn("USER,STAFF");
        when(row.getString("status")).thenReturn("ACTIVE");
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<Object>>any(), eq("person@example.com")))
                .thenAnswer(call -> List.of(call.<RowMapper<Object>>getArgument(1).mapRow(row, 0)));
        when(tokens.issue(anyString(), anyList(), anyString())).thenReturn("test-token");
        when(tokens.accessTtl()).thenReturn(Duration.ofHours(12));
        assertThat(service.login("person@example.com", original).accessToken()).isEqualTo("test-token");
        for (String suffix : List.of("a", "b")) {
            assertThatThrownBy(() -> service.login("person@example.com", original + suffix))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            assertThatThrownBy(() -> service.staffLogin("person@example.com", original + suffix))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
        }
    }
}
