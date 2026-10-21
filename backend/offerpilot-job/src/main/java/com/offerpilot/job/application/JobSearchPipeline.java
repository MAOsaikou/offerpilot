package com.offerpilot.job.application;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;

/** One wall-clock budget for parsing, both recalls and persistence. No unbounded request fanout. */
@Service
public class JobSearchPipeline {
    static final long SEARCH_BUDGET_MS = 7700;
    static long deadline() { return System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(SEARCH_BUDGET_MS); }
    private final JobSearchWork work; private final JobSearchParser parser; private final JobLocalSearch local;
    private final JobSource source; private final ObjectMapper json;
    public JobSearchPipeline(JobSearchWork work,JobSearchParser parser,JobLocalSearch local,JobSource source,ObjectMapper json) {
        this.work=work; this.parser=parser; this.local=local; this.source=source; this.json=json;
    }
    public JsonNode search(String owner,String query,String site,String nature,String location,BiFunction<JsonNode,String,JsonNode> save) {
        return search(owner,query,site,nature,location,save,false);
    }
    JsonNode search(String owner,String query,String site,String nature,String location,BiFunction<JsonNode,String,JsonNode> save,boolean structured) {
        return search(owner,query,site,nature,location,save,structured,deadline());
    }
    JsonNode search(String owner,String query,String site,String nature,String location,BiFunction<JsonNode,String,JsonNode> save,boolean structured,long deadline) {
        long start=deadline-TimeUnit.MILLISECONDS.toNanos(SEARCH_BUDGET_MS);
        var progress=new AtomicReference<ObjectNode>(empty());
        Future<ObjectNode> task;
        try { task=work.submit(()->execute(owner,query,site,nature,location,save,deadline,progress,structured)); }
        catch(RejectedExecutionException ex) { return partial(progress.get(),"SEARCH_BUSY",false,start); }
        ObjectNode result=JobSearchWork.await(task,deadline,null);
        if(result==null) return partial(progress.get(),task.isCancelled()?"SEARCH_TIMEOUT":"SEARCH_UNAVAILABLE",task.isCancelled(),start);
        result.withObject("/meta").put("durationMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));
        return result;
    }
    private ObjectNode execute(String owner,String query,String site,String nature,String location,
            BiFunction<JsonNode,String,JsonNode> save,long deadline,AtomicReference<ObjectNode> progress,boolean structured) {
        var fallback=JobSearchParser.rules(query,site,nature,location);
        var parsed=structured ? new JobSearchParser.Criteria(query,"",site,nature,location,"rule-route") : JobSearchWork.await(work.submit(()->parser.parse(owner,query,site,nature,location)),
                Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1100)),fallback);
        ObjectNode output=empty(); output.set("criteria",json.valueToTree(parsed));
        progress.set(output.deepCopy());
        var args=new LinkedHashMap<String,Object>(); args.put("query",parsed.query()); args.put("nature",parsed.nature()); args.put("limit",30);
        if(!parsed.site().isBlank()) args.put("site",parsed.site());
        else if(!parsed.company().isBlank()) args.put("site",parsed.company());
        if(!parsed.location().isBlank()) args.put("location",parsed.location());
        Future<JobLocalSearch.Result> localTask=null; Future<JsonNode> liveTask=null;
        try {
            localTask=work.submit(()->local.search(owner,parsed));
            liveTask=work.submit(()->source.search(args));
            var stored=JobSearchWork.await(localTask,Math.min(deadline,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1700)),null);
            var merged=new LinkedHashMap<String,JsonNode>();
            if(stored!=null) {
                stored.items().forEach(job->merge(merged,job));
                output.withObject("/meta").put("vectorAvailable",stored.vectorAvailable());
                publish(output,merged,progress);
            } else mark(output,"LOCAL_UNAVAILABLE",localTask.isCancelled());
            JsonNode remote=JobSearchWork.await(liveTask,deadline-TimeUnit.MILLISECONDS.toNanos(250),null);
            if(remote!=null) {
                var meta=output.withObject("/meta");
                if(remote.path("_meta").isObject()) meta.setAll((ObjectNode)remote.path("_meta"));
                // Restoring local flags after upstream metadata keeps partial-state accounting honest.
                if(stored==null) mark(output,"LOCAL_UNAVAILABLE",localTask.isCancelled());
                for(var raw:remote.path("jobs")) {
                    if(Thread.currentThread().isInterrupted() || System.nanoTime()>=deadline) { mark(output,"SEARCH_TIMEOUT",true); break; }
                    if(raw.path("id").asText().isBlank() || raw.path("site").asText().isBlank() || raw.path("title").asText().isBlank()) continue;
                    String kind=Set.of("campus","intern","social").contains(raw.path("nature").asText())?raw.path("nature").asText():parsed.nature();
                    var job=(ObjectNode)save.apply(raw,kind).deepCopy(); job.put("retrievalSource","JOBS_MCP");
                    if("LOCAL_SNAPSHOT".equals(job.path("source").asText())) job.put("retrievalSource","LOCAL_SNAPSHOT");
                    merge(merged,job); publish(output,merged,progress);
                }
                if(!meta.path("skipped").isEmpty() || meta.has("fallbackReason")) mark(output,"UPSTREAM_PARTIAL",false);
            } else mark(output,liveTask.isCancelled()?"LIVE_TIMEOUT":"LIVE_UNAVAILABLE",liveTask.isCancelled());
            publish(output,merged,progress);
            return output;
        } finally {
            if(localTask!=null && !localTask.isDone()) localTask.cancel(true);
            if(liveTask!=null && !liveTask.isDone()) liveTask.cancel(true);
        }
    }
    static void merge(Map<String,JsonNode> jobs,JsonNode job) {
        String key=identity(job); JsonNode previous=jobs.get(key);
        if(previous!=null) {
            var combined=(ObjectNode)job.deepCopy();
            var sources=combined.putArray("retrievalSources");
            var unique=new LinkedHashSet<String>();
            if(previous.path("retrievalSources").isArray()) previous.path("retrievalSources").forEach(s->unique.add(s.asText()));
            else unique.add(previous.path("retrievalSource").asText("LOCAL_DB"));
            unique.add(job.path("retrievalSource").asText("JOBS_MCP")); unique.forEach(sources::add);
            jobs.put(key,combined);
        } else jobs.put(key,job);
    }
    static String identity(JsonNode job) {
        String url=job.path("url").asText();
        if(!url.isBlank()) try {
            var uri=java.net.URI.create(url);
            String query=uri.getRawQuery()==null?"":Arrays.stream(uri.getRawQuery().split("&"))
                    .filter(pair->!pair.toLowerCase(Locale.ROOT).startsWith("utm_") && !pair.startsWith("spm="))
                    .sorted().collect(java.util.stream.Collectors.joining("&"));
            String canonical=uri.getScheme().toLowerCase(Locale.ROOT)+"://"+uri.getRawAuthority().toLowerCase(Locale.ROOT)
                    +uri.getRawPath().replaceFirst("/$", "")+(query.isBlank()?"":"?"+query);
            return "url:"+canonical+":"+job.path("nature").asText();
        } catch(RuntimeException ex) { /* Invalid stored URL: use the original source identity. */ }
        return "id:"+job.path("site").asText()+":"+job.path("externalId").asText()+":"+job.path("nature").asText();
    }

    private ObjectNode empty() {
        var out=json.createObjectNode(); out.putArray("items"); out.put("fetchedAt",Instant.now().toString());
        var meta=out.putObject("meta"); meta.putArray("sites"); meta.putArray("skipped"); meta.putArray("degradedSites");
        meta.putArray("warnings"); meta.put("partial",false).put("timedOut",false).put("vectorAvailable",false).put("cached",false).put("truncated",false);
        return out;
    }
    private void publish(ObjectNode out,Map<String,JsonNode> jobs,AtomicReference<ObjectNode> progress) {
        var items=out.putArray("items");
        var both=new ArrayList<JsonNode>(); var stored=new ArrayList<JsonNode>(); var remote=new ArrayList<JsonNode>();
        for(var job:jobs.values()) {
            if(job.path("retrievalSources").size()>1) both.add(job);
            else if("LOCAL_DB".equals(job.path("retrievalSource").asText())) stored.add(job);
            else remote.add(job);
        }
        both.stream().limit(30).forEach(items::add);
        for(int i=0;items.size()<30 && (i<stored.size() || i<remote.size());i++) {
            if(i<stored.size() && items.size()<30) items.add(stored.get(i));
            if(i<remote.size() && items.size()<30) items.add(remote.get(i));
        }
        var meta=out.withObject("/meta"); meta.put("total",items.size());
        meta.put("truncated",meta.path("truncated").asBoolean() || jobs.size()>30);
        var sites=new LinkedHashSet<String>(); meta.path("sites").forEach(s->sites.add(s.asText()));
        jobs.values().forEach(j->sites.add(j.path("site").asText())); var list=meta.putArray("sites"); sites.forEach(list::add);
        progress.set(out.deepCopy());
    }
    private static void mark(ObjectNode out,String warning,boolean timedOut) {
        var meta=out.withObject("/meta"); meta.put("partial",true); if(timedOut) meta.put("timedOut",true);
        var warnings=meta.withArray("warnings"); boolean exists=false; for(var w:warnings) exists|=w.asText().equals(warning); if(!exists) warnings.add(warning);
    }
    private static ObjectNode partial(ObjectNode progress,String reason,boolean timeout,long start) {
        var result=progress.deepCopy(); mark(result,reason,timeout);
        result.withObject("/meta").put("durationMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)); return result;
    }
}
