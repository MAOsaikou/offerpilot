package com.offerpilot.ops;

import com.offerpilot.monitoring.HttpErrorMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import java.util.*;

@Service
public class OpsMonitor {
    public record Usage(long calls,long failed,long promptTokens,long completionTokens,long unreported) {}
    public record Status(String state,long uptimeSeconds,HttpErrorMetrics.Window http,Usage usage,
                         List<String> alerts,Map<String,Double> moderationHits,boolean externalPushEnabled,String checkedAt) {}
    private final MeterRegistry meters;
    private final JdbcTemplate jdbc;
    private final HttpErrorMetrics http;
    private final Environment env;
    private final ObjectProvider<OpsNotificationChannel> channels;
    private Set<String> previous=Set.of();
    private volatile Status last;
    public OpsMonitor(JdbcTemplate jdbc,HttpErrorMetrics http,Environment env,ObjectProvider<OpsNotificationChannel> channels,MeterRegistry meters) {
        this.jdbc=jdbc;this.http=http;this.env=env;this.channels=channels;this.meters=meters;
        meters.gauge("offerpilot.model.calls.hour",this,m -> m.last==null || m.last.usage()==null?0:m.last.usage().calls());
        meters.gauge("offerpilot.model.tokens.hour",this,m -> m.last==null || m.last.usage()==null?0:m.last.usage().promptTokens()+m.last.usage().completionTokens());
    }
    public Status status() { Status s=last; return s==null?check():s; }
    @Scheduled(fixedDelayString="${offerpilot.ops.check-delay-ms:60000}",initialDelayString="${offerpilot.ops.initial-delay-ms:10000}")
    public synchronized Status check() {
        var alerts=new TreeSet<String>();
        var window=http.window();
        Usage usage=null;
        try {
            usage=jdbc.queryForObject("""
                SELECT count(*) calls,count(*) FILTER(WHERE status='FAILED') failed,
                  coalesce(sum(prompt_tokens),0) input,coalesce(sum(completion_tokens),0) output,
                  count(*) FILTER(WHERE NOT usage_reported) unreported
                FROM llm_usage_logs WHERE created_at>=now()-interval '1 hour'
                """,(rs,n)->new Usage(rs.getLong("calls"),rs.getLong("failed"),rs.getLong("input"),rs.getLong("output"),rs.getLong("unreported")));
        } catch(RuntimeException ex) { alerts.add("USAGE_UNAVAILABLE"); }
        if(window.requests()>=env.getProperty("offerpilot.ops.min-requests",Long.class,20L)
                && window.errorRate()>env.getProperty("offerpilot.ops.error-rate-threshold",Double.class,0.05)) alerts.add("HTTP_ERROR_RATE");
        if(usage!=null && usage.promptTokens()+usage.completionTokens()>env.getProperty("offerpilot.ops.hourly-token-threshold",Long.class,1_000_000L)) alerts.add("MODEL_TOKEN_USAGE");
        boolean push=env.getProperty("offerpilot.ops.external-push-enabled",Boolean.class,false);
        var changed=new TreeSet<>(previous);changed.addAll(alerts);
        for(String code:changed) if(previous.contains(code)!=alerts.contains(code)) {
            boolean active=alerts.contains(code);
            LoggerFactory.getLogger(getClass()).warn("Ops alert code={} active={}",code,active);
            if(push) channels.orderedStream().forEach(channel -> {
                try { channel.notify(code,active); }
                catch(RuntimeException ex) { LoggerFactory.getLogger(getClass()).warn("Ops notification failed code={}",code); }
            });
        }
        previous=Set.copyOf(alerts);
        var moderation=new TreeMap<String,Double>();
        meters.find("offerpilot.output.blocked").counters().forEach(counter -> {
            String category=counter.getId().getTag("category");
            if(category!=null) moderation.put(category,counter.count());
        });
        last=new Status(alerts.isEmpty()?"UP":"DEGRADED",java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime()/1000,
                window,usage,List.copyOf(alerts),Map.copyOf(moderation),push,java.time.Instant.now().toString());
        return last;
    }
}
