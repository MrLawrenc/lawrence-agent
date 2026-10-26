package com.agentmonitor.app.model;
public record SessionRetentionPolicy(int maxSessions) {
    public SessionRetentionPolicy { if (maxSessions < 0) throw new IllegalArgumentException("maxSessions 不能小于 0"); }
    public static SessionRetentionPolicy disabled() { return new SessionRetentionPolicy(0); }
}
