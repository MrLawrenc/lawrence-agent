package com.agentmonitor.core.performance;

import com.agentmonitor.model.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@Tag("performance")
class ScopeIndexBenchmarkTest {
    private static volatile long consumed;
    static List<String> fixture() {
        List<String> names=new ArrayList<>(141307);
        for(int i=0;i<2250;i++) names.add(String.format(Locale.ROOT,"com.example.orders.group%03d.OrderService%05d",i/35,i));
        for(int i=2250;i<141307;i++) names.add(String.format(Locale.ROOT,"org.library.module%04d.LibraryClass%06d",i/40,i));
        return names.stream().sorted().toList();
    }
    static MonitoringConfig.Scope scope() {
        return new MonitoringConfig.Scope(List.of("com.example.orders"),List.of(),List.of("cls:*DTO","cls:*Entity"),List.of("get*","set*"));
    }
    static long baseline(List<String> names,String browsed,String needle) {
        ScopeSelection selection=new ScopeSelection(scope());
        List<String> found=names.stream().filter(n -> browsed.isEmpty() || ScopeSelection.under(ScopeSelection.packageOf(n),browsed))
                .filter(n -> needle.isEmpty() || n.toLowerCase(Locale.ROOT).contains(needle)).toList();
        return found.size()+names.stream().filter(selection::selected).count();
    }
    interface Work {long run();}
    static Map<String,Object> measure(Work work) {
        for(int i=0;i<15;i++) consumed=work.run();
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        long allocatedBefore=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
        long[] times=new long[40];
        for(int i=0;i<times.length;i++){long start=System.nanoTime();consumed=work.run();times[i]=System.nanoTime()-start;}
        long allocated=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-allocatedBefore;
        Arrays.sort(times);
        return Map.of("samples",times.length,"medianMs",times[times.length/2]/1e6,"p95Ms",times[(int)Math.ceil(times.length*.95)-1]/1e6,
                "maxMs",times[times.length-1]/1e6,"allocatedBytesPerQuery",allocated/times.length);
    }
    @Test void recordsBeforeAndAfterAtTheReportedIndexScale() throws Exception {
        var names=fixture();assertEquals(141307,names.size());
        var result=new LinkedHashMap<String,Object>();
        result.put("data","synthetic / 141307 indexed, 2250 globally selected, 35 in browsed package");
        result.put("java",System.getProperty("java.version"));result.put("os",System.getProperty("os.name")+" / "+System.getProperty("os.arch"));
        result.put("measuredAt",java.time.Instant.now().toString());
        result.put("baselinePackageQuery",measure(() -> baseline(names,"com.example.orders.group007","")));
        result.put("baselineGlobalSearch",measure(() -> baseline(names,"","libraryclass100")));
        var index=new com.agentmonitor.core.scope.ScopeClassIndex(names,Map.of());
        var selected=index.select(scope());
        assertEquals(2250,selected.classCount());assertEquals(35,index.query(selected,"com.example.orders.group007","",false).packageClassCount());
        result.put("optimizedPackageQuery",measure(() -> index.query(selected,"com.example.orders.group007","",false).matches().size()+selected.classCount()));
        result.put("optimizedGlobalSearch",measure(() -> index.query(selected,"","libraryclass100",false).matches().size()+selected.classCount()));
        result.put("coldRuleEvaluation",measure(() -> new com.agentmonitor.core.scope.ScopeClassIndex(names,Map.of()).select(scope()).classCount()));
        result.put("limitations",List.of("Synthetic query microbenchmark; excludes class enumeration, Attach and JavaFX rendering.","Warm cached-rule queries measured separately from cold index construction plus rule evaluation.","No claim about target JVM CPU, RSS, business throughput or production percentiles."));
        Path output=Path.of("build/performance/scope-index.json");Files.createDirectories(output.getParent());
        Files.writeString(output,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(result));
        System.out.println("SCOPE_BENCHMARK "+Files.readString(output));
    }
}
