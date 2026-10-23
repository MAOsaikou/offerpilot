-- Only numeric windows are persisted; no frames, landmarks or video.
CREATE TABLE interview_metrics (
    id text PRIMARY KEY,
    session_id text NOT NULL REFERENCES interview_sessions(id) ON DELETE CASCADE,
    window_start timestamptz NOT NULL,
    window_end timestamptz NOT NULL,
    eye_contact numeric(4,3) NOT NULL CHECK (eye_contact BETWEEN 0 AND 1),
    smile numeric(4,3) NOT NULL CHECK (smile BETWEEN 0 AND 1),
    head_stability numeric(4,3) NOT NULL CHECK (head_stability BETWEEN 0 AND 1),
    posture_stability numeric(4,3) NOT NULL CHECK (posture_stability BETWEEN 0 AND 1),
    samples smallint NOT NULL CHECK (samples > 0),
    CHECK (window_end >= window_start)
);
CREATE INDEX idx_interview_metrics_session ON interview_metrics(session_id, window_start);
