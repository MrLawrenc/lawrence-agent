package com.agentmonitor.model.jvm;
/** Process identity without desktop properties. */
public record JvmProcess(String pid, String displayName) {
    public String getPid() { return pid; }
    public String getDisplayName() { return displayName; }
    @Override public String toString() { return pid + " - " + displayName; }
}
