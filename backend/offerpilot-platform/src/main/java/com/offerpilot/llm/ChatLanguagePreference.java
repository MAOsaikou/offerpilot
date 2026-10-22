package com.offerpilot.llm;

/** Account-owned preference exposed to chat without a dependency on identity storage. */
public interface ChatLanguagePreference {
    String language(String owner);
    default String instruction(String owner) {
        return "en-US".equals(language(owner))
                ? "Reply in English. Preserve verbatim citations, proper names, code and machine-readable JSON keys. Safety and evidence rules still apply."
                : "请使用简体中文回复；原文引用、专有名词、代码和 JSON 键保持原样。继续遵守安全与证据规则。";
    }
}
