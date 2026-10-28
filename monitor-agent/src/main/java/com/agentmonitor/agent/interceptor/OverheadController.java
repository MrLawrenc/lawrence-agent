package com.agentmonitor.agent.interceptor;

import com.agentmonitor.model.config.MonitoringConfig;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/** Generation-scoped, bounded adaptive policy. It never claims to enforce a process CPU ceiling. */
public final class OverheadController {
    private static volatile MonitoringConfig.Overhead config=MonitoringConfig.Overhead.defaults();
    private static volatile int level;
    private static final LongAdder adviceNanos=new LongAdder();
    private static final ArrayDeque<Map<String,Object>> transitions=new ArrayDeque<>();
    private static long epochNanos=System.nanoTime(),lastAdvice,lastPressureNanos,observedAdvice;
    private static long omittedTransitions,peakResidentBytes;
    private OverheadController(){}
    public static synchronized void configure(MonitoringConfig.Overhead replacement){
        config=replacement;level=0;adviceNanos.reset();lastAdvice=observedAdvice=0;epochNanos=System.nanoTime();lastPressureNanos=0;
        transitions.clear();omittedTransitions=peakResidentBytes=0;
    }
    public static void recordAdvice(long nanos){if(config.enabled())adviceNanos.add(Math.max(0,nanos));}
    public static boolean detailEnabled(){return level==0;}
    public static String mode(){return switch(level){case 1->"REDUCED";case 2->"MINIMAL";default->"NORMAL";};}
    public static int samplingRate(int configured){
        if(!config.enabled()||level==0)return configured;
        int floor=Math.min(configured,config.minimumSamplingRatePercent());
        return level==1?Math.max(floor,configured/2):floor;
    }
    public static synchronized void observe(long residentBytes,long clockNanos){
        peakResidentBytes=Math.max(peakResidentBytes,residentBytes);
        if(!config.enabled())return;
        long elapsed=clockNanos-epochNanos;
        if(elapsed<250_000_000L)return;
        long total=adviceNanos.sum(),delta=Math.max(0,total-lastAdvice);lastAdvice=total;observedAdvice=delta;
        boolean advicePressure=delta>config.adviceBudgetNanosPerSecond()*(elapsed/1_000_000_000.0);
        int percent=(int)Math.min(100,100.0*residentBytes/config.queueMaxBytes());
        int next=level;String reason="";
        if(percent>=config.highWatermarkPercent()||advicePressure){next=Math.min(2,level+1);lastPressureNanos=clockNanos;reason=advicePressure?"advice_time_budget":"resident_payload_high_watermark";}
        else if(percent<=config.lowWatermarkPercent()&&level>0&&clockNanos-lastPressureNanos>=config.recoveryMillis()*1_000_000L){next=level-1;lastPressureNanos=clockNanos;reason="sustained_recovery";}
        epochNanos=clockNanos;
        if(next!=level){level=next;if(transitions.size()>=128){transitions.removeFirst();omittedTransitions++;}
            transitions.addLast(Map.of("timestampMillis",System.currentTimeMillis(),"mode",mode(),"reason",reason,"residentEstimatedBytes",residentBytes));}
    }
    public static synchronized Map<String,Object> snapshot(){
        return Map.of("schemaVersion",1,"mode",mode(),"adviceMeasuredNanos",adviceNanos.sum(),"recentAdviceMeasuredNanos",observedAdvice,
                "peakResidentEstimatedBytes",peakResidentBytes,"transitions",List.copyOf(transitions),"omittedTransitions",omittedTransitions,
                "limitations","Advice timing covers measured hooks, not all observer work or process CPU. Adaptive head sampling plus tail promotion remains biased; detailed payload capture is disabled under pressure.");
    }
}
