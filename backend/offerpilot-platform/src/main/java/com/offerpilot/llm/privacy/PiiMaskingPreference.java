package com.offerpilot.llm.privacy;

/** Identity-owned preference; platform never queries identity tables. */
public interface PiiMaskingPreference {
    boolean enabled(String verifiedOwner);
}
