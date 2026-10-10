package com.offerpilot.interview.application;

import com.offerpilot.common.error.BusinessException;
import com.offerpilot.common.error.ErrorCode;
import com.offerpilot.common.id.IdGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Owner-confirmed STAR answers for reuse in later interview practice. Never auto-writes. */
@Service
public class StarStoryService {
    public static final int CAP = 30;
    public static final int MIN_STAR = 3;
    private static final Pattern TOKENS = Pattern.compile("\\p{IsHan}+|[\\p{L}\\p{N}&&[^\\p{IsHan}]]+", Pattern.UNICODE_CHARACTER_CLASS);

    public record Candidate(int questionIndex, String questionText, String title, String body, String role,
                            int starCount, boolean situation, boolean task, boolean action, boolean result,
                            boolean alreadySaved) {}
    public record Story(String id, String title, String body, String role, String sourceSessionId,
                        Integer sourceQuestionIndex, int starCount, boolean situation, boolean task,
                        boolean action, boolean result, String createdAt, String updatedAt) {}
    public record Inventory(List<Story> items, int limit) {}
    public record Citation(String id, String title, String role, int starCount, String excerpt) {}

    private final JdbcTemplate jdbc;
    private final InterviewService interviews;

    public StarStoryService(JdbcTemplate jdbc, InterviewService interviews) {
        this.jdbc = jdbc;
        this.interviews = interviews;
    }

    @Transactional(readOnly = true)
    public Inventory list(String owner) {
        return new Inventory(jdbc.query("""
                SELECT * FROM star_stories WHERE user_id=? ORDER BY updated_at DESC, id
                """, StarStoryService::map, owner), CAP);
    }

    @Transactional(readOnly = true)
    public List<Candidate> candidates(String owner, String sessionId) {
        var session = interviews.get(owner, sessionId);
        var out = new ArrayList<Candidate>();
        for (var answer : session.answers()) {
            String body = answer.answerText() == null ? "" : answer.answerText().strip();
            if (body.isEmpty()) continue;
            var star = InterviewRubric.detect(body);
            if (star.count() < MIN_STAR) continue;
            boolean saved = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM star_stories WHERE user_id=? AND source_session_id=? AND source_question_index=?)
                    """, Boolean.class, owner, session.id(), answer.questionIndex()))
                    || Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM star_stories WHERE user_id=? AND normalized_body=?)
                    """, Boolean.class, owner, normalize(body)));
            out.add(new Candidate(answer.questionIndex(), answer.questionText(), titleOf(answer.questionText(), body),
                    body, session.role(), star.count(), star.situation(), star.task(), star.action(), star.result(), saved));
        }
        return List.copyOf(out);
    }

    /** Persist only after explicit UI/approval; re-read answer from DB and re-verify STAR. */
    @Transactional
    public Story saveFromSession(String owner, String sessionId, int questionIndex) {
        if (questionIndex < 0 || questionIndex >= InterviewService.QUESTIONS.size()) invalid("题目序号无效");
        lockOwner(owner);
        var session = interviews.get(owner, sessionId);
        var answer = session.answers().stream().filter(a -> a.questionIndex() == questionIndex).findFirst()
                .orElseThrow(() -> invalid("题目不存在"));
        String body = answer.answerText() == null ? "" : answer.answerText().strip();
        if (body.isEmpty()) throw invalid("该题尚未作答，无法入库");
        var star = InterviewRubric.detect(body);
        if (star.count() < MIN_STAR) throw invalid("STAR 线索不足 3 项，请先补全回答再保存");
        String normalized = normalize(body);
        var bySource = jdbc.query("""
                SELECT * FROM star_stories WHERE user_id=? AND source_session_id=? AND source_question_index=?
                """, StarStoryService::map, owner, session.id(), questionIndex);
        if (!bySource.isEmpty()) return bySource.get(0);
        var byBody = jdbc.query("SELECT * FROM star_stories WHERE user_id=? AND normalized_body=?",
                StarStoryService::map, owner, normalized);
        if (!byBody.isEmpty()) return byBody.get(0);
        int count = jdbc.queryForObject("SELECT count(*) FROM star_stories WHERE user_id=?", Integer.class, owner);
        if (count != null && count >= CAP) throw conflict("最多保存 " + CAP + " 条 STAR 故事，请先删除旧条目");
        String id = IdGenerator.next("star");
        String title = titleOf(answer.questionText(), body);
        String document = fulltext(title + "\n" + session.role() + "\n" + body);
        jdbc.update("""
                INSERT INTO star_stories(id,user_id,title,body,role,source_session_id,source_question_index,
                star_count,situation,task,action,result,normalized_body,tsv)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,to_tsvector('simple',?))
                """, id, owner, title, body, session.role(), session.id(), questionIndex, star.count(),
                star.situation(), star.task(), star.action(), star.result(), normalized, document);
        return get(owner, id);
    }

    @Transactional
    public void delete(String owner, String id) {
        lockOwner(owner);
        if (jdbc.update("DELETE FROM star_stories WHERE user_id=? AND id=?", owner, id) == 0) throw missing();
    }

    @Transactional
    public void clear(String owner) {
        lockOwner(owner);
        jdbc.update("DELETE FROM star_stories WHERE user_id=?", owner);
    }

    @Transactional(readOnly = true)
    public List<Citation> recall(String owner, String query, int limit) {
        int size = Math.min(Math.max(limit, 1), 5);
        String q = lexicalQuery(query == null ? "" : query);
        if (q.isBlank()) {
            return jdbc.query("""
                    SELECT * FROM star_stories WHERE user_id=? ORDER BY updated_at DESC, id LIMIT ?
                    """, (rs, row) -> cite(map(rs, row)), owner, size);
        }
        return jdbc.query("""
                SELECT *, ts_rank_cd(tsv, to_tsquery('simple',?)) AS score,
                       CASE WHEN lower(role)=lower(?) THEN 1 ELSE 0 END AS role_hit
                FROM star_stories WHERE user_id=? AND (tsv @@ to_tsquery('simple',?) OR lower(role)=lower(?))
                ORDER BY role_hit DESC, score DESC, updated_at DESC, id LIMIT ?
                """, (rs, row) -> cite(map(rs, row)), q, query == null ? "" : query.strip(), owner, q,
                query == null ? "" : query.strip(), size);
    }

    private Story get(String owner, String id) {
        var rows = jdbc.query("SELECT * FROM star_stories WHERE user_id=? AND id=?", StarStoryService::map, owner, id);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    private void lockOwner(String owner) {
        if (jdbc.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE", String.class, owner).isEmpty())
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    private static Story map(ResultSet rs, int row) throws SQLException {
        Integer q = rs.getObject("source_question_index") == null ? null : rs.getInt("source_question_index");
        return new Story(rs.getString("id"), rs.getString("title"), rs.getString("body"), rs.getString("role"),
                rs.getString("source_session_id"), q, rs.getInt("star_count"), rs.getBoolean("situation"),
                rs.getBoolean("task"), rs.getBoolean("action"), rs.getBoolean("result"),
                rs.getTimestamp("created_at").toInstant().toString(),
                rs.getTimestamp("updated_at").toInstant().toString());
    }

    private static Citation cite(Story story) {
        String body = story.body();
        int end = body.offsetByCodePoints(0, Math.min(240, body.codePointCount(0, body.length())));
        String excerpt = body.substring(0, end) + (end < body.length() ? "…" : "");
        return new Citation(story.id(), story.title(), story.role(), story.starCount(), excerpt);
    }

    static String titleOf(String question, String body) {
        String source = body.codePointCount(0, body.length()) >= 12 ? body : (question == null ? body : question);
        source = source.replaceAll("\\s+", " ").strip();
        int end = source.offsetByCodePoints(0, Math.min(48, source.codePointCount(0, source.length())));
        String title = source.substring(0, end).strip();
        return title.isEmpty() ? "STAR 故事" : title;
    }

    public static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("(?U)\\s+", " ").strip();
    }

    static String fulltext(String document) {
        return document + "\n" + String.join(" ", lexicalTokens(document));
    }

    static String lexicalQuery(String query) {
        return lexicalTokens(query).stream().distinct().map(token -> "'" + token + "'")
                .collect(java.util.stream.Collectors.joining(" | "));
    }

    static List<String> lexicalTokens(String text) {
        List<String> tokens = new ArrayList<>();
        var matcher = TOKENS.matcher(text.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            String word = matcher.group();
            int[] points = word.codePoints().toArray();
            if (Character.UnicodeScript.of(points[0]) == Character.UnicodeScript.HAN) {
                if (points.length == 1) tokens.add(word);
                for (int n = 1; n < points.length; n++) tokens.add(new String(points, n - 1, 2));
            } else tokens.add(word);
        }
        return tokens;
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.VALIDATION_FAILED, message);
    }
    private static BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.STAR_STORY_CONFLICT, message);
    }
    private static BusinessException missing() {
        return new BusinessException(ErrorCode.STAR_STORY_NOT_FOUND, "STAR 故事不存在");
    }
}
