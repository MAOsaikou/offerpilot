package com.offerpilot.interview.application;

import java.util.List;

/** Public practice references, never candidate facts or authentic interview experiences. */
public interface InterviewQuestionReference {
    record Reference(String id,String text,String type,String difficulty,String source,String notice) {}
    List<Reference> find(String role,String industry,String difficulty);
}
