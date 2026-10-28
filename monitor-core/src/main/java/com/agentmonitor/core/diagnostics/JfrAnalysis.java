package com.agentmonitor.core.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentmonitor.core.util.AtomicFiles;
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;

/** Bounded JFR evidence, preserving event units and the distinction between CPU and wall time. */
public final class JfrAnalysis {
    private static final ObjectMapper JSON = new ObjectMapper();
    public Map<String,Object> analyze(Path recording) throws Exception {
        Map<String,Long> eventCounts = new TreeMap<>(); Map<String,Map<String,Long>> stacks = new TreeMap<>();
        List<Map<String,Object>> timeline=new ArrayList<>();long omittedTimeline=0;
        List<Map<String,Object>> pauses = new ArrayList<>(); Map<String,Long> exceptions = new LinkedHashMap<>();
        Map<String,Long> exceptionWindows=new LinkedHashMap<>();
        long omittedStacks = 0, omittedPauses = 0, omittedExceptions = 0, events = 0; boolean eventLimit = false;
        try (RecordingFile input = new RecordingFile(recording)) {
            while (input.hasMoreEvents()) {
                if (++events > 2_000_000) { eventLimit = true; break; }
                RecordedEvent event = input.readEvent(); String name = event.getEventType().getName();
                eventCounts.merge(name, 1L, Long::sum);
                if(Set.of("jdk.ExecutionSample","jdk.NativeMethodSample","jdk.GCPhasePause","jdk.GarbageCollection","jdk.JavaMonitorEnter","jdk.ThreadPark","jdk.SocketRead","jdk.SocketWrite","jdk.FileRead","jdk.FileWrite","jdk.ObjectAllocationSample").contains(name)){
                    if(timeline.size()<20000){Map<String,Object> point=new LinkedHashMap<>();point.put("eventType",name);point.put("startedAtEpochMillis",event.getStartTime().toEpochMilli());point.put("durationNanos",event.getDuration().toNanos());
                        RecordedThread thread=event.hasField("sampledThread")?event.getThread("sampledThread"):event.getThread();if(thread!=null){point.put("javaThreadId",thread.getJavaThreadId());point.put("threadName",thread.getJavaName());}
                        if(event.getStackTrace()!=null&&!event.getStackTrace().getFrames().isEmpty()){var method=event.getStackTrace().getFrames().get(0).getMethod();point.put("method",method.getType().getName()+"#"+method.getName()+method.getDescriptor());}
                        timeline.add(point);
                    }else omittedTimeline++;
                }
                if (name.equals("jdk.GCHeapSummary")) {
                    if (pauses.size()<10000) pauses.add(Map.of("event",name,"startedAtEpochMillis",event.getStartTime().toEpochMilli(),"when",event.getString("when"),"heapUsedBytes",event.getLong("heapUsed"))); else omittedPauses++;
                }
                if (name.equals("jdk.GCPhasePause") || name.equals("jdk.GarbageCollection")) {
                    if (pauses.size()<10000) pauses.add(Map.of("event", name, "startedAtEpochMillis", event.getStartTime().toEpochMilli(), "durationNanos", event.getDuration().toNanos()));
                    else omittedPauses++;
                }
                if (name.equals("jdk.JavaExceptionThrow")) {
                    String type = event.getClass("thrownClass").getName();
                    String window=event.getStartTime().getEpochSecond()+"|"+type;
                    if(exceptionWindows.containsKey(window)||exceptionWindows.size()<8192)exceptionWindows.merge(window,1L,Long::sum);else omittedExceptions++;
                    String key = type+"\n"+stack(event);
                    if (exceptions.containsKey(key) || exceptions.size()<8192) exceptions.merge(key,1L,Long::sum); else omittedExceptions++;
                }
                String mode = switch (name) {
                    case "jdk.ExecutionSample", "jdk.NativeMethodSample" -> "cpu_samples";
                    case "jdk.ObjectAllocationSample" -> "allocation_bytes";
                    case "jdk.JavaMonitorEnter", "jdk.ThreadPark" -> "lock_wait_nanos";
                    case "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite" -> "io_wait_nanos";
                    default -> null;
                };
                if (mode == null) continue;
                long weight = mode.equals("allocation_bytes") ? event.getLong("weight") : mode.equals("cpu_samples") ? 1 : Math.max(0,event.getDuration().toNanos());
                String stack = stack(event); if (stack.isBlank()) continue;
                Map<String,Long> bucket = stacks.computeIfAbsent(mode, k -> new LinkedHashMap<>());
                if (bucket.containsKey(stack) || bucket.size()<8192) bucket.merge(stack,weight,Long::sum); else omittedStacks++;
            }
        }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("timelineEvents",timeline);result.put("omittedTimelineEvents",omittedTimeline);
        result.put("schemaVersion",1); result.put("source", recording.getFileName().toString()); result.put("eventCounts",eventCounts);
        result.put("stacks", stacks); result.put("gcEvents",pauses); result.put("exceptionThrows", exceptions);
        result.put("exceptionStormCandidates",exceptionWindows.entrySet().stream().filter(e->e.getValue()>=100).map(e->{int split=e.getKey().indexOf('|');return Map.of("epochSecond",Long.parseLong(e.getKey().substring(0,split)),"type",e.getKey().substring(split+1),"observedThrowEvents",e.getValue(),"thresholdEventsPerSecond",100);}).toList());
        result.put("limits",Map.of("omittedStackEvents", omittedStacks, "omittedGcEvents", omittedPauses, "omittedExceptionEvents", omittedExceptions,"eventLimitReached", eventLimit));
        result.put("limitations",List.of("CPU samples are statistical evidence, not exact method CPU durations.",
                "JFR configuration controls event coverage. Allocation bytes are sampled weights.",
                "ThreadPark includes parks unrelated to lock contention. Time overlap with GC is correlation, not proof of cause.",
                "The recording contains at most the final 120 seconds/32MiB ring. Exception throws require exceptionEvents=true."));
        return result;
    }
    private static String stack(RecordedEvent event) {
        RecordedStackTrace trace = event.getStackTrace(); if (trace == null) return "";
        var frames = trace.getFrames(); List<String> names = new ArrayList<>();
        for (int i=Math.min(frames.size(),128)-1;i>=0;i--) {
            RecordedMethod method=frames.get(i).getMethod(); names.add(method.getType().getName()+"."+method.getName());
        }
        return String.join(";", names);
    }
    public Map<String,Object> export(Path recording, Path directory) throws Exception {
        Map<String,Object> result = analyze(recording); Files.createDirectories(directory);
        AtomicFiles.write(directory.resolve("jfr-analysis.json"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        String data = JSON.writeValueAsString(result.get("stacks")).replace("<", "\\u003c").replace("&", "\\u0026");
        String template;
        try (var input = JfrAnalysis.class.getResourceAsStream("/com/agentmonitor/core/diagnostics/flamegraph.html")) {
            if (input == null) throw new IllegalStateException("Missing flamegraph template");
            template = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        AtomicFiles.write(directory.resolve("flamegraph.html"), template.replace("/*PROFILE_DATA*/{}", data));
        return result;
    }
}
