package com.offerpilot.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.offerpilot.auth.application.DemoAccountBootstrap;
import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.resume.application.ResumeObjectStore;
import com.offerpilot.resume.application.ResumeStructure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Bootstrap-level JDBC fixture orchestration; business modules have no reverse dependency. */
@Service
public class DemoResetService {
    private static final Logger log = LoggerFactory.getLogger(DemoResetService.class);
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final ObjectMapper json;
    private final com.offerpilot.resume.application.ResumePatchService patches;
    private final com.offerpilot.common.artifact.JobArtifacts artifacts;
    private final DemoAccountBootstrap accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ResumeObjectStore storage;
    private final JsonNode journey;
    private final List<SourceDocument> sources;
    private final com.offerpilot.knowledge.application.KnowledgeNoteService notes;
    private final com.offerpilot.memory.application.UserMemoryService memories;

    public DemoResetService(DemoAccountBootstrap accounts, JdbcTemplate jdbc, PlatformTransactionManager transactions,
                            ResumeObjectStore storage, ObjectMapper json, com.offerpilot.knowledge.application.KnowledgeNoteService notes, com.offerpilot.memory.application.UserMemoryService memories, com.offerpilot.common.artifact.JobArtifacts artifacts, com.offerpilot.resume.application.ResumePatchService patches) throws Exception {
        this.json=json; this.patches=patches; this.artifacts=artifacts;
        this.notes = notes; this.memories = memories;
        this.accounts = accounts;
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(transactions);
        this.storage = storage;
        try (var input = new ClassPathResource("demo/journey.json").getInputStream()) {
            journey = json.readTree(input);
        }
        var cached = new java.util.ArrayList<SourceDocument>();
        journey.path("resumes").forEach(resume -> {
            ResumeStructure.validate(resume.path("structured"));
            byte[] bytes = document(plainText(resume.path("structured")));
            cached.add(new SourceDocument(bytes, sha256(bytes)));
        });
        sources = List.copyOf(cached);
    }

    public record Summary(Instant resetAt, int resumeCount, int jobCount, int applicationCount,
                          int interviewSessionCount, int careerProfileCount, int growthTaskCount, int knowledgeNoteCount, int memoryCount) {}
    private record StoredFile(String bucket, String key) {}
    private record SourceDocument(byte[] bytes, String hash) {}

    public Summary reset() {
        return seed(accounts.requireAccount(), false);
    }

    public void seedIfEmpty(String userId) {
        seed(userId, true);
    }

    private Summary seed(String userId, boolean onlyIfEmpty) {
        List<StoredFile> obsolete = new java.util.ArrayList<>();
        // Durable reservations precede S3 PUT, so process death/rollback cannot orphan uploads.
        // Existing linked sources are protected; stale receipts are discarded by privacy cleanup.
        if (!accounts.enabled() || jdbc.queryForObject("SELECT count(*) FROM users WHERE id=? AND is_demo=true", Integer.class, userId) == 0)
            throw new BusinessException(ErrorCode.DEMO_NOT_ALLOWED, "体验账号未启用");
        List<String> reservations = new java.util.ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            String key = sourceKey(userId, i + 1, sources.get(i));
            if (jdbc.queryForObject("SELECT count(*) FROM files WHERE bucket=? AND object_key=?", Integer.class, storage.bucket(), key) > 0) continue;
            String receipt = com.offerpilot.common.id.IdGenerator.next("cleanup");
            jdbc.update("""
                    INSERT INTO account_data_cleanup(id,kind,bucket,object_key,next_attempt_at)
                    SELECT ?,'OBJECT',?,?,COALESCE(demo_expires_at,now()+interval '26 hours') FROM users WHERE id=?
                    """, receipt, storage.bucket(), key, userId);
            reservations.add(receipt);
        }
        Summary result;
        try { result = transaction.execute(status -> {
            // Serializes startup/manual/scheduled resets across instances. Never accepts a real user.
            var demo = jdbc.queryForList("SELECT id FROM users WHERE id=? AND is_demo=true FOR UPDATE", String.class, userId);
            if (!accounts.enabled() || demo.isEmpty())
                throw new BusinessException(ErrorCode.DEMO_NOT_ALLOWED, "体验账号未启用");
            if (onlyIfEmpty && jdbc.queryForObject("SELECT count(*) FROM resumes WHERE user_id=?", Integer.class, userId) > 0)
                return null;
            obsolete.addAll(jdbc.query("SELECT bucket,object_key FROM files WHERE user_id=?",
                    (rs, n) -> new StoredFile(rs.getString(1), rs.getString(2)), userId));
            for (StoredFile file : obsolete) jdbc.update(
                    "INSERT INTO account_data_cleanup(id,kind,bucket,object_key) VALUES (?,'OBJECT',?,?)",
                    com.offerpilot.common.id.IdGenerator.next("cleanup"), file.bucket(), file.key());
            clear(userId);
            insertResumes(userId);
            insertJobs();
            insertJourney(userId);
            return new Summary(Instant.now(), journey.path("resumes").size(), journey.path("jobs").size(),
                    journey.path("applications").size(), journey.path("interviews").size(), 1, journey.path("growthTasks").size(), journey.path("notes").size(), 3);
        });
        } catch (RuntimeException ex) {
            for (String receipt : reservations)
                jdbc.update("UPDATE account_data_cleanup SET next_attempt_at=now() WHERE id=?", receipt);
            throw ex;
        }
        for (String receipt : reservations) jdbc.update("DELETE FROM account_data_cleanup WHERE id=?", receipt);
        // Never remove source objects until the database replacement has committed.
        for (StoredFile file : obsolete) {
            if (jdbc.queryForObject("SELECT count(*) FROM files WHERE bucket=? AND object_key=?",
                    Integer.class, file.bucket(), file.key()) > 0) continue;
            try {
                storage.remove(file.bucket(), file.key());
                jdbc.update("DELETE FROM account_data_cleanup WHERE kind='OBJECT' AND bucket=? AND object_key=?", file.bucket(), file.key());
            }
            catch (RuntimeException ex) { log.warn("Demo reset could not remove obsolete file object: {}", file.key()); }
        }
        return result;
    }

    private void clear(String userId) {
        // FK cascades remove answers, versions, messages, agent steps/calls/approvals/events.
        jdbc.update("DELETE FROM application_events WHERE application_id IN (SELECT id FROM applications WHERE user_id=?)", userId);
        for (String table : List.of("user_memories", "star_stories", "knowledge_note_imports", "knowledge_notes", "interview_sessions", "applications", "job_matches", "growth_task_completions", "career_assessments",
                "career_profiles", "resume_optimizations", "resume_patch_batches", "resume_diagnoses", "conversations")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", userId);
        }
        jdbc.update("UPDATE resumes SET current_version_id=NULL WHERE user_id=?", userId);
        jdbc.update("DELETE FROM resumes WHERE user_id=?", userId);
        jdbc.update("DELETE FROM files WHERE user_id=?", userId);
        // Jobs the previous visitor imported (JB-06); their applications/matches were removed above.
        jdbc.update("DELETE FROM jobs WHERE user_id=?", userId);
        // Demo jobs are public snapshots. Preserve them and any other user's references; never wipe MCP cache.
    }

    private void insertResumes(String userId) {
        int i = 0;
        for (JsonNode resume : journey.path("resumes")) {
            String suffix = userId + "_" + (++i);
            String id = "rsm_demo_" + suffix, version = "rsv_demo_" + suffix, file = "file_demo_" + suffix;
            String title = resume.path("title").asText();
            JsonNode structured = resume.path("structured");
            String plain = plainText(structured);
            SourceDocument source = sources.get(i - 1);
            byte[] bytes = source.bytes();
            String hash = source.hash();
            // Immutable source keys keep the old source intact if a later insert/upload fails.
            String key = sourceKey(userId, i, source);
            storage.put(key, bytes, DOCX);
            jdbc.update("""
                    INSERT INTO files(id,user_id,purpose,bucket,object_key,name,mime,size,sha256)
                    VALUES (?,?,'RESUME',?,?,?,?,?,?)
                    """, file, userId, storage.bucket(), key, title + ".docx", DOCX, bytes.length, hash);
            jdbc.update("""
                    INSERT INTO resumes(id,user_id,title,source_file_id,parse_status,parse_progress,text_model_tier)
                    VALUES (?,?,?,?,'READY',100,'FLASH')
                    """, id, userId, title, file);
            jdbc.update("""
                    INSERT INTO resume_versions(id,resume_id,user_id,version_no,source,structured,plain_text,parse_meta)
                    VALUES (?,?,?,1,'UPLOAD',?::jsonb,?, '{"channel":"demo","fictional":true}')
                    """, version, id, userId, structured.toString(), plain);
            jdbc.update("UPDATE resumes SET current_version_id=? WHERE id=?", version, id);
        }
    }

    private static String sourceKey(String userId, int index, SourceDocument source) {
        return "demo/" + userId + "/resume-" + index + "-" + source.hash() + ".docx";
    }

    private void insertJobs() {
        int i = 0;
        for (JsonNode job : journey.path("jobs")) {
            jdbc.update("""
                    INSERT INTO jobs(id,site,external_id,nature,payload,detail_fetched_at)
                    VALUES (?,'demo',?,?,?::jsonb,now())
                    ON CONFLICT(site,external_id,nature) DO UPDATE SET payload=EXCLUDED.payload,detail_fetched_at=now(),updated_at=now()
                    """, "job_demo_" + (++i), job.path("externalId").asText(), job.path("nature").asText(), job.toString());
        }
    }

    private void insertJourney(String userId) {
        int i = 0;
        for (JsonNode application : journey.path("applications")) {
            JsonNode job = journey.path("jobs").get(application.path("jobIndex").asInt());
            String jobId = jdbc.queryForObject("SELECT id FROM jobs WHERE site='demo' AND external_id=? AND nature=?",
                    String.class, job.path("externalId").asText(), job.path("nature").asText());
            String appId = "app_demo_" + userId + "_" + (++i);
            jdbc.update("INSERT INTO applications(id,user_id,job_id,status,note,next_action,next_action_at) VALUES (?,?,?,?,?,?,current_date + ?::integer)",
                    appId, userId, jobId, application.path("status").asText(), application.path("note").asText(),
                    application.path("nextAction").asText(""),
                    application.hasNonNull("nextActionDayOffset") ? application.path("nextActionDayOffset").asInt() : null);
            jdbc.update("INSERT INTO application_events(id,application_id,kind,to_status,detail,actor,created_at) VALUES (?,?,'CREATED','TODO','加入求职看板（虚构演示）','SYSTEM',now()-interval '2 days')",
                    "evt_demo_create_"+appId, appId);
            if (!"TODO".equals(application.path("status").asText()))
                jdbc.update("INSERT INTO application_events(id,application_id,kind,from_status,to_status,detail,actor,created_at) VALUES (?,?,'STATUS_CHANGED','TODO',?,'演示阶段更新','SYSTEM',now()-interval '1 day')",
                        "evt_demo_status_"+appId, appId, application.path("status").asText());
            if (!application.path("nextAction").asText("").isBlank())
                jdbc.update("INSERT INTO application_events(id,application_id,kind,detail,actor) VALUES (?,?,'NEXT_ACTION_EDITED',?,'SYSTEM')",
                        "evt_demo_next_"+appId, appId, application.path("nextAction").asText());
        }
        i = 0;
        for (JsonNode interview : journey.path("interviews")) {
            String id = "int_demo_" + userId + "_" + (++i);
            JsonNode target=journey.path("jobs").get(interview.path("jobIndex").asInt());
            String jobId=jdbc.queryForObject("SELECT id FROM jobs WHERE site='demo' AND external_id=? AND nature=?",String.class,target.path("externalId").asText(),target.path("nature").asText());
            jdbc.update("INSERT INTO interview_sessions(id,user_id,role,kind,question_index,completed,job_id) VALUES (?,?,?,?,?,?,?)",
                    id, userId, interview.path("role").asText(), interview.path("kind").asText(),
                    interview.path("questionIndex").asInt(), interview.path("completed").asBoolean(),jobId);
            int q = 0;
            for (JsonNode answer : interview.path("answers")) {
                jdbc.update("INSERT INTO interview_answers(session_id,question_index,question_text,answer_text) VALUES (?,?,?,?)",
                        id, q++, answer.path("question").asText(), answer.path("answer").asText());
            }
        }
        String resumeId="rsm_demo_"+userId+"_1", versionId="rsv_demo_"+userId+"_1", optId="opt_demo_"+userId;
        String linkedJob=jdbc.queryForObject("SELECT job_id FROM applications WHERE user_id=? ORDER BY id LIMIT 1",String.class,userId);
        var demoPatches=json.createArrayNode();
        demoPatches.addObject().put("id","demo_summary").put("op","replace").put("path","/summary")
            .put("from",journey.path("resumes").get(0).path("structured").path("summary").asText())
            .put("value","关注后端开发与可靠数据服务的 2027 届计算机专业学生，希望在实习中参与真实业务、学习代码评审与工程实践。以上全部为演示虚构内容。")
            .put("reason","虚构演示：突出后端方向，保留原有事实；未自动采纳。")
            .put("needsUserConfirmation",true).put("confirmationHint","此为虚构夹具，实际使用请换成本人的真实经历。");
        var batch=patches.store(userId,resumeId,versionId,demoPatches);
        var demoReport=json.createObjectNode().put("summary","虚构演示：调整摘要顺序，突出岗位方向；建议尚未采纳。")
            .put("model","演示夹具（未调用模型）").put("modelTier","FLASH").put("promptVersion","demo-fixture")
            .put("durationMs",0).put("inputTokens",0).put("outputTokens",0);
        demoReport.set("patches",demoPatches);
        demoReport.set("stats",json.valueToTree(batch.stats()));
        demoReport.put("reportId",optId).put("resumeId",resumeId).put("resumeVersionId",versionId).put("versionNo",1).put("createdAt",Instant.now().toString()).put("batchId",batch.batchId());
        jdbc.update("INSERT INTO resume_optimizations(id,user_id,resume_id,version_id,request_id,status,model_tier,job_description,job_id,report) VALUES (?,?,?,?,?,'SUCCEEDED','FLASH',?,?,?::jsonb)",
            optId,userId,resumeId,versionId,"demo-rewrite-"+userId,"虚构演示岗位定向改写",linkedJob,demoReport.toString());
        jdbc.queryForList("SELECT job_id FROM applications WHERE user_id=?",String.class,userId).forEach(job -> artifacts.linkJob(userId,job));
        for (JsonNode note : journey.path("notes"))
            notes.create(userId, new com.offerpilot.knowledge.application.KnowledgeNoteService.Draft(
                    note.path("title").asText(), note.path("content").asText(), note.path("category").asText()));
        memories.settings(userId, true);
        memories.add(userId, new com.offerpilot.memory.application.UserMemoryService.Draft(
                com.offerpilot.memory.application.UserMemoryService.Kind.GOAL, "我的目标岗位是Java后端开发（虚构演示）"));
        memories.add(userId, new com.offerpilot.memory.application.UserMemoryService.Draft(
                com.offerpilot.memory.application.UserMemoryService.Kind.LOCATION, "我的目标城市是杭州（虚构演示）"));
        memories.add(userId, new com.offerpilot.memory.application.UserMemoryService.Draft(
                com.offerpilot.memory.application.UserMemoryService.Kind.SKILL, "我的技术栈是Java、Spring Boot、PostgreSQL（虚构演示）"));
        JsonNode career = journey.path("career");
        jdbc.update("INSERT INTO career_profiles(user_id,target,city,skills,interests) VALUES (?,?,?,?,?::jsonb)",
                userId, career.path("target").asText(), career.path("city").asText(), career.path("skills").asText(), career.path("interests").toString());
        for (JsonNode task : journey.path("growthTasks"))
            jdbc.update("INSERT INTO growth_task_completions(user_id,task_title) VALUES (?,?)", userId, task.asText());
    }

    private static String plainText(JsonNode structured) {
        StringBuilder text = new StringBuilder();
        appendText(structured, text);
        return text.toString();
    }

    private static void appendText(JsonNode node, StringBuilder text) {
        if (node.isTextual() && !node.asText().isBlank()) text.append(node.asText()).append('\n');
        else if (node.isContainerNode()) node.forEach(child -> appendText(child, text));
    }

    /** Real small DOCX source, so retry parsing does not point at a nonexistent storage object. */
    private static byte[] document(String text) {
        try (var output = new ByteArrayOutputStream(); var zip = new ZipOutputStream(output)) {
            String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            var entries = new String[][] {
                    {"[Content_Types].xml", "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>"},
                    {"_rels/.rels", "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>"},
                    {"word/document.xml", "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                            + "<w:p><w:r><w:t xml:space=\"preserve\">" + escaped.replace("\n", "</w:t><w:br/><w:t xml:space=\"preserve\">")
                            + "</w:t></w:r></w:p></w:body></w:document>"}
            };
            for (var entry : entries) {
                var zipEntry = new ZipEntry(entry[0]);
                zipEntry.setTime(0); // Deterministic bytes and content-addressed source keys.
                zip.putNextEntry(zipEntry);
                zip.write(entry[1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return output.toByteArray();
        } catch (Exception ex) { throw new IllegalStateException("Cannot create fictional demo source", ex); }
    }

    private static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
}
