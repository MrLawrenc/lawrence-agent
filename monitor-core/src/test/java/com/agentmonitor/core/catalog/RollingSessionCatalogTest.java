package com.agentmonitor.core.catalog;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class RollingSessionCatalogTest {
    @TempDir Path root;
    @Test void resolvesMovedWindowsByIdentityAndPreservesPartialBoundary()throws Exception{
        Files.writeString(root.resolve("session.json"),"{\"sessionId\":\"master\"}");Files.writeString(root.resolve("rolling-capture.json"),"{\"sessionId\":\"master\",\"status\":\"COMPLETED\",\"windows\":[{\"sessionId\":\"child\",\"sessionDirectory\":\"/old/machine/capture\",\"sequence\":1}]}");
        Path child=Files.createDirectories(root.resolve("windows/time/session-child"));Files.writeString(child.resolve("session.json"),"{\"sessionId\":\"child\"}");Files.writeString(child.resolve("window.json"),"{\"sessionId\":\"child\",\"sourceSessionId\":\"master\"}");
        var data=new RollingSessionCatalog().list(root);var rows=(java.util.List<?>)data.get("windows");assertEquals(1,rows.size());var row=(java.util.Map<?,?>)rows.get(0);assertEquals(child.toRealPath().toString(),row.get("sessionDirectory"));assertEquals(true,row.get("partialEvidence"));
        Files.writeString(child.resolve("window.json"),"{\"sessionId\":\"child\",\"sourceSessionId\":\"foreign\"}");var invalid=(java.util.Map<?,?>)((java.util.List<?>)new RollingSessionCatalog().list(root).get("windows")).get(0);assertEquals("UNREADABLE",invalid.get("status"));assertFalse(invalid.containsKey("sessionDirectory"));
    }
}
