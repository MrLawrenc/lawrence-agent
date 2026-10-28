package com.agentmonitor.cli;

import com.agentmonitor.core.catalog.SessionCatalog;
import com.agentmonitor.core.config.MonitoringConfigYaml;
import com.agentmonitor.core.scope.ScopeClassIndex;
import com.agentmonitor.core.service.JvmClassIndexCache;
import com.agentmonitor.core.service.JvmService;
import com.agentmonitor.model.config.*;
import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static com.agentmonitor.cli.MonitorCli.*;

/** Non-capture commands keep stdout JSON and do not own a monitoring lifecycle. */
final class WorkflowCommands {
    Integer run(String[] args,PrintWriter out,PrintWriter err) throws Exception {
        if(!Set.of("config","scope","session","ai").contains(args[0]))return null;
        if(args.length<2)throw new IllegalArgumentException("Missing subcommand. Use monitor --help");
        String command=args[0]+" "+args[1];
        return switch(command) {
            case "config init" -> init(options(args,2,Set.of("output","package","class")),out);
            case "config validate" -> validate(options(args,2,Set.of("config")),out,err);
            case "scope preview" -> preview(options(args,2,Set.of("pid","config","package","search","limit","offset"),Set.of("refresh","selected-only")),out);
            case "session list" -> {var opts=options(args,2,Set.of("root","limit"));emit(out,new SessionCatalog().list(Path.of(opts.getOrDefault("root",MonitoringConfig.DEFAULT_SESSION_ROOT_DIRECTORY.replace("~",System.getProperty("user.home")))),integer(opts,"limit",20,1,200)));yield OK;}
            case "session show" -> {var opts=options(args,2,Set.of("session"));emit(out,new SessionCatalog().show(Path.of(required(opts,"session"))));yield OK;}
            case "ai verify" -> verify(options(args,2,Set.of("session","response","schema")),out,err);
            default -> throw new IllegalArgumentException("Unknown command: "+command+". Use monitor --help");
        };
    }
    private int init(Map<String,String> opts,PrintWriter out) throws Exception {
        var config=MonitoringConfig.defaults().withScope(new MonitoringConfig.Scope(csv(opts.get("package")),csv(opts.get("class")),MonitoringConfig.Scope.DEFAULT_EXCLUDE_CONDITIONS,MonitoringConfig.Scope.DEFAULT_EXCLUDE_METHODS));
        Path path=Path.of(opts.getOrDefault("output","monitor.yml")).toAbsolutePath().normalize();
        if(Files.exists(path,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("Configuration already exists; refusing to overwrite: "+path);
        Files.createDirectories(path.getParent());String yaml=MonitoringConfigYaml.write(config);
        Files.writeString(path,yaml,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
        emit(out,Map.of("path",path.toString(),"validForCapture",config.validationErrors().isEmpty(),"validationErrors",config.validationErrors(),"yaml",yaml,
                "nextAction",config.validationErrors().isEmpty()?"Validate the scope before capture":"Set scope.includePackages or scope.includeClasses, then run config validate"));return OK;
    }
    private int validate(Map<String,String> opts,PrintWriter out,PrintWriter err) throws Exception {
        Path path=Path.of(required(opts,"config")).toAbsolutePath().normalize();
        try {var config=readConfig(path);emit(out,Map.of("valid",true,"configPath",path.toString(),"scope",config.scope(),"exporters",config.output().exporters(),"diagnostics",config.diagnostics()));return OK;}
        catch(IllegalArgumentException invalid){emit(out,Map.of("valid",false,"configPath",path.toString(),"errors",List.of(invalid.getMessage()),"exitCode",USAGE));err.println(invalid.getMessage());err.flush();return USAGE;}
    }
    private int preview(Map<String,String> opts,PrintWriter out) throws Exception {
        long numeric=Long.parseLong(required(opts,"pid"));
        if(numeric<=0 || numeric==ProcessHandle.current().pid())throw new IllegalArgumentException("An external JVM PID is required");
        ProcessHandle process=ProcessHandle.of(numeric).filter(ProcessHandle::isAlive).orElseThrow(() -> new IllegalArgumentException("Target process is not alive"));
        var started=process.info().startInstant();String pid=Long.toString(numeric);var config=readConfig(Path.of(required(opts,"config")));
        int limit=integer(opts,"limit",150,1,500),offset=integer(opts,"offset",0,0,Integer.MAX_VALUE);
        var cache=new JvmClassIndexCache(pid);List<String> names=opts.containsKey("refresh")?List.of():cache.readClasses();
        Map<String,List<String>> methods;String source;
        if(names.isEmpty()){methods=JvmService.listClassMethods(pid);names=List.copyOf(methods.keySet());source="classpath-scan";}
        else {methods=cache.readMethods();source="process-start-bound-cache";}
        if(!process.isAlive() || !started.equals(process.info().startInstant()))throw new IllegalArgumentException("Target process exited or changed during indexing");
        var index=new ScopeClassIndex(names,methods);var selection=new ScopeSelection(config.scope());var selected=index.select(config.scope());
        var query=index.query(selected,opts.getOrDefault("package",""),opts.getOrDefault("search",""),opts.containsKey("selected-only"));
        long known=index.names().stream().filter(selection::selected).filter(methods::containsKey).count();
        int from=Math.min(offset,query.matches().size()),to=Math.min(from+limit,query.matches().size());List<Map<String,Object>> rows=new ArrayList<>();
        for(String name:query.matches().subList(from,to)) {
            Map<String,Object> row=new LinkedHashMap<>();row.put("className",name);row.put("selected",selection.selected(name));row.put("exclusion",selection.exclusion(name));
            row.put("indexedMethodNames",methods.containsKey(name)?methods.get(name).stream().filter(m -> selection.methodSelected(name,m)).count():null);rows.add(row);
        }
        Map<String,Object> result=new LinkedHashMap<>();result.put("targetPid",numeric);result.put("processStartedAt",started.map(Instant::toString).orElse(null));result.put("observedAt",Instant.now().toString());
        result.put("indexSource",source);result.put("indexedClasses",index.names().size());result.put("globalSelectedClasses",selected.classCount());result.put("selectedClassesWithMethodIndex",known);
        result.put("knownSelectedMethodNames",selected.methodCount());result.put("selectedMethodIndexClassCoverageComplete",known==selected.classCount());
        result.put("package",opts.getOrDefault("package",""));result.put("packageClasses",query.packageClassCount());result.put("packageSelectedClasses",query.packageSelectedCount());
        result.put("matchedClasses",query.matches().size());result.put("matchedSelectedClasses",query.matchedSelectedCount());result.put("offset",from);result.put("limit",limit);result.put("classes",rows);result.put("scope",config.scope());
        result.put("limitations",List.of("Discovered classpath/cache candidates, not proof of loaded or successfully transformed classes. Dynamic/custom-loader classes may be missing.",
                "No performance Agent is attached; this command never requests a heap histogram or GC. Cache freshness may lag newly loaded classes; --refresh scans the target classpath.","Method totals cover only classes present in the method index; future-loaded classes can also match package rules.","Method names deduplicate overloads. These are indexed name entries, not actual transformed method counts."));
        emit(out,result);return OK;
    }
    private int verify(Map<String,String> opts,PrintWriter out,PrintWriter err) throws Exception {
        var validator=new com.agentmonitor.core.ai.AiAnalysisValidator();
        var result=validator.verify(Path.of(required(opts,"session")),Path.of(required(opts,"response")),opts.containsKey("schema")?Path.of(opts.get("schema")):null);
        emit(out,result);if(!result.valid()){err.println(String.join("; ",result.errors()));err.flush();}return result.valid()?OK:INVALID_ANALYSIS;
    }
    private static List<String> csv(String text){return text==null?List.of():Arrays.stream(text.split(",")).map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();}
    private static int integer(Map<String,String> opts,String key,int fallback,int min,int max){int n=opts.containsKey(key)?Integer.parseInt(opts.get(key)):fallback;if(n<min || n>max)throw new IllegalArgumentException(key+" must be "+min+"–"+max);return n;}
    private static MonitoringConfig readConfig(Path path) throws IOException {if(!Files.isRegularFile(path) || Files.size(path)>1_048_576)throw new IllegalArgumentException("Configuration missing or exceeds 1MiB: "+path);return MonitoringConfigYaml.parse(Files.readString(path));}
}
