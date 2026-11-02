package com.agentmonitor.app.ui;

import com.agentmonitor.core.diagnostics.DiagnosticTask;
import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.span.SpanKind;
import com.agentmonitor.model.trace.TraceNode;
import com.agentmonitor.app.model.CallNode;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.animation.PauseTransition;
import javafx.scene.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/** Native interaction checks; sessions are explicitly fabricated and no target JVM is attached. */
final class WorkflowChecks {
    @FunctionalInterface private interface Step {void run()throws Exception;}
    private final Stage owner;private final Runnable done;private final Consumer<Throwable> fail;
    private final List<AutoCloseable> resources=new ArrayList<>();private final ObjectMapper json=new ObjectMapper();
    private WorkflowChecks(Stage owner,Runnable done,Consumer<Throwable> fail){this.owner=owner;this.done=done;this.fail=fail;}
    static void run(Stage owner,Runnable done,Consumer<Throwable> fail){var checks=new WorkflowChecks(owner,done,fail);checks.step(checks::jobs);}
    private void step(Step step){try{step.run();}catch(Throwable error){close();fail.accept(error);}}
    private void later(Step step){var pause=new PauseTransition(Duration.millis(100));pause.setOnFinished(e->step(step));pause.play();}
    private void await(BooleanSupplier ready,Step next){await(ready,System.nanoTime()+TimeUnit.SECONDS.toNanos(12),next);}
    private void await(BooleanSupplier ready,long deadline,Step next){later(()->{if(ready.getAsBoolean())next.run();else if(System.nanoTime()>deadline)throw new AssertionError("Native workflow did not settle within 12 seconds");else await(ready,deadline,next);});}
    private Scene show(Parent root){Scene scene=new Scene(root,1080,720);scene.getStylesheets().add(getClass().getResource("/com/agentmonitor/app/main.css").toExternalForm());owner.setScene(scene);owner.setWidth(1080);owner.setHeight(720);root.applyCss();root.layout();return scene;}
    private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);System.out.println("PASS "+message);}
    private void close(){for(var resource:resources)try{resource.close();}catch(Exception ignored){}resources.clear();}

    private void jobs(){
        var pane=new DiagnosticsPane("preview",()->null,()->null);resources.add(pane);Scene scene=show(pane);
        var released=new CountDownLatch(1);resources.add(released::countDown);var cleaned=new AtomicBoolean();
        pane.runAnalysis("取消验证",()->{try(AutoCloseable resource=()->cleaned.set(true)){DiagnosticTask.phase("不可中断的目标端操作",false);released.await();DiagnosticTask.checkpoint();return Map.of();}});
        await(()->((Label)pane.lookup("#diagnostics-job-status")).getText().contains("不可中断"),()->{
            ((Button)pane.lookup("#diagnostics-job-cancel")).fire();
            require(((Label)pane.lookup("#diagnostics-job-status")).getText().contains("等待当前目标端操作结束"),"Cancel explains why a target operation must finish first");
            require(!cleaned.get()&&pane.lookup("#diagnostics-refresh").isDisabled(),"Requesting cancellation cannot report completion before cleanup");released.countDown();
            await(()->((Label)pane.lookup("#diagnostics-job-status")).getText().contains("资源已关闭"),()->{
                require(cleaned.get()&&!pane.lookup("#diagnostics-refresh").isDisabled(),"Completed cancellation closes resources and restores controls");
                var attempts=new AtomicInteger();pane.runAnalysis("重试验证",()->{if(attempts.incrementAndGet()==1)throw new java.io.IOException("fixture failure");return Map.of("findings",List.of(Map.of("finding","recovered")));});
                await(()->pane.lookup("#diagnostics-job-retry").isVisible(),()->{
                    require(((Label)pane.lookup("#diagnostics-job-status")).getText().contains("fixture failure"),"Failed task keeps its actual reason next to retry");
                    ((Button)pane.lookup("#diagnostics-job-retry")).fire();
                    await(()->((Label)pane.lookup("#diagnostics-job-status")).getText().contains("已完成"),()->{
                        require(attempts.get()==2,"Retry invokes the original operation once");PreviewChecks.capture(scene,"workflow-task-retry");pane.close();timelines();
                    });
                });
            });
        });
    }
    private static MouseEvent mouse(Canvas canvas,javafx.event.EventType<MouseEvent> type,double x,double y){return new MouseEvent(type,x,y,x,y,MouseButton.PRIMARY,1,false,false,false,false,type==MouseEvent.MOUSE_PRESSED,false,false,false,false,true,new PickResult(canvas,new javafx.geometry.Point3D(x,y,0),0));}
    private void timelines(){
        var pane=new DiagnosticsPane("preview",()->null,()->null);resources.add(pane);
        var rows=json.createArrayNode();rows.addObject().put("method","Controller.request").put("kind","HTTP_SERVER").put("startedAtMillis",1000).put("endedAtMillis",1100);
        rows.addObject().put("method","SQL query").put("kind","SQL").put("startedAtMillis",1020).put("endedAtMillis",1030);
        rows.addObject().put("method","GC").put("kind","jfr").put("startedAtMillis",1050).put("endedAtMillis",1060);
        pane.showResult(json.createObjectNode().set("events",rows));Scene scene=show(pane);
        @SuppressWarnings("unchecked") TableView<JsonNode> table=(TableView<JsonNode>)pane.lookup(".table-view");
        await(()->table.getItems().size()==3,()->{
            Canvas canvas=(Canvas)pane.lookup("#evidence-timeline-canvas");double width=canvas.getWidth()-200;
            canvas.fireEvent(mouse(canvas,MouseEvent.MOUSE_PRESSED,190+.21*width,80));canvas.fireEvent(mouse(canvas,MouseEvent.MOUSE_RELEASED,190+.29*width,80));
            await(()->table.getItems().size()==2,()->{
                require(table.getItems().stream().noneMatch(n->n.path("method").asText().equals("GC")),"Timeline brush filters the actual evidence table by overlapping timestamps");
                ((Button)pane.lookup("#evidence-timeline-reset")).fire();
                await(()->table.getItems().size()==3,()->{
                    require(table.getItems().size()==3,"Reset clears the time filter");double click=190+.25*(canvas.getWidth()-200);canvas.fireEvent(mouse(canvas,MouseEvent.MOUSE_PRESSED,click,77));canvas.fireEvent(mouse(canvas,MouseEvent.MOUSE_RELEASED,click,77));
                    await(()->table.getSelectionModel().getSelectedItem()!=null&&table.getSelectionModel().getSelectedItem().path("method").asText().equals("SQL query"),()->{
                        require(table.getItems().size()==3,"Reset restores all rows and clicking SQL selects its evidence");PreviewChecks.capture(scene,"workflow-linked-timeline");pane.close();calls();
                    });
                });
            });
        });
    }
    private TraceNode call(String name,long timestamp,long nanos,String span,boolean error){return new TraceNode("fixture."+name,"execute","worker",nanos,error,0,"()V","","","fixture stack",SpanKind.BUSINESS,Map.of("agent.span.id",span,"agent.span.started_at_epoch_millis",Long.toString(timestamp)),"fixture-trace");}
    private void calls(){
        var root=call("Root",1000,100000000,"root",false);root.addChild(call("SQL",1020,10000000,"first",true));root.addChild(call("SQL",1060,10000000,"second",false));root.freeze();
        var pane=new CallTreePane();resources.add(pane);pane.showOfflineTraces(List.of(root));Scene scene=show(pane);
        @SuppressWarnings("unchecked") TreeTableView<CallNode> tree=(TreeTableView<CallNode>)pane.lookup("#call-tree-table");
        await(()->tree.getRoot().getChildren().size()==1,()->{
            tree.getSelectionModel().select(tree.getRoot().getChildren().get(0));
            ((Button)pane.lookupAll(".button").stream().filter(n->((Button)n).getText().equals("瀑布图")).findFirst().orElseThrow()).fire();
            await(()->pane.lookup("#evidence-timeline-canvas")!=null,()->{
                Canvas canvas=(Canvas)pane.lookup("#evidence-timeline-canvas");double click=190+.25*(canvas.getWidth()-200);
                canvas.fireEvent(mouse(canvas,MouseEvent.MOUSE_PRESSED,click,77));canvas.fireEvent(mouse(canvas,MouseEvent.MOUSE_RELEASED,click,77));
                require(tree.getSelectionModel().getSelectedItem().getValue().getAttributes().get("agent.span.id").equals("first"),"Waterfall selects the exact repeated method using its tree path");
                require(pane.lookup("#evidence-timeline-canvas")!=null,"Selecting a waterfall item keeps the graph and detail position");
                PreviewChecks.capture(scene,"workflow-call-waterfall");
                pane.focusCall(json.createObjectNode().put("traceId","fixture-trace").put("spanId","second"));
                await(()->tree.getSelectionModel().getSelectedItem()!=null&&"second".equals(tree.getSelectionModel().getSelectedItem().getValue().getAttributes().get("agent.span.id")),()->{require(true,"Evidence navigation resolves the exact span in the offline call tree");pane.close();baselines();});
            });
        });
    }
    private MonitoringSession fixture(String build)throws Exception {
        Path output=Files.createDirectories(Path.of("build/ui-preview/experiment-fixtures"));var session=MonitoringSession.create(output.toAbsolutePath().toString());
        ObjectNode supplied=json.valueToTree(MonitoringConfig.defaults());supplied.putObject("experiment").put("workloadId","orders").put("datasetId","v1").put("loadProfile","10 clients, 1m").put("warmupId","2000 requests").put("buildId",build);
        ObjectNode report=json.createObjectNode().put("schemaVersion",1).put("sessionId",session.sessionId()).put("status","COMPLETED").put("captureComplete",true).put("analysisReady",true);report.set("monitoringConfig",supplied);
        var attributes=report.putObject("resource").putObject("attributes");for(String key:List.of("service.name","process.runtime.name","process.runtime.version","host.name","host.arch","host.available_processors","os.name","os.version","process.runtime.arguments.sha256"))attributes.put(key,"fixture-"+key);
        var quality=report.putObject("captureQuality").put("acceptedSpans",2).put("discardedSpans",0).put("unreceivedSpans",0);quality.putObject("agentExporter").put("reported",true).put("enqueuedSpans",2).put("queueDroppedSpans",0).put("deliveryDroppedSpans",0).put("pendingSpans",0).put("rejectedDestinations",0);
        json.writeValue(session.performanceReportPath().toFile(),report);json.writeValue(session.spansDirectory().resolve("manifest.json").toFile(),Map.of("captureComplete",true,"writtenSpans",2));json.writeValue(session.agentLogsDirectory().resolve("overhead.json").toFile(),Map.of("sessionId",session.sessionId(),"status","FINAL","policy",Map.of("mode","NORMAL","transitions",List.of(),"omittedTransitions",0)));return session;
    }
    private void baselines()throws Exception {
        DesktopPreferences.get().put("experiment.before","");DesktopPreferences.get().put("experiment.after","");
        var before=fixture("before-build");var after=fixture("after-build");var config=new AtomicReference<>(MonitoringConfig.defaults());var captures=new AtomicInteger();var compared=new AtomicReference<List<Path>>();var reference=new AtomicReference<BaselinePane>();
        BaselinePane pane=new BaselinePane(config::get,experiment->{var c=config.get();config.set(new MonitoringConfig(c.schemaVersion(),c.scope(),c.sampling(),c.output(),c.resource(),c.dependencies(),c.analysis(),c.diagnostics(),c.overhead(),experiment));},()->{captures.incrementAndGet();reference.get().captureStarted(after.sessionDirectory());return true;},(a,b)->compared.set(List.of(a,b)),ignored->{});reference.set(pane);resources.add(pane);Scene scene=show(pane);
        require(pane.lookup("#experiment-start-after").isDisabled(),"After capture requires a bound baseline with complete quality evidence");
        pane.bindSession("before",before.sessionDirectory(),true);
        await(()->!pane.lookup("#experiment-start-after").isDisabled(),()->{
            ((Button)pane.lookupAll(".button").stream().filter(n->((Button)n).getText().equals("保存到下次采集")).findFirst().orElseThrow()).fire();
            ((Button)pane.lookup("#experiment-start-after")).fire();require(captures.get()==0&&((Label)pane.lookup("#experiment-status")).getText().contains("buildId"),"After capture requires a distinct saved build version");
            var fields=((javafx.scene.layout.VBox)pane.getContent()).getChildren().stream().filter(n->n instanceof TextField).map(n->(TextField)n).toList();fields.get(4).setText("after-build");
            ((Button)pane.lookupAll(".button").stream().filter(n->((Button)n).getText().equals("保存到下次采集")).findFirst().orElseThrow()).fire();((Button)pane.lookup("#experiment-start-after")).fire();
            require(captures.get()==1&&pane.lookup("#experiment-compare").isDisabled(),"Experiment tracks a running after capture without enabling premature comparison");pane.captureFinished(after.sessionDirectory(),true);
            await(()->!pane.lookup("#experiment-compare").isDisabled(),()->{
                ((Button)pane.lookup("#experiment-compare")).fire();require(compared.get().equals(List.of(before.sessionDirectory().toRealPath(),after.sessionDirectory().toRealPath())),"Stopped experiment binds the correct before and after sessions for strict comparison");PreviewChecks.capture(scene,"workflow-bound-experiment");
                pane.bindSession("after",before.sessionDirectory(),false);
                await(()->((Label)pane.lookup("#experiment-after-status")).getText().contains(before.sessionId()),()->{
                    require(pane.lookup("#experiment-compare").isDisabled(),"The same logical capture cannot occupy both experiment roles");
                    Path bad=Files.createTempDirectory(Path.of("build/ui-preview"),"invalid-experiment-");pane.bindSession("after",bad,false);
                    await(()->((Label)pane.lookup("#experiment-after-status")).getText().contains("绑定失败"),()->{require(pane.lookup("#experiment-compare").isDisabled(),"Invalid bindings retain an actionable error and block comparison");pane.close();close();done.run();});
                });
            });
        });
    }
}
