package com.offerpilot.privacy;
import java.util.Set;
/** V1–V48 owner tables erased by DELETE users via ON DELETE CASCADE; migrations are checked in tests. */
public final class UserDataInventory {
    private UserDataInventory() {}
    public static final Set<String> CASCADE_TABLES = Set.of("memory_candidates","career_questionnaires","llm_usage_logs","notifications","career_target_plans","career_profile_suggestions","github_evidence","job_daily_recommendations","generation_tasks","user_memories","user_preferences","refresh_tokens","user_integrations","user_mcp_servers","guest_sessions","conversations","messages","agent_runs","approvals","files","resumes","resume_versions","resume_model_preferences","resume_diagnoses","resume_patch_batches","resume_optimizations","job_matches","applications","interview_sessions","interview_learning_plans","star_stories","career_profiles","career_recommendation_revisions","career_assessments","growth_task_completions","knowledge_notes","knowledge_note_imports","jobs");
}
