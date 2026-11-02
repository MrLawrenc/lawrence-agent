package com.agentmonitor.core.diagnostics;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class DiagnosticTaskTest {
    @TempDir Path directory;

    @Test void cancelledBeforeStartCannotPerformWorkAndDoesNotLeakThreadContext() throws Exception {
        var task=new DiagnosticTask();task.requestCancel();
        assertThrows(CancellationException.class,()->task.run(()->{fail("must not run");return null;}));
        assertEquals(DiagnosticTask.State.CANCELLED,task.progress().state());
        assertEquals(42,new DiagnosticTask().run(()->42));
        assertFalse(task.requestCancel());
    }

    @Test @Timeout(10) void cancellationWaitsForAnUninterruptibleOperationAndItsCleanup() throws Exception {
        var task=new DiagnosticTask();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var closed=new CountDownLatch(1);
        var worker=Executors.newSingleThreadExecutor();
        try {
            Future<?> result=worker.submit(()->task.run(()->{
                try(AutoCloseable resource=closed::countDown){
                    DiagnosticTask.phase("target operation",false);entered.countDown();release.await();
                    DiagnosticTask.checkpoint();return null;
                }
            }));
            assertTrue(entered.await(2,TimeUnit.SECONDS));assertTrue(task.requestCancel());
            assertFalse(task.progress().cancellable());assertFalse(result.isDone());assertEquals(1,closed.getCount());
            release.countDown();assertInstanceOf(CancellationException.class,assertThrows(ExecutionException.class,()->result.get(2,TimeUnit.SECONDS)).getCause());
            assertEquals(0,closed.getCount());assertEquals(DiagnosticTask.State.CANCELLED,task.progress().state());
        } finally {release.countDown();worker.shutdownNow();}
    }

    @Test void failedCleanupCannotBeReportedAsSuccessfulCancellation() {
        var task=new DiagnosticTask();
        var error=assertThrows(IllegalStateException.class,()->task.run(()->{
            try(AutoCloseable resource=()->{throw new IOException("close failed");}){
                task.requestCancel();DiagnosticTask.checkpoint();return null;
            }
        }));
        assertEquals(DiagnosticTask.State.FAILED,task.progress().state());
        assertEquals("close failed",error.getCause().getSuppressed()[0].getMessage());
    }

    @Test @Timeout(30) void cancellingOwnedJfrRecordingClosesOnlyThatRecordingAndKeepsTargetAlive() throws Exception {
        Process target=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin/java").toString(),"-Djava.rmi.server.hostname=127.0.0.1","-cp",System.getProperty("java.class.path"),WaitingJvm.class.getName()).redirectErrorStream(true).start();
        var worker=Executors.newSingleThreadExecutor();var task=new DiagnosticTask();Future<?> result=null;
        try {
            assertEquals("READY",target.inputReader().readLine());
            String pid=Long.toString(target.pid());
            try(var connection=new TargetJvm(pid)){
                var bean=connection.bean(jdk.management.jfr.FlightRecorderMXBean.MXBEAN_NAME,jdk.management.jfr.FlightRecorderMXBean.class);
                long userRecording=bean.newRecording();bean.setRecordingOptions(userRecording,java.util.Map.of("name","user-owned-test"));bean.startRecording(userRecording);
                result=worker.submit(()->task.run(()->DiagnosticCommands.execute(new String[]{"profile","record","--pid",pid,"--duration","60s","--output",directory.resolve("profile").toString()})));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
                while(!task.progress().phase().equals("录制 JFR")&&!result.isDone()&&System.nanoTime()<deadline)Thread.sleep(20);
                assertEquals("录制 JFR",task.progress().phase());
                assertTrue(bean.getRecordings().stream().anyMatch(r->r.getName().startsWith("agent-monitor-")));
                task.requestCancel();Future<?> running=result;
                assertInstanceOf(CancellationException.class,assertThrows(ExecutionException.class,()->running.get(5,TimeUnit.SECONDS)).getCause());
                assertTrue(target.isAlive());assertEquals(DiagnosticTask.State.CANCELLED,task.progress().state());
                assertTrue(bean.getRecordings().stream().noneMatch(r->r.getName().startsWith("agent-monitor-")));
                assertTrue(bean.getRecordings().stream().anyMatch(r->r.getId()==userRecording));bean.closeRecording(userRecording);
                assertFalse(Files.exists(directory.resolve("profile/profile.jfr")));
            }
        } finally {task.requestCancel();if(result!=null)try{result.get(5,TimeUnit.SECONDS);}catch(Exception ignored){}worker.shutdown();target.getOutputStream().write(10);target.getOutputStream().close();assertTrue(target.waitFor(10,TimeUnit.SECONDS),"Owned JFR fixture must exit after stdin completion");target.getInputStream().close();target.getErrorStream().close();assertTrue(worker.awaitTermination(5,TimeUnit.SECONDS));}
    }
    public static final class WaitingJvm {
        public static void main(String[] args)throws Exception {System.out.println("READY");System.out.flush();System.in.read();}
    }
}
