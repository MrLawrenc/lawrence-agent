package com.agentmonitor.cli;
import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.service.JvmClassIndexCache;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class WorkflowCommandsTest {
    @TempDir Path root;
    private final ObjectMapper json=new ObjectMapper();
    private record Run(int code,JsonNode document,String error){}
    private Run run(String... args) throws Exception {var out=new StringWriter();var err=new StringWriter();int code=new MonitorCli().run(args,new PrintWriter(out),new PrintWriter(err));try(var parser=json.getFactory().createParser(out.toString())){JsonNode doc=json.readTree(parser);assertNotNull(doc);assertNull(parser.nextToken(),"stdout contains exactly one JSON result");return new Run(code,doc,err.toString());}}
    @Test void createsSafeDraftOrExplicitScopeAndRefusesOverwrite() throws Exception {
        Path config=root.resolve("monitor.yml");var draft=run("config","init","--output",config.toString());assertEquals(0,draft.code);assertFalse(draft.document.path("validForCapture").asBoolean());
        assertEquals(2,run("config","validate","--config",config.toString()).code);byte[] before=Files.readAllBytes(config);assertEquals(2,run("config","init","--output",config.toString(),"--package","example").code);assertArrayEquals(before,Files.readAllBytes(config));
        var ready=root.resolve("ready.yml");assertEquals(0,run("config","init","--output",ready.toString(),"--package","example.api,example.service").code);
        var validated=run("config","validate","--config",ready.toString());assertEquals(0,validated.code);assertTrue(validated.document.path("valid").asBoolean());assertEquals(2,validated.document.path("scope").path("includePackages").size());assertTrue(validated.document.has("diagnostics"));
    }
    @Test void previewsBoundedCandidatesUsingTheSameSelectionContractWithoutCapturing() throws Exception {
        Process target=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-cp",Path.of("build/classes/java/test").toAbsolutePath().toString(),"agentfixtures.clipipeline.TargetLoop").start();
        String previous=System.getProperty("agent.monitor.cacheDir");System.setProperty("agent.monitor.cacheDir",root.resolve("cache").toString());
        try {
            assertEquals("READY",new BufferedReader(new InputStreamReader(target.getInputStream())).readLine());var cache=new JvmClassIndexCache(Long.toString(target.pid()));
            cache.writeClasses(List.of("example.A","example.B","example.DTO","other.C"));cache.writeMethods(Map.of("example.A",List.of("getName","work")));
            Path config=root.resolve("monitor.yml");run("config","init","--output",config.toString(),"--package","example");
            var result=run("scope","preview","--pid",Long.toString(target.pid()),"--config",config.toString(),"--package","example","--search","example.A","--limit","1");
            assertEquals(0,result.code,result.error);assertEquals(2,result.document.path("globalSelectedClasses").asInt());assertEquals(3,result.document.path("packageClasses").asInt());assertEquals(1,result.document.path("matchedClasses").asInt());assertFalse(result.document.path("selectedMethodIndexClassCoverageComplete").asBoolean());assertEquals(1,result.document.path("classes").size());
            assertEquals(2,run("scope","preview","--pid",Long.toString(target.pid()),"--config",config.toString(),"--limit","501").code);
            assertTrue(target.isAlive());
        } finally {if(previous==null)System.clearProperty("agent.monitor.cacheDir");else System.setProperty("agent.monitor.cacheDir",previous);target.destroy();if(!target.waitFor(3,TimeUnit.SECONDS))target.destroyForcibly();}
    }
    @Test void sessionCommandsAreReadOnlyAndAiInvalidDataHasItsOwnExitCode() throws Exception {
        var session=MonitoringSession.create(root.resolve("captures").toString());byte[] marker=Files.readAllBytes(session.sessionDirectory().resolve("session.json"));
        assertEquals(1,run("session","list","--root",root.resolve("captures").toString()).document.path("sessions").size());
        var shown=run("session","show","--session",session.sessionDirectory().toString());assertEquals("NOT_FINALIZED",shown.document.path("status").asText());assertTrue(shown.document.path("readOnly").asBoolean());
        Path bad=root.resolve("bad.json");Files.writeString(bad,"{}");var rejected=run("ai","verify","--session",session.sessionDirectory().toString(),"--response",bad.toString());assertEquals(6,rejected.code);assertFalse(rejected.document.path("valid").asBoolean());
        assertArrayEquals(marker,Files.readAllBytes(session.sessionDirectory().resolve("session.json")));
    }    @Test void nestedHelpDoesNotCreateTheRequestedConfiguration() throws Exception {
        Path target=root.resolve("never-created.yml");var out=new StringWriter();var err=new StringWriter();
        assertEquals(0,new MonitorCli().run(new String[]{"config","init","--output",target.toString(),"--help"},new PrintWriter(out),new PrintWriter(err)));
        assertTrue(out.toString().contains("scope preview"));assertFalse(Files.exists(target));
    }

}
