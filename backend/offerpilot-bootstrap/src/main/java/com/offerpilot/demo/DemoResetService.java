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
    private final DemoAccountBootstrap accounts;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ResumeObjectStore storage;
    private final JsonNode journey;
    private final com.offerpilot.knowledge.application.KnowledgeNoteService notes;
    private final com.offerpilot.memory.application.UserMemoryService memories;

    public DemoResetService(DemoAccountBootstrap accounts, JdbcTemplate jdbc, PlatformTransactionManager transactions,
                            ResumeObjectStore storage, ObjectMapper json, com.offerpilot.knowledge.application.KnowledgeNoteService notes, com.offerpilot.memory.application.UserMemoryService memories) throws Exception {
        this.notes = notes; this.memories = memories;
        this.accounts = accounts;
        this.jdbc = jdbc;
        transaction = new TransactionTemplate(transactions);
        this.storage = storage;
        try (var input = new ClassPathResource("demo/journey.json").getInputStream()) {
            journey = json.readTree(input);
        }
        journey.path("resumes").forEach(resume -> ResumeStructure.validate(resume.path("structured")));
    }

    public record Summary(Instant resetAt, int resumeCount, int jobCount, int applicationCount,
                          int interviewSessionCount, int careerProfileCount, int growthTaskCount, int knowledgeNoteCount, int memoryCount) {}
    private record StoredFile(String bucket, String key) {}

    public Summary reset() {
        return seed(accounts.requireAccount(), false);
    }

    public void seedIfEmpty(String userId) {
        seed(userId, true);
    }

    private Summary seed(String userId, boolean onlyIfEmpty) {
        List<StoredFile> obsolete = new java.util.ArrayList<>();
        Summary result = transaction.execute(status -> {
            // Serializes startup/manual/scheduled resets across instances. Never accepts a real user.
            var demo = jdbc.queryForList("SELECT id FROM users WHERE id=? AND is_demo=true FOR UPDATE", String.class, userId);
            if (!accounts.enabled() || demo.isEmpty())
                throw new BusinessException(ErrorCode.DEMO_NOT_ALLOWED, "体验账号未启用");
            if (onlyIfEmpty && jdbc.queryForObject("SELECT count(*) FROM resumes WHERE user_id=?", Integer.class, userId) > 0)
                return null;
            obsolete.addAll(jdbc.query("SELECT bucket,object_key FROM files WHERE user_id=?",
                    (rs, n) -> new StoredFile(rs.getString(1), rs.getString(2)), userId));
            clear(userId);
            insertResumes(userId);
            insertJobs();
            insertJourney(userId);
            return new Summary(Instant.now(), journey.path("resumes").size(), journey.path("jobs").size(),
                    journey.path("applications").size(), journey.path("interviews").size(), 1, journey.path("growthTasks").size(), journey.path("notes").size(), 3);
        });
        // Never remove source objects until the database replacement has committed.
        for (StoredFile file : obsolete) {
            if (jdbc.queryForObject("SELECT count(*) FROM files WHERE bucket=? AND object_key=?",
                    Integer.class, file.bucket(), file.key()) > 0) continue;
            try { storage.remove(file.bucket(), file.key()); }
            catch (RuntimeException ex) { log.warn("Demo reset could not remove obsolete file object: {}", file.key()); }
        }
        return result;
    }

    private void clear(String userId) {
        // FK cascades remove answers, versions, messages, agent steps/calls/approvals/events.
        for (String table : List.of("user_memories", "knowledge_note_imports", "knowledge_notes", "interview_sessions", "applications", "job_matches", "growth_task_completions",
                "career_profiles", "resume_optimizations", "resume_patch_batches", "resume_diagnoses", "conversations")) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", userId);
        }
        jdbc.update("UPDATE resumes SET current_version_id=NULL WHERE user_id=?", userId);
        jdbc.update("DELETE FROM resumes WHERE user_id=?", userId);
        jdbc.update("DELETE FROM files WHERE user_id=?", userId);
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
            byte[] bytes = document(plain);
            String hash = sha256(bytes);
            // Immutable source keys keep the old source intact if a later insert/upload fails.
            String key = "demo/" + userId + "/resume-" + i + "-" + hash + ".docx";
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
            jdbc.update("INSERT INTO applications(id,user_id,job_id,status,note) VALUES (?,?,?,?,?)",
                    "app_demo_" + userId + "_" + (++i), userId, jobId, application.path("status").asText(), application.path("note").asText());
        }
        i = 0;
        for (JsonNode interview : journey.path("interviews")) {
            String id = "int_demo_" + userId + "_" + (++i);
            jdbc.update("INSERT INTO interview_sessions(id,user_id,role,kind,question_index,completed) VALUES (?,?,?,?,?,?)",
                    id, userId, interview.path("role").asText(), interview.path("kind").asText(),
                    interview.path("questionIndex").asInt(), interview.path("completed").asBoolean());
            int q = 0;
            for (JsonNode answer : interview.path("answers")) {
                jdbc.update("INSERT INTO interview_answers(session_id,question_index,question_text,answer_text) VALUES (?,?,?,?)",
                        id, q++, answer.path("question").asText(), answer.path("answer").asText());
            }
        }
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
