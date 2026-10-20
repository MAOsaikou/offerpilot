package com.offerpilot.auth.application;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
/** Bounded trusted directory for periodic owner-scoped reconciliation. */
@Service
public class UserDirectory {
    private final JdbcTemplate jdbc;
    public UserDirectory(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<String> activeAfter(String after,int limit) {
        return jdbc.queryForList("SELECT id FROM users WHERE status='ACTIVE' AND is_demo=false AND id>? ORDER BY id LIMIT ?",String.class,after,Math.min(50,Math.max(1,limit)));
    }
}
