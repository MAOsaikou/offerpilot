package com.offerpilot.career.application;
import java.util.List;
/** Read-only, owner-checked snapshots. No model/network calls during reconciliation. */
public interface ProfileEvidenceSource {
    record Observation(String type,String sourceId,String revision,List<String> skills,List<String> facts) {}
    List<Observation> read(String owner);
    List<String> owners(String after,int limit);
}
