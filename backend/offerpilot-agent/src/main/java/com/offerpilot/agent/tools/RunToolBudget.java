package com.offerpilot.agent.tools;
import java.util.concurrent.atomic.AtomicInteger;
/** One per chat run, shared by plan tools and the optional memory tool. */
public final class RunToolBudget {
    public static final String KEY=RunToolBudget.class.getName();
    private final int limit;
    private final AtomicInteger used=new AtomicInteger();
    public RunToolBudget(int limit) { this.limit=Math.max(1,Math.min(8,limit)); }
    public boolean acquire() {
        while(true) {
            int previous=used.get();
            if(previous>=limit) return false;
            if(used.compareAndSet(previous,previous+1)) return true;
        }
    }
}
