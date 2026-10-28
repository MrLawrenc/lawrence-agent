package com.agentmonitor.agent.exporter;

import com.agentmonitor.agent.interceptor.OverheadController;
import com.agentmonitor.agent.model.*;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.span.SpanKind;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class OverheadBudgetTest {
    @AfterEach void reset(){OverheadController.configure(MonitoringConfig.Overhead.defaults());}
    @Test void byteBudgetIncludesTheInflightBatchUntilDeliveryFinishes()throws Exception {
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        SpanExporter sink=new SpanExporter(){public String name(){return "blocked";}public boolean start(){return true;}public ExportResult export(SpanData span){entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}return ExportResult.accepted();}public void close(){release.countDown();}};
        var budget=new MonitoringConfig.Overhead(true,65536,75,35,1,50_000_000,1000);
        AsyncSpanExporter exporter=new AsyncSpanExporter(sink,budget);exporter.start();
        SpanData span=new SpanData("t","s","","example.Work","call","()V","test",0,1,1,SpanStatus.SUCCESS,"x".repeat(8192),"y".repeat(8192),"z".repeat(8192),SpanKind.BUSINESS,Map.of());
        try{
            assertTrue(exporter.export(span).delivered());assertTrue(entered.await(2,TimeUnit.SECONDS));
            assertEquals(1,exporter.statistics().pendingSpans(),"In-flight delivery is still pending after dequeue");
            assertFalse(exporter.export(span).delivered());assertEquals(1,exporter.statistics().queueDroppedSpans());
            assertTrue(((Number)exporter.budgetStatistics().get("residentEstimatedBytes")).longValue()>0);
            assertEquals(1,((Number)exporter.budgetStatistics().get("byteBudgetDroppedSpans")).longValue());
            release.countDown();assertTrue(exporter.drain(2000).drained());assertEquals(0,((Number)exporter.budgetStatistics().get("residentEstimatedBytes")).longValue());
        }finally{release.countDown();exporter.close();}
    }
    @Test void pressureChangesOnlyEffectiveSamplingAndRecoversWithHysteresis(){
        OverheadController.configure(new MonitoringConfig.Overhead(true,65536,75,35,1,50_000_000,1000));
        long base=System.nanoTime();OverheadController.observe(60000,base+300_000_000);
        assertEquals("REDUCED",OverheadController.mode());assertFalse(OverheadController.detailEnabled());assertEquals(50,OverheadController.samplingRate(100));
        OverheadController.observe(60000,base+600_000_000);assertEquals("MINIMAL",OverheadController.mode());assertEquals(1,OverheadController.samplingRate(100));assertEquals(0,OverheadController.samplingRate(0));
        OverheadController.observe(0,base+900_000_000);assertEquals("MINIMAL",OverheadController.mode());
        OverheadController.observe(0,base+1_700_000_000);assertEquals("REDUCED",OverheadController.mode());
        OverheadController.observe(0,base+2_800_000_000L);assertEquals("NORMAL",OverheadController.mode());assertTrue(OverheadController.detailEnabled());
        assertEquals(4,((List<?>)OverheadController.snapshot().get("transitions")).size());
    }
    @Test void adviceBudgetTriggersWithoutInventingProcessCpuMeasurements(){
        OverheadController.configure(MonitoringConfig.Overhead.defaults());OverheadController.recordAdvice(100_000_000);
        OverheadController.observe(0,System.nanoTime()+300_000_000);assertEquals("REDUCED",OverheadController.mode());
        assertEquals(100_000_000,((Number)OverheadController.snapshot().get("adviceMeasuredNanos")).longValue());
    }
}
