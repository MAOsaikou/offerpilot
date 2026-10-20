package com.offerpilot.notification.application;
import com.offerpilot.common.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class NotificationsTest {
    @Test void deliveryUsesOwnerAndStableDedupeKeyAndRejectsExternalLinks() {
        var jdbc=mock(JdbcTemplate.class);var service=new Notifications(jdbc);
        service.send("alice","DDL","app:date:3","title","body","/board?application=a");
        verify(jdbc).update(contains("ON CONFLICT(user_id,dedupe_key) DO NOTHING"),anyString(),eq("DDL"),eq("app:date:3"),eq("title"),eq("body"),eq("/board?application=a"),eq("alice"));
        assertThatThrownBy(()->service.send("alice","DDL","k","t","b","//evil.test")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->service.send("alice","DDL","k","t","b","https://evil.test")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void readIsOwnerScopedAndForeignOrMissingIdsHaveTheSameFailure() {
        var jdbc=mock(JdbcTemplate.class);var service=new Notifications(jdbc);
        assertThatThrownBy(()->service.read("bob","alice-notice")).isInstanceOf(BusinessException.class);
        verify(jdbc).update(contains("WHERE user_id=? AND id=?"),eq("bob"),eq("alice-notice"));
        service.readAll("bob");verify(jdbc).update(contains("WHERE user_id=? AND read_at IS NULL"),eq("bob"));
        assertThatThrownBy(()->service.list("bob",0,20)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(()->service.list("bob",1,51)).isInstanceOf(BusinessException.class);
    }
}
