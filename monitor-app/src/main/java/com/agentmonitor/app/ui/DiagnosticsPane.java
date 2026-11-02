package com.agentmonitor.app.ui;

import com.agentmonitor.core.diagnostics.DiagnosticCommands;
import com.agentmonitor.core.diagnostics.DiagnosticTask;
import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.service.MonitoringRuntime;
import com.agentmonitor.core.evidence.SessionEvidenceReader;
import com.agentmonitor.core.evidence.EvidenceStore;
import com.agentmonitor.core.evidence.TimelineService;
import com.agentmonitor.core.diagnostics.RequestStatistics;
import com.agentmonitor.core.diagnostics.SymptomDiagnosis;
import com.agentmonitor.core.diagnostics.DistributedTrace;
import com.agentmonitor.app.util.SessionArtifactOpener;
import com.fasterxml.jackson.databind.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;
import javafx.util.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Native evidence workbench. File parsing and live JVM operations never run on the FX thread. */
public final class DiagnosticsPane extends BorderPane implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final String pid; private final Supplier<MonitoringSession> sessionSupplier;private final Supplier<MonitoringRuntime> runtimeSupplier;
    private volatile Path history;private final Label status=new Label("等待采集或选择历史会话");private final Label location=new Label();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"diagnostic-workbench");t.setDaemon(true);return t;});
    private final Button refresh=new Button("刷新证据");private final Timeline timer;
    private final Map<String,EvidenceTablePane> tables=new HashMap<>();
    private final SessionEvidenceReader reader=new SessionEvidenceReader();
    private final List<MenuItem> liveActions=new ArrayList<>();
    private java.util.function.Consumer<Path> onSessionSelected;
    private Runnable onRecapture=()->{};
    private java.util.function.Consumer<JsonNode> onCallSelected=ignored->{};
    public void setOnCallSelected(java.util.function.Consumer<JsonNode> action){onCallSelected=action;}
    public void setOnRecapture(Runnable action){onRecapture=action;}
    public void compareSessions(){compare();}
    public void compareSessions(Path before,Path after){command("compare","--before",before.toString(),"--after",after.toString());}
    private long sessionStartMillis;
    private final Label timeRange=new Label();
    private final ComboBox<String> metric=new ComboBox<>();private final LineChart<Number,Number> chart;
    private boolean busy,reading,closed,pendingReload;
    private Path evidenceDirectory;
    private DiagnosticTask activeTask;
    private final Label jobStatus=new Label();
    private final ProgressBar jobProgress=new ProgressBar();
    private final Button cancelJob=new Button("取消任务"),retryJob=new Button("重试任务");
    private final Timeline jobTimer=new Timeline(new KeyFrame(Duration.millis(250),e->updateJobProgress()));
    private final FlowPane jobControls=new FlowPane(8,6,jobProgress,jobStatus,cancelJob,retryJob);
    private Runnable retryAction;

    private JsonNode lastMetricsRows;
    private int lastMetricIndex=-1;
    private final BorderPane evidenceView=new BorderPane();
    private final List<Control> operations=new ArrayList<>();
    public DiagnosticsPane(String pid,Supplier<MonitoringSession> session,Supplier<MonitoringRuntime> runtime){
        this.pid=pid;sessionSupplier=session;runtimeSupplier=runtime;getStyleClass().add("diagnostics-pane");setId("diagnostics-pane");
        refresh.setId("diagnostics-refresh");status.setId("diagnostics-status");
        Button historical=new Button("加载历史会话…"),current=new Button("返回本次会话"),compare=new Button("对比会话…"),open=new Button("打开会话目录");
        historical.setOnAction(e->{DirectoryChooser c=new DirectoryChooser();c.setTitle("选择完整会话目录");var f=c.showDialog(getScene().getWindow());if(f!=null)selectSession(f.toPath());});
        current.setOnAction(e->selectSession(null));compare.setOnAction(e->compare());open.setOnAction(e->{Path p=directory();if(p!=null)open(p);});refresh.setOnAction(e->reload());
        MenuButton capture=new MenuButton("采集诊断");capture.setId("diagnostics-capture");
        add(capture,"连续线程快照（3 次）",()->command("diag","threads","--pid",pid,"--count","3"));
        add(capture,"刷新活动会话类来源",()->{Path dir=directory();if(dir!=null)command("diag","classes","--session",dir.toString(),"--refresh");});
        add(capture,"运行环境",()->command("diag","environment","--pid",pid));
        add(capture,"对象直方图…",()->saveCommand("对象直方图","json","diag","histogram","--pid",pid));
        add(capture,"堆转储（可能暂停目标）…",()->saveCommand("堆转储（可能暂停 JVM，占用较大磁盘空间）","hprof","diag","heap-dump","--pid",pid));
        add(capture,"录制 JFR（30 秒）…",()->{DirectoryChooser c=new DirectoryChooser();c.setTitle("选择 JFR 输出目录");var f=c.showDialog(getScene().getWindow());if(f!=null)command("profile","record","--pid",pid,"--duration","30s","--output",f.toPath().toString());});
        liveActions.addAll(capture.getItems());
        add(capture,"导入 JFR 分析…",()->{FileChooser f=new FileChooser();f.setTitle("选择 JFR 文件");f.getExtensionFilters().add(new FileChooser.ExtensionFilter("JFR","*.jfr"));var selected=f.showOpenDialog(getScene().getWindow());if(selected==null)return;DirectoryChooser d=new DirectoryChooser();d.setTitle("选择分析输出目录");var out=d.showDialog(getScene().getWindow());if(out!=null)command("profile","analyze","--recording",selected.toString(),"--output",out.toString());});
        Menu async=new Menu("async-profiler 火焰图…");for(String mode:List.of("cpu","wall","alloc","lock")){MenuItem item=new MenuItem(mode);item.setOnAction(e->asyncProfile(mode));async.getItems().add(item);}capture.getItems().add(async);liveActions.add(async);
        add(capture,"打开本次火焰图",()->{Path dir=directory();if(dir!=null)open(dir.resolve("diagnostics/flamegraph.html"));});
        add(capture,"对比对象直方图…",()->compareHistograms());
        operations.addAll(List.of(refresh,capture,historical,current,compare,open));
        MenuButton sessions=new MenuButton("会话操作");
        for(Button source:List.of(historical,current,compare,open)){MenuItem item=new MenuItem(source.getText());item.disableProperty().bind(source.disabledProperty());item.setOnAction(e->source.fire());sessions.getItems().add(item);}
        for(Control control:List.of(refresh,capture,sessions))control.getStyleClass().add("diagnostics-action");
        MenuButton investigate=new MenuButton("分析证据");investigate.setId("diagnostics-investigate");
        for(String symptom:List.of("cpu","latency","memory","errors"))add(investigate,switch(symptom){case "cpu"->"CPU 偏高";case "latency"->"响应慢";case "memory"->"内存增长";default->"异常与错误";},()->{Path dir=directory();analyze(()->new SymptomDiagnosis().analyze(dir,symptom));});
        add(investigate,"接口统计",()->{Path dir=directory();analyze(()->new RequestStatistics().read(dir));});
        add(investigate,"关联时间线…",()->focusTimeline(null));
        add(investigate,"跨服务关联…",this::distributed);
        operations.add(investigate);
        javafx.scene.layout.FlowPane actions=new javafx.scene.layout.FlowPane(8,6,refresh,capture,investigate,sessions);
        location.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);location.setMaxWidth(Double.MAX_VALUE);
        status.setTextOverrun(OverrunStyle.ELLIPSIS);
        location.getStyleClass().add("hint-label");status.getStyleClass().add("diagnostics-status");
        HBox context=new HBox(16,location,status);context.setAlignment(javafx.geometry.Pos.CENTER_LEFT);HBox.setHgrow(location,Priority.ALWAYS);
        warningText.setEditable(false);warningText.setWrapText(true);warningText.setPrefRowCount(2);warningText.setMaxHeight(64);warningText.setAccessibleText("证据读取问题");warningText.setVisible(false);warningText.setManaged(false);
        jobStatus.setId("diagnostics-job-status");jobStatus.setWrapText(true);jobStatus.setMaxWidth(520);
        cancelJob.setId("diagnostics-job-cancel");retryJob.setId("diagnostics-job-retry");jobProgress.setPrefWidth(100);
        jobControls.setVisible(false);jobControls.setManaged(false);retryJob.setVisible(false);retryJob.setManaged(false);
        cancelJob.setOnAction(e->{if(activeTask!=null){activeTask.requestCancel();updateJobProgress();}});
        retryJob.setOnAction(e->{if(retryAction!=null)retryAction.run();});
        jobTimer.setCycleCount(Timeline.INDEFINITE);
        VBox header=new VBox(10,actions,context,warningText,jobControls);header.setPadding(new Insets(0,0,14,0));
        TabPane tabs=new TabPane();tabs.setId("diagnostics-tabs");tabs.getStyleClass().add("diagnostics-tabs");tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        NumberAxis x=new NumberAxis(),y=new NumberAxis();x.setLabel("会话相对时间（秒）");y.setLabel("百分比");chart=new LineChart<>(x,y);chart.setAnimated(false);chart.setCreateSymbols(false);chart.setLegendVisible(false);chart.setMinHeight(220);
        metric.getItems().addAll("进程 CPU (%)","堆使用率 (%)","平台线程数");metric.getSelectionModel().selectFirst();metric.setOnAction(e->updateChart(lastMetricsRows==null?JSON.createArrayNode():lastMetricsRows));metric.setAccessibleText("时间线指标");metric.getStyleClass().add("diagnostics-metric");
        Label caveat=new Label("采样与慢调用使用时间戳关联。GC 重叠只是线索；CPU 采样和方法耗时有不同含义。JFR 默认需在 YAML 开启 diagnostics.jfr。");caveat.setWrapText(true);
        VBox timeline=new VBox(12,metric,timeRange,chart,caveat);timeline.setPadding(new Insets(16));VBox.setVgrow(chart,Priority.ALWAYS);tabs.getTabs().add(new Tab("指标时间线",timeline));
        tableTab(tabs,"live","未完成调用",new String[]{"方法","已运行 ms","线程","状态"},new String[]{"method","elapsedMillis","threadName","state"});
        tableTab(tabs,"threads","线程与锁",new String[]{"线程","状态","锁持有者","锁"},new String[]{"name","state","lockOwnerName","lock"});
        tableTab(tabs,"sql","SQL / 连接等待",new String[]{"SQL 指纹 / 连接池","观测次数","累计耗时","最大耗时"},new String[]{"key","observedCalls","inclusiveNanos","maxNanos"});
        tableTab(tabs,"exceptions","异常",new String[]{"异常类型与栈","观测数（详情注明口径）","累计耗时"},new String[]{"key","observedErrors","inclusiveNanos"});
        tableTab(tabs,"async","异步任务",new String[]{"来源 Trace","任务 Trace","排队耗时","执行耗时","线程"},new String[]{"parentTraceId","traceId","queueNanos","executionNanos","thread"});
        tableTab(tabs,"pools","线程池 / 连接池",new String[]{"池 / 类","活跃","队列 / 等待连接","大小"},new String[]{"poolLabel","activeLabel","queuedLabel","sizeLabel"});
        tableTab(tabs,"memory","内存与 GC",new String[]{"证据","时间 / 阶段","字节 / 时长"},new String[]{"name","time","value"});
        tableTab(tabs,"classes","类来源",new String[]{"类","ClassLoader","来源 JAR / 目录"},new String[]{"className","loader","source"});
        tableTab(tabs,"artifacts","证据与采集质量",new String[]{"文件 / 字段","内容"},new String[]{"name","value"});
        ListView<String> sections=new ListView<>();sections.setId("diagnostics-sections");sections.getStyleClass().add("diagnostics-sections");
        sections.getItems().setAll(tabs.getTabs().stream().map(Tab::getText).toList());sections.setPrefWidth(160);sections.setMinWidth(144);sections.setMaxWidth(180);
        sections.getSelectionModel().selectedIndexProperty().addListener((o,a,b)->{if(b.intValue()>=0)tabs.getSelectionModel().select(b.intValue());});
        tabs.getSelectionModel().selectedIndexProperty().addListener((o,a,b)->sections.getSelectionModel().select(b.intValue()));
        sections.getSelectionModel().selectFirst();
        evidenceView.setCenter(tabs);evidenceView.setLeft(sections);evidenceView.getStyleClass().add("diagnostics-evidence");
        setCenter(evidenceView);setTop(header);
        timer=new Timeline(new KeyFrame(Duration.seconds(2),e->{if(getScene()!=null&&history==null&&runtimeSupplier.get()!=null)reload(false);}));timer.setCycleCount(Timeline.INDEFINITE);timer.play();
        reload();
    }
    @Override public void close(){
        closed=true;timer.stop();jobTimer.stop();if(activeTask!=null)activeTask.requestCancel();
        tables.values().forEach(EvidenceTablePane::close);resultClosers.forEach(EvidenceTablePane::close);worker.shutdown();
        if(busy){Thread cleanup=new Thread(()->{try{while(!worker.awaitTermination(30,TimeUnit.SECONDS))com.agentmonitor.core.util.AppLog.warn("等待诊断任务结束与目标端资源关闭");}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}},"diagnostic-close-wait");cleanup.setDaemon(false);cleanup.start();}
    }
    public void refreshEvidence(){reload();}
    private void tableTab(TabPane tabs,String key,String label,String[] names,String[] fields){
        EvidenceTablePane pane=new EvidenceTablePane(label,names,fields);pane.setOnFocus(this::focusTimeline);
        tabs.getTabs().add(new Tab(label,pane));tables.put(key,pane);
    }
    private void fill(String key,JsonNode rows){tables.get(key).setRows(rows);}
    public void setOnSessionSelected(java.util.function.Consumer<Path> action){onSessionSelected=action;}
    private void selectSession(Path path){if(onSessionSelected!=null)onSessionSelected.accept(path);else setViewedSession(path);}
    public void setViewedSession(Path path){history=path;updateContext();reload();}
    private void updateContext(){
        liveActions.forEach(item->item.setDisable(history!=null));
        location.setText((history==null?"查看本次会话":"查看历史会话")+(directory()==null?" · 尚无证据":" · "+directory().getFileName())+" · 实时目标 PID "+pid+(history!=null?"（历史模式，实时采集已禁用）":""));
        location.setTooltip(new Tooltip(location.getText()+(directory()==null?"":"\n"+directory().toAbsolutePath())));
    }
    private Path directory(){MonitoringSession s=sessionSupplier.get();return history!=null?history:s==null?null:s.sessionDirectory();}
    private void reload(){reload(true);}
    private void reload(boolean manual){if(closed)return;Path dir=directory();
        if(!Objects.equals(dir,evidenceDirectory)){clearEvidence();evidenceDirectory=dir;setCenter(evidenceView);}
        updateContext();
        if(dir==null){clearEvidence();status.setText("等待采集或选择历史会话");return;}
        if(busy||reading){pendingReload=true;return;}
        if(manual)status.setText("正在读取会话证据…");
        task(()->project(dir),value->{
            if(!Objects.equals(dir,directory())){clearEvidence();Platform.runLater(this::reload);return;}
            Snapshot snapshot=(Snapshot)value;JsonNode root=snapshot.root();
            snapshot.tables().forEach(this::fill);
            try{sessionStartMillis=java.time.Instant.parse(root.path("identity").path("startedAt").asText()).toEpochMilli();}catch(Exception absent){sessionStartMillis=0;}
            updateChart(root.path("metrics"));JsonNode m=root.path("manifest");
            String warnings=String.join("\n",snapshot.warnings());
            status.setText("诊断："+UiText.status(m.path("status").asText("未生成"))+" · 样本 "+m.path("samples").asInt()+" · 读取问题 "+snapshot.warnings().size()+" · 更新 "+java.time.LocalTime.now().withNano(0));
            status.setTooltip(new Tooltip(warnings.isBlank()?"各类证据已更新":warnings));
            status.setAccessibleText(status.getText()+"\n"+warnings);
            warningText.setText(warnings);warningText.setVisible(!warnings.isBlank());warningText.setManaged(!warnings.isBlank());
        },false);
    }
    private final TextArea warningText=new TextArea();
    private record Snapshot(JsonNode root,Map<String,JsonNode> tables,List<String> warnings) { }
    private Snapshot project(Path dir)throws Exception {
            JsonNode root=JSON.valueToTree(reader.read(dir));
            Map<String,JsonNode> projected=new LinkedHashMap<>();projected.put("live",root.path("live").path("roots"));projected.put("threads",root.path("threads").path("threads"));projected.put("classes",root.path("classes").path("classes"));
            JsonNode aggregate=root.path("report").path("diagnosticAggregates");var sql=JSON.createArrayNode();aggregate.path("sql").forEach(sql::add);aggregate.path("connectionAcquisition").forEach(sql::add);for(JsonNode p:aggregate.path("repeatedQueries")){var row=(com.fasterxml.jackson.databind.node.ObjectNode)p.deepCopy();row.put("key","重复查询候选 · "+p.path("sqlFingerprint").asText());row.set("observedCalls",p.path("observedExecutions"));sql.add(row);}projected.put("sql",sql);
            var exceptions=JSON.createArrayNode();aggregate.path("exceptions").forEach(exceptions::add);root.path("jfr").path("exceptionThrows").fields().forEachRemaining(e->{var row=exceptions.addObject();row.put("key","JFR throw · "+e.getKey());row.set("observedErrors",e.getValue());row.put("countKind","JFR throw events, distinct from propagated error spans");});projected.put("exceptions",exceptions);projected.put("async",aggregate.path("asyncLinks"));
            var pools=JSON.createArrayNode();for(JsonNode p:root.path("live").path("threadPools")){var row=p.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)row).put("poolLabel",p.path("class").asText()+"@"+p.path("identity").asText()).put("activeLabel",p.path("active").asText()).put("queuedLabel",p.path("queued").asText()).put("sizeLabel",p.path("size").asText());pools.add(row);}
            for(JsonNode p:root.path("latest").path("pools").path("registeredPools")){var row=p.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)row).put("poolLabel",p.path("objectName").asText()).put("activeLabel",p.path("ActiveConnections").asText(p.path("ActiveCount").asText())).put("queuedLabel",p.path("ThreadsAwaitingConnection").asText(p.path("QueueSize").asText())).put("sizeLabel",p.path("TotalConnections").asText(p.path("PoolSize").asText()));pools.add(row);}projected.put("pools",pools);
            var memory=JSON.createArrayNode();var trend=memory.addObject();trend.put("name","堆使用趋势（不等于泄漏）");trend.put("value",root.path("manifest").path("heapTrend").toString());for(JsonNode event:root.path("jfr").path("gcEvents")){var row=memory.addObject();row.put("name",event.path("event").asText());row.put("time",event.path("startedAtEpochMillis").asText()+" "+event.path("when").asText());row.put("value",event.has("heapUsedBytes")?event.path("heapUsedBytes").asText()+" bytes":event.path("durationNanos").asText()+" ns");row.set("evidence",event);}projected.put("memory",memory);
            var artifacts=JSON.createArrayNode();for(String k:List.of("manifest","environment","jfr","trigger","report")){var r=artifacts.addObject();r.put("name",k);r.put("value",root.path(k).path("status").asText("可从会话目录查看原始文件")+" · 字段数 "+root.path(k).size());}projected.put("artifacts",artifacts);

            List<String> warnings=new ArrayList<>();root.path("warnings").forEach(w->warnings.add(w.asText()));
            return new Snapshot(root,projected,warnings);
    }
    private void clearEvidence(){
        warningText.clear();warningText.setVisible(false);warningText.setManaged(false);
        for(EvidenceTablePane table:tables.values())table.setRows(JSON.createArrayNode());
        chart.getData().clear();lastMetricsRows=null;lastMetricIndex=-1;timeRange.setText("");
    }
    private void updateChart(JsonNode rows){
        int selected=metric.getSelectionModel().getSelectedIndex();if(selected==lastMetricIndex&&rows.equals(lastMetricsRows))return;
        lastMetricsRows=rows;lastMetricIndex=selected;chart.getData().clear();
        long start=sessionStartMillis;boolean fixed=start>0;if(!fixed)start=rows.isEmpty()?0:rows.get(0).path("timestampMillis").asLong();
        ((NumberAxis)chart.getXAxis()).setLabel(fixed?"会话相对时间（秒）":"样本窗口相对时间（会话开始时间未知）");
        ((NumberAxis)chart.getYAxis()).setLabel(selected==2?"线程数":"百分比");
        XYChart.Series<Number,Number> series=null;
        for(JsonNode row:rows){JsonNode raw=selected==0?row.path("ProcessCpuLoad"):selected==1?row.path("heap").path("used"):row.path("threadCount");
            if(!raw.isNumber()||raw.asDouble()<0){series=null;continue;}
            double value=selected==0?raw.asDouble()*100:selected==1?raw.asDouble()/Math.max(1,row.path("heap").path(row.path("heap").path("max").asDouble()>0?"max":"committed").asDouble())*100:raw.asDouble();
            if(series==null){series=new XYChart.Series<>();chart.getData().add(series);}
            series.getData().add(new XYChart.Data<>((row.path("timestampMillis").asLong()-start)/1000.0,value));
        }
        if(!rows.isEmpty())timeRange.setText("显示最近 "+rows.size()+" 个有效样本 · "+java.time.Instant.ofEpochMilli(rows.get(0).path("timestampMillis").asLong())+" — "+java.time.Instant.ofEpochMilli(rows.get(rows.size()-1).path("timestampMillis").asLong()));
    }
    interface Work { Object run() throws Exception; }
    private void task(Work work,java.util.function.Consumer<Object> done){task(work,done,true);}
    private void task(Work work,java.util.function.Consumer<Object> done,boolean blockControls){task("分析诊断证据",work,done,blockControls);}
    void runAnalysis(String title,Work work){task(title,work,value->showResult(JSON.valueToTree(value)),true);}
    private void updateJobProgress(){
        if(activeTask==null)return;
        var progress=activeTask.progress();
        String phase=progress.cancelRequested()?(progress.cancellable()?"正在取消，等待资源关闭":"已请求取消；等待当前目标端操作结束并关闭资源"):progress.phase();
        jobStatus.setText(phase+String.format(Locale.ROOT," · 已用 %.1f 秒",progress.elapsedMillis()/1000.0));
        jobStatus.setAccessibleText(jobStatus.getText());cancelJob.setDisable(progress.cancelRequested());
        cancelJob.setText(progress.cancellable()?"取消任务":"请求取消");
    }
    private void task(String title,Work work,java.util.function.Consumer<Object> done,boolean blockControls){
        if(closed)return;
        if(busy||(reading&&!blockControls)){if(blockControls)status.setText("已有诊断操作正在执行，请等待完成后再试。");return;}
        Path context=directory();
        DiagnosticTask job=blockControls?new DiagnosticTask():null;
        if(blockControls){
            busy=true;activeTask=job;operations.forEach(c->c.setDisable(true));
            jobControls.setVisible(true);jobControls.setManaged(true);jobProgress.setProgress(-1);
            retryJob.setVisible(false);retryJob.setManaged(false);cancelJob.setVisible(true);cancelJob.setManaged(true);cancelJob.setDisable(false);
            retryAction=()->{if(!Objects.equals(context,directory())){jobStatus.setText("查看会话已变化，请在当前会话重新发起操作。");return;}task(title,work,done,true);};
            jobStatus.setText(title+" · 准备执行");jobTimer.playFromStart();
        }else reading=true;
        worker.execute(()->{
            Exception failure=null;
            try{
                Object value=job==null?work.run():job.run(()->{DiagnosticTask.phase(title,true);return work.run();});
                Platform.runLater(()->{if(!closed&&(blockControls||!busy)&&Objects.equals(context,directory()))done.accept(value);});
            }catch(Exception error){failure=error;
                Platform.runLater(()->{if(!closed&&!blockControls&&Objects.equals(context,directory()))clearEvidence();if(!closed&&(blockControls||!busy)&&Objects.equals(context,directory()))status.setText(error instanceof CancellationException?"诊断已取消，已生成的证据保留。":"操作失败："+Objects.toString(error.getMessage(),error.getClass().getSimpleName())+"。请检查目标进程、权限或所选文件后重试。");});
            }finally{
                Exception outcome=failure;
                Platform.runLater(()->{
                    if(blockControls){
                        busy=false;jobTimer.stop();activeTask=null;
                        if(!closed){
                            var progress=job.progress();boolean failed=progress.state()==DiagnosticTask.State.FAILED;
                            jobProgress.setProgress(failed?0:1);cancelJob.setVisible(false);cancelJob.setManaged(false);
                            retryJob.setVisible(failed);retryJob.setManaged(failed);
                            jobStatus.setText(title+" · "+(failed?"失败："+Objects.toString(outcome==null?null:outcome.getMessage(),"请检查日志"):progress.state()==DiagnosticTask.State.CANCELLED?"已取消，资源已关闭，已有证据保留":"已完成")+String.format(Locale.ROOT," · %.1f 秒",progress.elapsedMillis()/1000.0));
                            operations.forEach(c->c.setDisable(false));updateContext();
                        }
                    }else reading=false;
                    if(pendingReload&&!closed){pendingReload=false;reload();}
                });
            }
        });
    }
    private void command(String... args){
        String title=String.join(" ",Arrays.copyOf(args,Math.min(2,args.length)));
        task(title,()->JSON.valueToTree(DiagnosticCommands.execute(args)),result->showResult((JsonNode)result),true);
    }
    void showResult(JsonNode result){

        TabPane sections=new TabPane();sections.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        List<EvidenceTablePane> results=new ArrayList<>();
        EvidenceTablePane eventTable=null;Tab eventTab=null;
        for(String key:List.of("findings","endpoints","events","spans","edges","methods","sql","classes","sources","missingParents"))if(result.path(key).isArray()){
            var rows=result.path(key).deepCopy();
            if(key.equals("methods")||key.equals("sql"))for(JsonNode row:rows)if(row.isObject()){
                var object=(com.fasterxml.jackson.databind.node.ObjectNode)row;
                object.set("beforeInclusiveNanos",row.path("before").path("inclusiveNanos"));object.set("afterInclusiveNanos",row.path("after").path("inclusiveNanos"));
            }
            LinkedHashSet<String> fields=new LinkedHashSet<>();for(JsonNode row:rows){row.fieldNames().forEachRemaining(fields::add);if(fields.size()>=8)break;}
            List<String> columns=fields.stream().filter(f->!f.equals("before")&&!f.equals("after")).limit(8).toList();
            EvidenceTablePane pane=new EvidenceTablePane(key,columns.toArray(String[]::new),columns.toArray(String[]::new));pane.setRows(rows);pane.setOnFocus(this::focusTimeline);results.add(pane);Tab tab=new Tab(resultLabel(key),pane);sections.getTabs().add(tab);if(key.equals("events")){eventTable=pane;eventTab=tab;}
        }
        if(eventTable!=null){
            Path context=directory();EvidenceTablePane linked=eventTable;
            var timeline=new EvidenceTimelinePane();timeline.setEvents(result.path("events"));timeline.setShowRawDetails(false);linked.setDetailsOrientation(javafx.geometry.Orientation.HORIZONTAL);
            timeline.setOnSelect(linked::focusEvidence);timeline.setOnRangeChanged(linked::setTimeRange);linked.setOnSelection(timeline::highlightEvidence);
            linked.setResolver(row->row.path("evidenceId").asText().isBlank()||context==null?row:JSON.valueToTree(new EvidenceStore().get(context,row.path("evidenceId").asText())));
            linked.setOnFocus("定位调用链",row->{
                if(row.path("traceId").asText().isBlank()){status.setText("该证据没有 Trace ID；可在摘要中查看 SQL、异常或线程原值。");return;}
                task("核验调用来源",()->{
                    JsonNode verified=row.path("evidenceId").asText().isBlank()?row:JSON.valueToTree(new EvidenceStore().get(context,row.path("evidenceId").asText()));
                    var target=JSON.createObjectNode();target.put("traceId",row.path("traceId").asText());target.put("method",row.path("method").asText());
                    target.put("spanId",verified.path("value").path("spanId").asText(verified.path("value").path("attributes").path("agent.span.id").asText()));return target;
                },value->onCallSelected.accept((JsonNode)value),true);
            });
            SplitPane linkedView=new SplitPane(timeline,linked);linkedView.setOrientation(javafx.geometry.Orientation.VERTICAL);linkedView.setDividerPositions(.5);
            eventTab.setText("多轨时间线与证据");eventTab.setContent(linkedView);
        }
        TextArea raw=new TextArea("正在整理原始证据…");raw.setEditable(false);raw.setAccessibleText("诊断结果 JSON");
        worker.execute(()->{String text=result.toPrettyString();String display=text.length()>131072?text.substring(0,131072)+"\n显示已截断；完整结果请查看会话文件或 CLI 输出。":text;Platform.runLater(()->{if(!closed)raw.setText(display);});});sections.getTabs().add(new Tab("原始证据",raw));
        Label explanation=new Label(result.has("comparability")?(result.path("comparability").path("eligible").asBoolean()?"满足声明与证据的比较门槛；仍不能证明优化收益。":"无法严格比较；下列原因需补齐后再试：\n"+comparisonReasons(result.path("comparability").path("reasons"))):"观测结果与假设供进一步核验；时间重叠不代表因果。");explanation.setWrapText(true);
        TextArea notes=new TextArea();notes.setEditable(false);notes.setWrapText(true);notes.setPrefRowCount(3);
        StringBuilder text=new StringBuilder();for(String key:List.of("missingEvidence","warnings","limitations","plan","nextMeasurements","coverage"))if(result.has(key))text.append(key).append("：").append(result.path(key).toPrettyString()).append('\n');notes.setText(text.toString());notes.setVisible(!text.isEmpty());notes.setManaged(!text.isEmpty());
        Button back=new Button("返回诊断证据");back.setId("diagnostics-result-back");back.setOnAction(event->{results.forEach(EvidenceTablePane::close);setCenter(evidenceView);});
        Button recapture=new Button("补齐实验条件并重新采集");recapture.setVisible(result.has("comparability"));recapture.setManaged(result.has("comparability"));recapture.setOnAction(e->onRecapture.run());
        Button export=new Button("导出结果…");export.setOnAction(e->{FileChooser chooser=new FileChooser();chooser.setTitle("导出诊断或比较结果");chooser.setInitialFileName("diagnostic-result.json");var file=chooser.showSaveDialog(getScene().getWindow());if(file!=null)task("导出诊断结果",()->{com.agentmonitor.core.util.AtomicFiles.write(file.toPath(),JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));return file.toPath();},value->status.setText("已导出："+value),true);});
        VBox content=new VBox(10,new javafx.scene.layout.FlowPane(8,6,back,recapture,export),explanation,notes,sections);VBox.setVgrow(sections,Priority.ALWAYS);setCenter(content);
        // A single owner closes all result-table workers when replacing this result or the workbench.
        resultClosers.forEach(EvidenceTablePane::close);resultClosers.clear();resultClosers.addAll(results);
        status.setText("诊断完成；摘要、数据表与原始证据均可查看。");
    }
    private final List<EvidenceTablePane> resultClosers=new ArrayList<>();
    private static String comparisonReasons(JsonNode reasons){List<String> lines=new ArrayList<>();reasons.forEach(reason->lines.add(UiText.comparisonReason(reason.asText())));return String.join("\n",lines);}
    private static String resultLabel(String key){return switch(key){case "findings"->"发现与假设";case "endpoints"->"接口统计";case "events"->"关联时间线";case "spans"->"跨服务调用";case "edges"->"调用关系";case "methods"->"方法差异";case "sql"->"SQL 差异";case "classes"->"对象差异";case "sources"->"来源会话";default->key;};}
    private void analyze(Work work){Path selected=directory();if(selected==null){status.setText("请先采集或加载会话");return;}task(()->JSON.valueToTree(work.run()),result->{if(Objects.equals(selected,directory()))showResult((JsonNode)result);});}
    public void showTimeline(String traceId){focusTimeline(JSON.createObjectNode().put("traceId",traceId));}
    private void focusTimeline(JsonNode row){
        Path selected=directory();if(selected==null)return;
        String trace=row==null?"":row.path("traceId").asText(row.path("parentTraceId").asText());
        if(trace.isBlank()){
            TextInputDialog dialog=new TextInputDialog();dialog.setTitle("关联时间线");dialog.setHeaderText("输入本会话中要关联的 Trace ID");var answer=dialog.showAndWait();if(answer.isEmpty()||answer.get().isBlank())return;trace=answer.get().trim();
        }
        String focusTrace=trace,thread=row==null?null:row.path("threadId").asText(null);
        analyze(()->{new EvidenceStore().index(selected);return new TimelineService().query(selected,null,null,focusTrace,thread,200);});
    }
    private void distributed(){
        TextInputDialog trace=new TextInputDialog();trace.setTitle("跨服务关联");trace.setHeaderText("输入 W3C Trace ID（32 位十六进制）");var id=trace.showAndWait();if(id.isEmpty())return;
        List<Path> sessions=new ArrayList<>();if(directory()!=null)sessions.add(directory());
        DirectoryChooser chooser=new DirectoryChooser();chooser.setTitle("选择关联服务的会话目录");
        while(sessions.size()<32){var selected=chooser.showDialog(getScene().getWindow());if(selected==null)break;sessions.add(selected.toPath());Alert more=new Alert(Alert.AlertType.CONFIRMATION,"继续添加其他服务会话？",ButtonType.YES,ButtonType.NO);if(more.showAndWait().orElse(ButtonType.NO)!=ButtonType.YES)break;}
        analyze(()->new DistributedTrace().correlate(sessions,id.get().trim()));
    }
    private void saveCommand(String title,String extension,String... base){FileChooser c=new FileChooser();c.setTitle(title);c.setInitialFileName("diagnostic-"+System.currentTimeMillis()+"."+extension);c.getExtensionFilters().add(new FileChooser.ExtensionFilter(extension,"*."+extension));var f=c.showSaveDialog(getScene().getWindow());if(f!=null){List<String> args=new ArrayList<>(List.of(base));args.addAll(List.of("--output",f.toString()));command(args.toArray(String[]::new));}}
    private void compare(){DirectoryChooser c=new DirectoryChooser();c.setTitle("选择优化前会话");var a=c.showDialog(getScene().getWindow());if(a==null)return;c.setTitle("选择优化后会话");var b=c.showDialog(getScene().getWindow());if(b!=null)command("compare","--before",a.toString(),"--after",b.toString());}
    private void compareHistograms(){FileChooser c=new FileChooser();c.setTitle("选择之前的对象直方图 JSON");var a=c.showOpenDialog(getScene().getWindow());if(a==null)return;c.setTitle("选择之后的对象直方图 JSON");var b=c.showOpenDialog(getScene().getWindow());if(b!=null)command("compare","--kind","histogram","--before",a.toString(),"--after",b.toString());}
    private void asyncProfile(String mode){FileChooser c=new FileChooser();c.setTitle("选择本机 async-profiler 的 asprof 可执行文件");var binary=c.showOpenDialog(getScene().getWindow());if(binary==null)return;saveCommand("保存 "+mode+" 火焰图","html","profile","async","--pid",pid,"--binary",binary.toString(),"--event",mode,"--duration","30s");}
    private void open(Path path){try{var result=SessionArtifactOpener.open(path);status.setText(result.message());}catch(Exception e){status.setText("无法打开："+e.getMessage());}}
    private static void add(MenuButton menu,String text,Runnable action){MenuItem item=new MenuItem(text);item.setOnAction(e->action.run());menu.getItems().add(item);}
}
