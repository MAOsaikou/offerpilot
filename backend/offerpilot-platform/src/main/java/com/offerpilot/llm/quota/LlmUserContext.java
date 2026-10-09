package com.offerpilot.llm.quota;

import java.util.function.Supplier;
import reactor.util.context.Context;

/** Verified application owner only, never a client-supplied user ID. */
public final class LlmUserContext {
    public static final String KEY = LlmUserContext.class.getName();
    private static final ThreadLocal<String> OWNER = new ThreadLocal<>();
    private LlmUserContext() {}
    public static String current() {
        if (OWNER.get() != null) return OWNER.get();
        var request = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        return request == null ? null : (String) request.getAttribute(KEY,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
    }
    public static void verifiedRequest(String owner) {
        var request = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (request != null) request.setAttribute(KEY, owner,
                org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);
    }
    public static Context withUser(Context context, String owner) { return context.put(KEY, owner); }

    /** Bridges blocking workers and nested block() calls; always restores the previous scope. */
    public static <T> T asUser(String owner, Supplier<T> action) {
        String previous = OWNER.get();
        OWNER.set(owner);
        try { return action.get(); }
        finally { if (previous == null) OWNER.remove(); else OWNER.set(previous); }
    }
}
