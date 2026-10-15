-- IV-02: one interviewer follow-up per main question, generated from the saved answer.
ALTER TABLE interview_answers
    ADD COLUMN follow_up_question text CHECK (follow_up_question IS NULL OR char_length(follow_up_question) BETWEEN 1 AND 200),
    ADD COLUMN follow_up_source text CHECK (follow_up_source IS NULL OR follow_up_source IN ('model', 'rule')),
    ADD COLUMN follow_up_answer text CHECK (follow_up_answer IS NULL OR char_length(follow_up_answer) <= 12000),
    ADD CONSTRAINT interview_follow_up_source_pair CHECK ((follow_up_question IS NULL) = (follow_up_source IS NULL));
