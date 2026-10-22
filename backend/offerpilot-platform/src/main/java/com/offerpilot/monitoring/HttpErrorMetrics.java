package com.offerpilot.monitoring;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayDeque;

/** Bounded five-minute buckets, aggregate only: no URLs, identities or credentials. */
@Component
public class HttpErrorMetrics extends OncePerRequestFilter {
    private record Bucket(long second,long requests,long errors) {}
    public record Window(long requests,long errors,double errorRate) {}
    private final ArrayDeque<Bucket> buckets = new ArrayDeque<>();
    private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired
    public HttpErrorMetrics(MeterRegistry meters) { this(meters,Clock.systemUTC()); }
    public HttpErrorMetrics(MeterRegistry meters,Clock clock) {
        this.clock=clock;
        meters.gauge("offerpilot.http.error.rate",this,m -> m.window().errorRate());
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest r) {
        return !r.getRequestURI().startsWith("/api/") || r.getRequestURI().startsWith("/api/v1/admin/ops");
    }
    @Override protected void doFilterInternal(HttpServletRequest req,HttpServletResponse res,FilterChain chain) throws ServletException,IOException {
        boolean failed=false;
        try { chain.doFilter(req,res); }
        catch(ServletException | IOException | RuntimeException ex) { failed=true; throw ex; }
        finally { record(failed || res.getStatus()>=500); }
    }
    public synchronized void record(boolean error) {
        long now=clock.instant().getEpochSecond();
        var last=buckets.peekLast();
        if(last!=null && last.second()==now) {
            buckets.removeLast(); buckets.add(new Bucket(now,last.requests()+1,last.errors()+(error?1:0)));
        } else buckets.add(new Bucket(now,1,error?1:0));
        prune(now);
    }
    private void prune(long now) { while(!buckets.isEmpty() && buckets.peekFirst().second()<=now-300) buckets.removeFirst(); }
    public synchronized Window window() {
        prune(clock.instant().getEpochSecond());
        long requests=0,errors=0;
        for(var b:buckets) { requests+=b.requests(); errors+=b.errors(); }
        return new Window(requests,errors,requests==0?0:(double)errors/requests);
    }
}
