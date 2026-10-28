package com.agentmonitor.core.catalog;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Read-only bounded session metadata browsing; never replays, rewrites or prunes captures. */
public final class SessionCatalog {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY);
    private static final Set<String> SUMMARY=Set.of("schemaVersion","sessionId","status","captureComplete","analysisReady","analysisEnabled","observedRootTraces","analyzedSpans","finishedAt","captureQuality","diagnosticSummary");
    public Map<String,Object> list(Path selected,int limit) throws IOException {
        if(limit<1 || limit>200)throw new IllegalArgumentException("limit must be 1–200");
        Path root=selected.toAbsolutePath().normalize();
        if(!Files.exists(root))return Map.of("root",root.toString(),"sessions",List.of(),"rootExists",false,"scanTruncated",false);
        if(!Files.isDirectory(root))throw new IllegalArgumentException("Session root is not a directory: "+root);
        List<Map<String,Object>> sessions=new ArrayList<>();List<String> warnings=new ArrayList<>();boolean truncated=false;
        try(var paths=Files.walk(root,2)) {
            var iterator=paths.iterator();int visited=0;
            while(iterator.hasNext()) {
                Path path=iterator.next();if(++visited>20000){truncated=true;break;}
                if(Files.isSymbolicLink(path) || !Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS) || !Files.isRegularFile(path.resolve("session.json"),LinkOption.NOFOLLOW_LINKS))continue;
                try{sessions.add(summary(path));}catch(Exception error){warnings.add(path+": "+error.getMessage());}
            }
        }
        sessions.sort(Comparator.<Map<String,Object>,Instant>comparing(row -> Instant.parse(row.get("startedAt").toString())).reversed().thenComparing(row -> row.get("sessionDirectory").toString()));
        return Map.of("root",root.toString(),"rootExists",true,"sessions",List.copyOf(sessions.subList(0,Math.min(limit,sessions.size()))),
                "matchedSessions",sessions.size(),"scanTruncated",truncated,"warnings",warnings);
    }
    public Map<String,Object> show(Path selected) throws IOException {
        Path root=selected.toAbsolutePath().normalize();if(root.getFileName()!=null && root.getFileName().toString().equals("spans"))root=root.getParent();
        Map<String,Object> result=new LinkedHashMap<>(summary(root));List<Map<String,Object>> files=new ArrayList<>();
        try(var paths=Files.walk(root,2)) {
            var iterator=paths.iterator();int visited=0;
            while(iterator.hasNext() && files.size()<128 && visited++<20000) {
                Path file=iterator.next();if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file))continue;
                files.add(Map.of("path",root.relativize(file).toString(),"bytes",Files.size(file)));
            }
        }
        files.sort(Comparator.comparing(row -> row.get("path").toString()));result.put("artifacts",files);result.put("artifactLimit",128);result.put("readOnly",true);return result;
    }
    private static Map<String,Object> summary(Path directory) throws IOException {
        if(!Files.isDirectory(directory))throw new IllegalArgumentException("Session directory does not exist: "+directory);
        Path marker=directory.resolve("session.json");
        if(Files.isSymbolicLink(marker) || !Files.isRegularFile(marker) || Files.size(marker)>65536)throw new IllegalArgumentException("Missing or oversized session.json: "+directory);
        JsonNode identity=JSON.readTree(marker.toFile());
        if(identity==null || !"agent-monitor".equals(identity.path("application").asText()) || identity.path("schemaVersion").asInt()!=1 || identity.path("sessionId").asText().isBlank())
            throw new IllegalArgumentException("Not an Agent Monitor session: "+directory);
        String started=identity.path("startedAt").asText();try{started=Instant.parse(started).toString();}catch(Exception invalid){throw new IllegalArgumentException("Invalid session start time");}
        Map<String,Object> result=new LinkedHashMap<>();result.put("sessionId",identity.path("sessionId").asText());result.put("sessionDirectory",directory.toAbsolutePath().normalize().toString());result.put("startedAt",started);
        Path report=directory.resolve("reports/performance-report.json");boolean available=Files.isRegularFile(report,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(report);
        result.put("reportAvailable",available);result.put("status","NOT_FINALIZED");result.put("captureComplete",null);result.put("analysisReady",null);
        if(available) {
            try {
                if(Files.size(report)>64L*1024*1024)throw new IOException("Report exceeds 64MiB");
                Map<String,Object> metadata=new LinkedHashMap<>();
                try(var parser=JSON.getFactory().createParser(report.toFile())) {
                    if(parser.nextToken()!=JsonToken.START_OBJECT)throw new IOException("Report is not an object");
                    Set<String> seen=new HashSet<>();
                    while(parser.nextToken()!=JsonToken.END_OBJECT) {
                        if(parser.currentToken()!=JsonToken.FIELD_NAME)throw new IOException("Malformed report");
                        String name=parser.currentName();if(!seen.add(name))throw new IOException("Duplicate report field: "+name);parser.nextToken();
                        if(SUMMARY.contains(name))metadata.put(name,JSON.convertValue(JSON.readTree(parser),Object.class));else parser.skipChildren();
                    }
                    if(parser.nextToken()!=null)throw new IOException("More than one report JSON document");
                }
                for(String field:List.of("captureComplete","analysisReady"))if(metadata.containsKey(field) && !(metadata.get(field) instanceof Boolean))throw new IOException("Invalid report boolean: "+field);
                if(!identity.path("sessionId").asText().equals(metadata.remove("sessionId")))throw new IOException("Report/session ID mismatch");
                result.putAll(metadata);
            }catch(Exception error){result.put("status","UNREADABLE_REPORT");result.put("captureComplete",null);result.put("analysisReady",null);result.put("reportError",Objects.toString(error.getMessage(),error.toString()));}
        }
        Path diagnostic=directory.resolve("diagnostics/manifest.json");
        if(!result.containsKey("diagnosticSummary") && Files.isRegularFile(diagnostic,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(diagnostic)) {
            try {
                if(Files.size(diagnostic)>1024*1024)throw new IOException("Diagnostic manifest exceeds 1MiB");
                JsonNode metadata=JSON.readTree(diagnostic.toFile());
                if(metadata==null || !identity.path("sessionId").asText().equals(metadata.path("sessionId").asText()))throw new IOException("Diagnostic/session ID mismatch");
                result.put("diagnosticSummary",JSON.convertValue(metadata,Object.class));
                if(!available)result.put("status","NO_TRACE_REPORT");
            } catch(Exception error){result.put("diagnosticError",Objects.toString(error.getMessage(),error.toString()));}
        }
        return result;
    }
}
