-- 模型偏好归属访客；上传和重试时复制路由，避免设置变更影响已有队列。
CREATE TABLE resume_model_preferences (
  user_id text PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  text_tier text NOT NULL,
  vision_tier text NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE resumes ADD COLUMN text_model_tier text;
ALTER TABLE resumes ADD COLUMN vision_model_tier text;
