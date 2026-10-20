package com.offerpilot.artifact;

import com.offerpilot.interview.application.InterviewQuestionReference;
import com.offerpilot.knowledge.application.BuiltinQuestionReferences;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class InterviewQuestionBankBridge implements InterviewQuestionReference {
    private final BuiltinQuestionReferences references;
    public InterviewQuestionBankBridge(BuiltinQuestionReferences references) { this.references=references; }
    @Override public List<Reference> find(String role,String industry,String difficulty) {
        return references.find(role,industry,difficulty).stream().map(r -> new Reference(r.id(),r.text(),r.type(),r.difficulty(),r.source(),r.notice())).toList();
    }
}
