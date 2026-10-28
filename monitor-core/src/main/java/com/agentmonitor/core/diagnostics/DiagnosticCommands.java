package com.agentmonitor.core.diagnostics;

import com.agentmonitor.core.config.MonitoringConfigYaml;
import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.util.AtomicFiles;
import com.agentmonitor.model.config.MonitoringConfig;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared diagnostic command contract; stdout formatting belongs to the CLI. */
public final class DiagnosticCommands {
    private static final ObjectMapper JSON=new ObjectMapper();
    private DiagnosticCommands(){}
    public static boolean supports(String command){return Set.of("diag","profile","compare").contains(command);}
    public static int exitCode(Object result){
        JsonNode node=JSON.valueToTree(result);if("strict".equals(node.path("comparisonMode").asText())&&!node.path("comparability").path("eligible").asBoolean())return 5;JsonNode manifest=node.path("manifest");
        return manifest.isObject() && (!manifest.path("errors").isEmpty() || manifest.path("sampleLimitReached").asBoolean() || manifest.path("byteLimitReached").asBoolean() || manifest.path("samples").asInt()==0) ? 5 : 0;
    }
    public static String help(){return """
              monitor diag snapshot|environment|pools --pid <pid> [--output <json>]
              monitor diag threads --pid <pid> [--count 3] [--interval 1s] [--output <json>]
              monitor diag histogram --pid <pid> --output <json> [--live]
              monitor diag heap-dump --pid <pid> --output <hprof>
              monitor diag classes --session <directory> [--query <class-or-jar>] [--refresh]
              monitor diag record --pid <pid> [--duration 60s] [--output <root>] [--config <yaml>]
              monitor profile record --pid <pid> --output <directory> [--duration 30s] [--exceptions]
              monitor profile analyze --recording <jfr> --output <directory>
              monitor profile async --pid <pid> --binary <asprof> --event cpu|wall|alloc|lock --output <html> [--duration 30s]
              monitor compare --before <session> --after <session> [--kind session|histogram] [--observed] [--output <json>]
            JFR records a bounded 120s/32MiB ring; histogram --live may request a full GC; heap dumps may pause the target.
            """;}
    public static Object execute(String[] args)throws Exception{
        if(args.length==0||!supports(args[0]))throw new IllegalArgumentException("Unknown diagnostic command");
        if(args[0].equals("compare")){
            var o=options(args,1,Set.of("before","after","kind","output"),Set.of("observed"));
            String kind=o.getOrDefault("kind","session");if(!Set.of("session","histogram").contains(kind))throw new IllegalArgumentException("--kind: session|histogram");
            var comparison=new SessionComparison();Object result=kind.equals("histogram")?comparison.compareHistograms(path(o,"before"),path(o,"after")):comparison.compare(path(o,"before"),path(o,"after"),!o.containsKey("observed"));
            return save(o,result);
        }
        if(args.length<2)throw new IllegalArgumentException(help());String action=args[1];
        if(args[0].equals("profile"))return profile(action,args);
        if(action.equals("classes")){
            var o=options(args,2,Set.of("session","query"),Set.of("refresh"));Path file=path(o,"session").resolve("agent-logs/class-origins.json");
            if(o.containsKey("refresh")){
                Path logs=path(o,"session").resolve("agent-logs");JsonNode live=JSON.readTree(logs.resolve("live-invocations.json").toFile());
                if(!live.path("status").asText().equals("RUNNING")||System.currentTimeMillis()-live.path("timestampMillis").asLong()>5000)throw new IllegalArgumentException("类来源刷新需要仍在采集的活动会话");
                long previous=Files.isRegularFile(file)?Files.getLastModifiedTime(file).toMillis():0;
                AtomicFiles.write(logs.resolve("class-origin-refresh.request"),"refresh");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
                while((!Files.isRegularFile(file)||Files.getLastModifiedTime(file).toMillis()<=previous)&&System.nanoTime()<deadline)Thread.sleep(100);
                if(!Files.isRegularFile(file)||Files.getLastModifiedTime(file).toMillis()<=previous)throw new IllegalStateException("类来源刷新超时，原快照仍保留");
            }
            if(Files.size(file)>64L*1024*1024)throw new IllegalArgumentException("Class inventory exceeds 64MiB");
            JsonNode root=JSON.readTree(file.toFile());List<JsonNode> rows=new ArrayList<>();Map<String,Set<String>> loaders=new TreeMap<>();String query=o.getOrDefault("query","").toLowerCase(Locale.ROOT);
            for(JsonNode item:root.path("classes")){
                loaders.computeIfAbsent(item.path("className").asText(),k->new TreeSet<>()).add(item.path("loader").asText());
                if(item.toString().toLowerCase(Locale.ROOT).contains(query)&&rows.size()<2000)rows.add(item);
            }
            var duplicates=loaders.entrySet().stream().filter(e->e.getValue().size()>1).limit(1000).map(e->Map.of("className",e.getKey(),"loaders",e.getValue())).toList();
            return Map.of("timestampMillis",root.path("timestampMillis"),"classes",rows,"duplicateClassNames",duplicates,"omittedClasses",root.path("omittedClasses"),"resultLimit",2000,"coverage",root.path("coverage"));
        }
        if(action.equals("record")){
            var o=options(args,2,Set.of("pid","duration","output","config"),Set.of());String pid=required(o,"pid");long numericPid=Long.parseLong(pid);if(numericPid<=0||numericPid==ProcessHandle.current().pid()||!ProcessHandle.of(numericPid).map(ProcessHandle::isAlive).orElse(false))throw new IllegalArgumentException("需要仍存活的外部 JVM PID");long duration=duration(o.getOrDefault("duration","60s"));
            var config=o.containsKey("config")?MonitoringConfigYaml.parseDiagnostics(Files.readString(path(o,"config"))).diagnostics():MonitoringConfig.Diagnostics.defaults();
            if(!config.enabled())throw new IllegalArgumentException("diagnostics.enabled=false");
            var session=MonitoringSession.create(o.getOrDefault("output",MonitoringConfig.DEFAULT_SESSION_ROOT_DIRECTORY));
            AtomicFiles.write(session.sessionDirectory().resolve("ai-analysis-prompt.txt"),com.agentmonitor.core.report.SessionAnalysisPrompt.forSession(session));
            AtomicFiles.write(session.reportsDirectory().resolve("ai-analysis-output.schema.json"),com.agentmonitor.core.report.SessionAnalysisPrompt.outputSchema());
            try(var recorder=new DiagnosticRecorder(pid,config,session)){
                Thread hook=new Thread(recorder::close,"diagnostics-cli-close");Runtime.getRuntime().addShutdownHook(hook);
                try{recorder.start();if(recorder.awaitFirstSample(20_000))waitDuration(pid,duration);}finally{recorder.close();Runtime.getRuntime().removeShutdownHook(hook);}
            }
            return Map.of("sessionDirectory",session.sessionDirectory().toString(),"diagnosticsOnly",true,"manifest",JSON.readTree(session.sessionDirectory().resolve("diagnostics/manifest.json").toFile()));
        }
        Set<String> allowed=switch(action){case "snapshot","environment","pools"->Set.of("pid","output");case "threads"->Set.of("pid","count","interval","output");case "histogram","heap-dump"->Set.of("pid","output");default->throw new IllegalArgumentException("Unknown diag action: "+action);};
        var o=options(args,2,allowed,action.equals("histogram")?Set.of("live"):Set.of());
        try(var target=new TargetJvm(required(o,"pid"))){
            Object result=switch(action){
                case "snapshot"->target.sample();case "environment"->target.environment();case "pools"->target.pools();
                case "threads"->{int count=Integer.parseInt(o.getOrDefault("count","3"));if(count<1||count>30)throw new IllegalArgumentException("--count: 1–30");long interval=duration(o.getOrDefault("interval","1s"));if(interval>60_000)throw new IllegalArgumentException("--interval <= 60s");List<Map<String,Object>> snapshots=new ArrayList<>();for(int i=0;i<count;i++){if(i>0)Thread.sleep(interval);snapshots.add(target.threads());}yield Map.of("snapshots",snapshots,"comparison",compareThreads(snapshots));}
                case "histogram"->{required(o,"output");yield target.histogram(o.containsKey("live"));}
                case "heap-dump"->{Path output=path(o,"output");String message=target.heapDump(output);boolean created=Files.isRegularFile(output)&&Files.size(output)>0;if(!created)throw new IllegalStateException("Heap dump 未生成: "+message);yield Map.of("path",output.toAbsolutePath().toString(),"message",message,"bytes",Files.size(output));}
                default->throw new IllegalArgumentException(action);
            };
            return action.equals("heap-dump")?result:save(o,result);
        }
    }
    private static Object profile(String action,String[] args)throws Exception{
        if(action.equals("analyze")){var o=options(args,2,Set.of("recording","output"),Set.of());return new JfrAnalysis().export(path(o,"recording"),path(o,"output"));}
        if(action.equals("async")){
            var o=options(args,2,Set.of("pid","binary","event","duration","output"),Set.of());String event=required(o,"event");if(!Set.of("cpu","wall","alloc","lock").contains(event))throw new IllegalArgumentException("--event: cpu|wall|alloc|lock");
            long millis=duration(o.getOrDefault("duration","30s"));Path binary=path(o,"binary").toAbsolutePath(),output=path(o,"output").toAbsolutePath();
            if(!Files.isExecutable(binary))throw new IllegalArgumentException("asprof 不可执行: "+binary);
            if(Files.exists(output))throw new IllegalArgumentException("输出已存在: "+output);Files.createDirectories(output.getParent());
            String pid=required(o,"pid");if(Long.parseLong(pid)<=0||Long.parseLong(pid)==ProcessHandle.current().pid())throw new IllegalArgumentException("需要外部 JVM PID");
            Path log=Files.createTempFile("agent-profiler-",".log");Process process=null;
            try{process=new ProcessBuilder(binary.toString(),"-e",event,"-d",Long.toString(Math.max(1,(millis+999)/1000)),"-f",output.toString(),pid).redirectErrorStream(true).redirectOutput(log.toFile()).start();
                if(!process.waitFor(millis+30_000,TimeUnit.MILLISECONDS)){process.destroyForcibly();throw new IllegalStateException("async-profiler 超时");}
                String text;try(var input=Files.newInputStream(log)){text=new String(input.readNBytes(8192),java.nio.charset.StandardCharsets.UTF_8);}if(process.exitValue()!=0||!Files.isRegularFile(output))throw new IllegalStateException("async-profiler: "+text.substring(0,Math.min(8192,text.length())));
                return Map.of("path",output.toString(),"event",event,"bytes",Files.size(output));
            }finally{if(process!=null&&process.isAlive())process.destroyForcibly();Files.deleteIfExists(log);}
        }
        if(!action.equals("record"))throw new IllegalArgumentException("Unknown profile action: "+action);
        var o=options(args,2,Set.of("pid","duration","output"),Set.of("exceptions"));Path directory=path(o,"output");Files.createDirectories(directory);
        Path recording=directory.resolve("profile.jfr");if(Files.exists(recording))throw new IllegalArgumentException("输出已存在: "+recording);
        long millis=duration(o.getOrDefault("duration","30s"));String pid=required(o,"pid");
        try(var target=new TargetJvm(pid);var profiler=new JfrProfiler(target,o.containsKey("exceptions"))){waitDuration(pid,millis);profiler.dump(recording);}
        new JfrAnalysis().export(recording,directory);return Map.of("recording",recording.toAbsolutePath().toString(),"analysis",directory.resolve("jfr-analysis.json").toAbsolutePath().toString(),"flamegraph",directory.resolve("flamegraph.html").toAbsolutePath().toString());
    }
    private static Object compareThreads(List<Map<String,Object>> snapshots){
        if(snapshots.size()<2)return Map.of("unchangedStackThreadIds",List.of());
        Map<String,String> first=new HashMap<>();for(JsonNode t:JSON.valueToTree(snapshots.get(0)).path("threads"))first.put(t.path("threadId").asText(),t.path("stack").toString());
        List<String> unchanged=new ArrayList<>();for(JsonNode t:JSON.valueToTree(snapshots.get(snapshots.size()-1)).path("threads"))if(t.path("stack").toString().equals(first.get(t.path("threadId").asText())))unchanged.add(t.path("threadId").asText());
        return Map.of("unchangedStackThreadIds",unchanged,"interpretation","Repeated stacks may be normal waiting; inspect state, owners, CPU deltas, and workload.");
    }
    private static Object save(Map<String,String> o,Object value)throws Exception{if(o.containsKey("output"))AtomicFiles.write(path(o,"output"),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));return value;}
    private static Path path(Map<String,String> o,String key){return Path.of(required(o,key));}
    private static String required(Map<String,String> o,String key){String value=o.get(key);if(value==null||value.isBlank())throw new IllegalArgumentException("Missing --"+key);return value;}
    private static long duration(String text){if(!text.matches("[0-9]+(ms|s|m)"))throw new IllegalArgumentException("Duration: ms|s|m");int suffix=text.endsWith("ms")?2:1;long value;try{value=Math.multiplyExact(Long.parseLong(text.substring(0,text.length()-suffix)),suffix==2?1:text.endsWith("s")?1000:60000);}catch(ArithmeticException e){throw new IllegalArgumentException("Duration too large");}if(value<500||value>3600000)throw new IllegalArgumentException("Duration: 500ms–60m");return value;}
    private static void waitDuration(String pid,long millis)throws Exception{long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis);while(System.nanoTime()<deadline&&ProcessHandle.of(Long.parseLong(pid)).map(ProcessHandle::isAlive).orElse(false))Thread.sleep(100);}
    private static Map<String,String> options(String[] args,int start,Set<String> values,Set<String> flags){Map<String,String> o=new LinkedHashMap<>();for(int i=start;i<args.length;i++){String token=args[i];if(!token.startsWith("--"))throw new IllegalArgumentException("Unknown option: "+token);String key=token.substring(2);if(o.containsKey(key))throw new IllegalArgumentException("Duplicate --"+key);if(key.equals("json")||flags.contains(key)){o.put(key,"true");continue;}if(!values.contains(key)||++i>=args.length||args[i].startsWith("--"))throw new IllegalArgumentException("Unknown or missing option: "+token);o.put(key,args[i]);}return o;}
}
