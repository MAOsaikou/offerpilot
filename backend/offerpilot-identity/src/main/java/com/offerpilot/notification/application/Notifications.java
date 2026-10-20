package com.offerpilot.notification.application;

import com.offerpilot.common.error.*;
import com.offerpilot.common.id.IdGenerator;
import com.offerpilot.common.notification.NotificationSink;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Service
public class Notifications implements NotificationSink {
    private final JdbcTemplate jdbc;
    public Notifications(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Notice(String id, String kind, String title, String body, String link, String createdAt, String readAt) {}
    public record Page(List<Notice> items, long unread, long total, int page, int size) {}
    @Override
    public void send(String owner, String kind, String key, String title, String body, String link) {
        if (owner == null || owner.isBlank() || key == null || key.length() > 500 || !link.matches("^/[^/].*"))
            throw new IllegalArgumentException("Invalid notification destination");
        // INSERT SELECT also tolerates a source owner deleted while background work was in flight.
        jdbc.update("""
            INSERT INTO notifications(id,user_id,kind,dedupe_key,title,body,link)
            SELECT ?,id,?,?,?,?,? FROM users WHERE id=? AND status='ACTIVE'
            ON CONFLICT(user_id,dedupe_key) DO NOTHING
            """, IdGenerator.next("ntf"), kind, key, com.offerpilot.common.text.TextBounds.truncate(title,200), com.offerpilot.common.text.TextBounds.truncate(body,4000), link, owner);
    }
    public long unread(String owner) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=? AND read_at IS NULL", Long.class, owner);
        return count == null ? 0 : count;
    }
    public Page list(String owner, int page, int size) {
        if (page < 1 || page > 100000 || size < 1 || size > 50) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        var items = jdbc.query("SELECT * FROM notifications WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs,n) -> new Notice(rs.getString("id"),rs.getString("kind"),rs.getString("title"),rs.getString("body"),rs.getString("link"),
                rs.getTimestamp("created_at").toInstant().toString(),rs.getTimestamp("read_at")==null ? null : rs.getTimestamp("read_at").toInstant().toString()),
            owner,size,(page-1)*size);
        Long total = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id=?",Long.class,owner);
        return new Page(items,unread(owner),total==null?0:total,page,size);
    }
    @Transactional
    public void read(String owner, String id) {
        if (jdbc.update("UPDATE notifications SET read_at=coalesce(read_at,now()) WHERE user_id=? AND id=?",owner,id)==0)
            throw new BusinessException(ErrorCode.ROUTE_NOT_FOUND);
    }
    public void readAll(String owner) {
        jdbc.update("UPDATE notifications SET read_at=now() WHERE user_id=? AND read_at IS NULL",owner);
    }
}
