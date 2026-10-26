package com.agentmonitor.app.service;
import java.util.List;
import java.util.Map;
/** In-memory preview adapter, with no filesystem writes. */
public final class JvmClassIndexCache {
    public JvmClassIndexCache(String pid) {}
    public List<String> readClasses() { return JvmService.listLoadedClasses(""); }
    public Map<String, List<String>> readMethods() { return JvmService.listClassMethods(""); }
    public void writeClasses(List<String> ignored) {}
    public void writeMethods(Map<String, List<String>> ignored) {}
}
