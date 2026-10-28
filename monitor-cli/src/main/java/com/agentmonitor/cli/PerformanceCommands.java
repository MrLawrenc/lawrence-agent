package com.agentmonitor.cli;

import com.agentmonitor.core.diagnostics.*;
import com.fasterxml.jackson.databind.*;
import java.io.PrintWriter;
import java.nio.file.*;
import java.util.*;

/** Request metrics, symptom-driven capture and exact cross-session trace correlation. */
final class PerformanceCommands {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY,DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static String help(){return """
              monitor requests --session <directory> [--json]
              monitor diagnose --symptom cpu|latency|memory|errors --session <directory> [--json]
              monitor diagnose --symptom cpu|latency|memory|errors --pid <pid> [--config <yaml>] [--duration 30s] [--output <root>] [--json]
              monitor distributed --sessions <directory1,directory2> --trace <trace-id> [--output <json>] [--json]
            diagnose with --config captures traces and independent Servlet metrics; without it collects JVM/JFR diagnostics.
            Request percentiles are histogram upper bounds for observed Servlet ingress, independent of trace sampling.
            """;}
    Integer run(String[] args,PrintWriter out,PrintWriter err)throws Exception{
        if(!Set.of("requests","diagnose","distributed").contains(args[0]))return null;
        if(args[0].equals("requests")){
            var o=MonitorCli.options(args,1,Set.of("session"));Path root=Path.of(MonitorCli.required(o,"session")).toRealPath();
            var data=new RequestStatistics().read(root);MonitorCli.emit(out,data);return Boolean.TRUE.equals(data.get("captureComplete"))?0:5;
        }
        if(args[0].equals("distributed")){
            var o=MonitorCli.options(args,1,Set.of("sessions","trace","output"));List<Path> sessions=Arrays.stream(MonitorCli.required(o,"sessions").split(",")).map(Path::of).toList();
            Object result=new DistributedTrace().correlate(sessions,MonitorCli.required(o,"trace"));
            if(o.containsKey("output"))com.agentmonitor.core.util.AtomicFiles.write(Path.of(o.get("output")),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
            MonitorCli.emit(out,result);return 0;
        }
        var o=MonitorCli.options(args,1,Set.of("symptom","session","pid","config","duration","output"));String symptom=MonitorCli.required(o,"symptom");SymptomDiagnosis.plan(symptom);
        if(o.containsKey("session")&&o.keySet().stream().anyMatch(k->Set.of("pid","config","duration","output").contains(k)))throw new IllegalArgumentException("--session cannot be combined with live capture options");
        Path session;int captureCode=0;
        if(o.containsKey("session"))session=Path.of(o.get("session"));
        else{
            String pid=MonitorCli.required(o,"pid");Object capture;
            if(o.containsKey("config")) {
                Map<String,String> captureOptions=new LinkedHashMap<>();
                captureOptions.put("pid",pid);captureOptions.put("config",o.get("config"));
                captureOptions.put("duration",o.getOrDefault("duration","30s"));
                if(o.containsKey("output"))captureOptions.put("output",o.get("output"));
                var result=new MonitorCli().capture(captureOptions,err);
                captureCode=result.exitCode();capture=result.body();
            } else {
                List<String> command=new ArrayList<>(List.of("diag","record","--pid",pid,"--duration",o.getOrDefault("duration","30s")));
                if(o.containsKey("output"))command.addAll(List.of("--output",o.get("output")));
                capture=DiagnosticCommands.execute(command.toArray(String[]::new));
                captureCode=DiagnosticCommands.exitCode(capture);
            }
            JsonNode captured=JSON.valueToTree(capture);
            if(!captured.hasNonNull("sessionDirectory")||captureCode!=0&&captureCode!=5){MonitorCli.emit(out,capture);return captureCode;}
            session=Path.of(captured.path("sessionDirectory").asText());
        }
        Map<String,Object> result=new LinkedHashMap<>(new SymptomDiagnosis().analyze(session,symptom));result.put("captureExitCode",captureCode);MonitorCli.emit(out,result);return captureCode;
    }
}
