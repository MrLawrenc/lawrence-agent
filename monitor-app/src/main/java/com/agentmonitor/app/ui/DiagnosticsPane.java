package com.agentmonitor.app.ui;

import com.agentmonitor.core.diagnostics.DiagnosticCommands;
import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.service.MonitoringRuntime;
import com.agentmonitor.core.util.AtomicFiles;
import com.agentmonitor.app.util.SessionArtifactOpener;
import com.fasterxml.jackson.databind.*;
import javafx.animation.*;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.scene.Scene;
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
    private Path history;private final Label status=new Label("等待采集或选择历史会话");private final Label location=new Label();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"diagnostic-workbench");t.setDaemon(true);return t;});
    private final Button refresh=new Button("刷新证据");private final Timeline timer;
    private final Map<String,TableView<JsonNode>> tables=new HashMap<>();
    private final ComboBox<String> metric=new ComboBox<>();private final LineChart<Number,Number> chart;
    private boolean busy,reading,closed;
    private Path evidenceDirectory;
    private JsonNode lastMetricsRows;
    private int lastMetricIndex=-1;
    private final BorderPane evidenceView=new BorderPane();
    private final List<Control> operations=new ArrayList<>();
    public DiagnosticsPane(String pid,Supplier<MonitoringSession> session,Supplier<MonitoringRuntime> runtime){
        this.pid=pid;sessionSupplier=session;runtimeSupplier=runtime;getStyleClass().add("diagnostics-pane");setId("diagnostics-pane");
        refresh.setId("diagnostics-refresh");status.setId("diagnostics-status");
        Button historical=new Button("加载历史会话…"),current=new Button("返回本次会话"),compare=new Button("对比会话…"),open=new Button("打开会话目录");
        historical.setOnAction(e->{DirectoryChooser c=new DirectoryChooser();c.setTitle("选择完整会话目录");var f=c.showDialog(getScene().getWindow());if(f!=null){history=f.toPath();reload();}});
        current.setOnAction(e->{history=null;reload();});compare.setOnAction(e->compare());open.setOnAction(e->{Path p=directory();if(p!=null)open(p);});refresh.setOnAction(e->reload());
        MenuButton capture=new MenuButton("采集诊断");capture.setId("diagnostics-capture");
        add(capture,"连续线程快照（3 次）",()->command("diag","threads","--pid",pid,"--count","3"));
        add(capture,"刷新活动会话类来源",()->{Path dir=directory();if(dir!=null)command("diag","classes","--session",dir.toString(),"--refresh");});
        add(capture,"运行环境",()->command("diag","environment","--pid",pid));
        add(capture,"对象直方图…",()->saveCommand("对象直方图","json","diag","histogram","--pid",pid));
        add(capture,"堆转储（可能暂停目标）…",()->saveCommand("堆转储（可能暂停 JVM，占用较大磁盘空间）","hprof","diag","heap-dump","--pid",pid));
        add(capture,"录制 JFR（30 秒）…",()->{DirectoryChooser c=new DirectoryChooser();c.setTitle("选择 JFR 输出目录");var f=c.showDialog(getScene().getWindow());if(f!=null)command("profile","record","--pid",pid,"--duration","30s","--output",f.toPath().toString());});
        add(capture,"导入 JFR 分析…",()->{FileChooser f=new FileChooser();f.setTitle("选择 JFR 文件");f.getExtensionFilters().add(new FileChooser.ExtensionFilter("JFR","*.jfr"));var selected=f.showOpenDialog(getScene().getWindow());if(selected==null)return;DirectoryChooser d=new DirectoryChooser();d.setTitle("选择分析输出目录");var out=d.showDialog(getScene().getWindow());if(out!=null)command("profile","analyze","--recording",selected.toString(),"--output",out.toString());});
        Menu async=new Menu("async-profiler 火焰图…");for(String mode:List.of("cpu","wall","alloc","lock")){MenuItem item=new MenuItem(mode);item.setOnAction(e->asyncProfile(mode));async.getItems().add(item);}capture.getItems().add(async);
        add(capture,"打开本次火焰图",()->{Path dir=directory();if(dir!=null)open(dir.resolve("diagnostics/flamegraph.html"));});
        add(capture,"对比对象直方图…",()->compareHistograms());
        operations.addAll(List.of(refresh,capture,historical,current,compare,open));
        MenuButton sessions=new MenuButton("会话操作");
        for(Button source:List.of(historical,current,compare,open)){MenuItem item=new MenuItem(source.getText());item.disableProperty().bind(source.disabledProperty());item.setOnAction(e->source.fire());sessions.getItems().add(item);}
        for(Control control:List.of(refresh,capture,sessions))control.getStyleClass().add("diagnostics-action");
        HBox actions=WorkbenchPane.actions(refresh,capture,sessions);
        location.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS);location.setMaxWidth(Double.MAX_VALUE);
        status.setTextOverrun(OverrunStyle.ELLIPSIS);
        location.getStyleClass().add("hint-label");status.getStyleClass().add("diagnostics-status");
        HBox context=new HBox(16,location,status);context.setAlignment(javafx.geometry.Pos.CENTER_LEFT);HBox.setHgrow(location,Priority.ALWAYS);
        VBox header=new VBox(10,actions,context);header.setPadding(new Insets(0,0,14,0));
        TabPane tabs=new TabPane();tabs.setId("diagnostics-tabs");tabs.getStyleClass().add("diagnostics-tabs");tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        NumberAxis x=new NumberAxis(),y=new NumberAxis();x.setLabel("会话相对时间（秒）");y.setLabel("百分比");chart=new LineChart<>(x,y);chart.setAnimated(false);chart.setCreateSymbols(false);chart.setLegendVisible(false);chart.setMinHeight(220);
        metric.getItems().addAll("进程 CPU (%)","堆使用率 (%)","平台线程数");metric.getSelectionModel().selectFirst();metric.setOnAction(e->updateChart(lastMetricsRows==null?JSON.createArrayNode():lastMetricsRows));metric.setAccessibleText("时间线指标");metric.getStyleClass().add("diagnostics-metric");
        Label caveat=new Label("采样与慢调用使用时间戳关联。GC 重叠只是线索；CPU 采样和方法耗时有不同含义。JFR 默认需在 YAML 开启 diagnostics.jfr。");caveat.setWrapText(true);
        VBox timeline=new VBox(12,metric,chart,caveat);timeline.setPadding(new Insets(16));VBox.setVgrow(chart,Priority.ALWAYS);tabs.getTabs().add(new Tab("指标时间线",timeline));
        tableTab(tabs,"live","未完成调用",new String[]{"方法","已运行 ms","线程","状态"},new String[]{"method","elapsedMillis","threadName","state"});
        tableTab(tabs,"threads","线程与锁",new String[]{"线程","状态","锁持有者","锁"},new String[]{"name","state","lockOwnerName","lock"});
        tableTab(tabs,"sql","SQL / 连接等待",new String[]{"SQL 指纹 / 连接池","观测次数","累计 ns","最大 ns"},new String[]{"key","observedCalls","inclusiveNanos","maxNanos"});
        tableTab(tabs,"exceptions","异常",new String[]{"异常类型与栈","观测数（详情注明口径）","累计 ns"},new String[]{"key","observedErrors","inclusiveNanos"});
        tableTab(tabs,"async","异步任务",new String[]{"来源 Trace","任务 Trace","排队 ns","执行 ns","线程"},new String[]{"parentTraceId","traceId","queueNanos","executionNanos","thread"});
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
    @Override public void close(){closed=true;timer.stop();worker.shutdown();}
    public void refreshEvidence(){reload();}
    private void tableTab(TabPane tabs,String key,String label,String[] names,String[] fields){
        TableView<JsonNode> table=new TableView<>();Label empty=new Label("暂无"+label+"证据\n开始采集，或从“会话操作”加载历史会话。");empty.setWrapText(true);empty.getStyleClass().add("empty-state-desc");table.setPlaceholder(empty);table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);table.setAccessibleText(label);
        for(int i=0;i<fields.length;i++){String field=fields[i];TableColumn<JsonNode,String> col=new TableColumn<>(names[i]);col.setCellValueFactory(c->new ReadOnlyStringWrapper(c.getValue().path(field).asText("")));col.setMinWidth(70);table.getColumns().add(col);}
        TextField search=new TextField();search.setPromptText("筛选"+label);search.setAccessibleText("筛选"+label);
        TextArea details=new TextArea();details.setEditable(false);details.setWrapText(false);details.setPromptText("选择一行查看完整证据，包括堆栈、锁关系和 Trace ID。");details.setAccessibleText(label+"详情");
        table.getProperties().put("details",details);
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{if(!Boolean.TRUE.equals(table.getProperties().get("refreshing")))details.setText(b==null?"":b.toPrettyString());});
        search.textProperty().addListener((o,a,b)->{table.getProperties().put("page",0);filter(table,b);});table.getProperties().put("search",search);
        Label count=new Label("暂无证据");table.getProperties().put("count",count);Button previous=new Button("上一页"),next=new Button("下一页");previous.setDisable(true);next.setDisable(true);
        table.getProperties().put("previous",previous);table.getProperties().put("next",next);
        previous.setOnAction(e->{table.getProperties().put("page",Math.max(0,(int)table.getProperties().getOrDefault("page",0)-1));filter(table,search.getText());});
        next.setOnAction(e->{table.getProperties().put("page",(int)table.getProperties().getOrDefault("page",0)+1);filter(table,search.getText());});
        HBox pages=new HBox(10,count,previous,next);pages.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        SplitPane split=new SplitPane(table,details);split.setOrientation(javafx.geometry.Orientation.VERTICAL);split.setDividerPositions(0.65);
        VBox content=new VBox(10,new SearchBox(search,label,true),split,pages);content.setPadding(new Insets(12));VBox.setVgrow(split,Priority.ALWAYS);tabs.getTabs().add(new Tab(label,content));tables.put(key,table);
    }
    @SuppressWarnings("unchecked") private void filter(TableView<JsonNode> table,String query){
        List<JsonNode> source=(List<JsonNode>)table.getProperties().getOrDefault("source",List.of());
        String selected=identity(table.getSelectionModel().getSelectedItem());
        String q=query.toLowerCase(Locale.ROOT);List<JsonNode> matched=source.stream().filter(n->n.toString().toLowerCase(Locale.ROOT).contains(q)).toList();
        int pages=Math.max(1,(matched.size()+499)/500),page=Math.min(pages-1,(int)table.getProperties().getOrDefault("page",0));table.getProperties().put("page",page);
        table.getProperties().put("refreshing",true);
        table.getItems().setAll(matched.subList(Math.min(page*500,matched.size()),Math.min((page+1)*500,matched.size())));
        for(JsonNode row:table.getItems())if(!selected.isEmpty()&&selected.equals(identity(row))){table.getSelectionModel().select(row);break;}
        table.getProperties().put("refreshing",false);
        TextArea details=(TextArea)table.getProperties().get("details");JsonNode row=table.getSelectionModel().getSelectedItem();
        String text=row==null?"":row.toPrettyString();if(!text.equals(details.getText())){double top=details.getScrollTop(),left=details.getScrollLeft();int caret=details.getCaretPosition();details.setText(text);details.positionCaret(Math.min(caret,text.length()));details.setScrollTop(top);details.setScrollLeft(left);}
        ((Label)table.getProperties().get("count")).setText("匹配 "+matched.size()+" / 全部 "+source.size()+" · 第 "+(page+1)+" / "+pages+" 页（搜索覆盖全部证据）");
        ((Button)table.getProperties().get("previous")).setDisable(page==0);((Button)table.getProperties().get("next")).setDisable(page>=pages-1);
    }
    private static String identity(JsonNode row){
        if(row==null)return "";for(String key:List.of("threadId","traceId","key","poolLabel","name"))if(row.has(key))return key+":"+row.path(key).asText();
        if(row.has("className"))return row.path("className").asText()+"@"+row.path("loader").asText();return row.toString();
    }
    private void fill(String key,JsonNode rows){List<JsonNode> list=new ArrayList<>();if(rows.isArray())rows.forEach(list::add);TableView<JsonNode> table=tables.get(key);if(list.equals(table.getProperties().get("source")))return;table.getProperties().put("source",list);filter(table,((TextField)table.getProperties().get("search")).getText());}
    private Path directory(){MonitoringSession s=sessionSupplier.get();return history!=null?history:s==null?null:s.sessionDirectory();}
    private void reload(){reload(true);}
    private void reload(boolean manual){if(busy||reading||closed)return;Path dir=directory();
        if(!Objects.equals(dir,evidenceDirectory)){clearEvidence();evidenceDirectory=dir;setCenter(evidenceView);}
        String locationText=dir==null?"尚无会话 · 开始监控或加载历史会话":"会话 · "+dir.getFileName();
        if(!locationText.equals(location.getText())){location.setText(locationText);location.setTooltip(dir==null?null:new Tooltip(dir.toAbsolutePath().toString()));}
        if(dir==null){clearEvidence();status.setText("等待采集或选择历史会话");return;}
        if(manual)status.setText("正在读取会话证据…");
        task(()->load(dir),value->{
            if(!Objects.equals(dir,directory())){clearEvidence();Platform.runLater(this::reload);return;}
            JsonNode root=JSON.valueToTree(value);fill("live",root.path("live").path("roots"));fill("threads",root.path("threads").path("threads"));fill("classes",root.path("classes").path("classes"));
            JsonNode aggregate=root.path("report").path("diagnosticAggregates");var sql=JSON.createArrayNode();aggregate.path("sql").forEach(sql::add);aggregate.path("connectionAcquisition").forEach(sql::add);for(JsonNode p:aggregate.path("repeatedQueries")){var row=(com.fasterxml.jackson.databind.node.ObjectNode)p.deepCopy();row.put("key","重复查询候选 · "+p.path("sqlFingerprint").asText());row.set("observedCalls",p.path("observedExecutions"));sql.add(row);}fill("sql",sql);
            var exceptions=JSON.createArrayNode();aggregate.path("exceptions").forEach(exceptions::add);root.path("jfr").path("exceptionThrows").fields().forEachRemaining(e->{var row=exceptions.addObject();row.put("key","JFR throw · "+e.getKey());row.set("observedErrors",e.getValue());row.put("countKind","JFR throw events, distinct from propagated error spans");});fill("exceptions",exceptions);fill("async",aggregate.path("asyncLinks"));
            var pools=JSON.createArrayNode();for(JsonNode p:root.path("live").path("threadPools")){var row=p.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)row).put("poolLabel",p.path("class").asText()+"@"+p.path("identity").asText()).put("activeLabel",p.path("active").asText()).put("queuedLabel",p.path("queued").asText()).put("sizeLabel",p.path("size").asText());pools.add(row);}
            for(JsonNode p:root.path("latest").path("pools").path("registeredPools")){var row=p.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)row).put("poolLabel",p.path("objectName").asText()).put("activeLabel",p.path("ActiveConnections").asText(p.path("ActiveCount").asText())).put("queuedLabel",p.path("ThreadsAwaitingConnection").asText(p.path("QueueSize").asText())).put("sizeLabel",p.path("TotalConnections").asText(p.path("PoolSize").asText()));pools.add(row);}fill("pools",pools);
            var memory=JSON.createArrayNode();var trend=memory.addObject();trend.put("name","堆使用趋势（不等于泄漏）");trend.put("value",root.path("manifest").path("heapTrend").toString());for(JsonNode event:root.path("jfr").path("gcEvents")){var row=memory.addObject();row.put("name",event.path("event").asText());row.put("time",event.path("startedAtEpochMillis").asText()+" "+event.path("when").asText());row.put("value",event.has("heapUsedBytes")?event.path("heapUsedBytes").asText()+" bytes":event.path("durationNanos").asText()+" ns");row.set("evidence",event);}fill("memory",memory);
            var artifacts=JSON.createArrayNode();for(String k:List.of("manifest","environment","jfr","trigger","report")){var r=artifacts.addObject();r.put("name",k);r.put("value",root.path(k).toPrettyString());}fill("artifacts",artifacts);
            updateChart(root.path("metrics"));JsonNode m=root.path("manifest");status.setText("诊断状态："+m.path("status").asText("尚未生成")+" · 样本 "+m.path("samples").asInt()+" · 错误 "+m.path("errors").size()+" · 报告 "+root.path("report").path("status").asText("停止后生成"));},false);
    }
    private void clearEvidence(){
        for(TableView<JsonNode> table:tables.values()){table.getProperties().put("source",List.of());filter(table,((TextField)table.getProperties().get("search")).getText());}
        chart.getData().clear();
        lastMetricsRows=null;lastMetricIndex=-1;
    }
    private static Object load(Path dir)throws Exception{
        Map<String,Object> result=new LinkedHashMap<>();for(var e:Map.of("live","agent-logs/live-invocations.json","threads","diagnostics/threads-latest.json","classes","agent-logs/class-origins.json","latest","diagnostics/latest.json","manifest","diagnostics/manifest.json","environment","diagnostics/environment.json","report","reports/performance-report.json","jfr","diagnostics/jfr-analysis.json","trigger","diagnostics/trigger.json").entrySet()){
            Path file=dir.resolve(e.getValue());result.put(e.getKey(),Files.isRegularFile(file)&&Files.size(file)<64L*1024*1024?JSON.readTree(file.toFile()):JSON.createObjectNode());}
        ArrayDeque<JsonNode> metrics=new ArrayDeque<>();Path file=dir.resolve("diagnostics/metrics.jsonl");if(Files.isRegularFile(file))try(var reader=Files.newBufferedReader(file)){for(String line;(line=reader.readLine())!=null;){if(line.length()>4*1024*1024)continue;metrics.add(JSON.readTree(line));if(metrics.size()>240)metrics.removeFirst();}}
        result.put("metrics",metrics);return result;
    }
    private void updateChart(JsonNode rows){int selected=metric.getSelectionModel().getSelectedIndex();if(selected==lastMetricIndex&&rows.equals(lastMetricsRows))return;lastMetricsRows=rows;lastMetricIndex=selected;XYChart.Series<Number,Number> series=new XYChart.Series<>();long start=rows.isEmpty()?0:rows.get(0).path("timestampMillis").asLong();((NumberAxis)chart.getYAxis()).setLabel(selected==2?"线程数":"百分比");for(JsonNode row:rows){double value=selected==0?row.path("ProcessCpuLoad").asDouble(-1)*100:selected==1?row.path("heap").path("used").asDouble()/Math.max(1,row.path("heap").path(row.path("heap").path("max").asDouble()>0?"max":"committed").asDouble())*100:row.path("threadCount").asDouble();if(value>=0)series.getData().add(new XYChart.Data<>((row.path("timestampMillis").asLong()-start)/1000.0,value));}chart.getData().setAll(series);}
    private interface Work{Object run()throws Exception;}
    private void task(Work work,java.util.function.Consumer<Object> done){task(work,done,true);}
    private void task(Work work,java.util.function.Consumer<Object> done,boolean blockControls){
        if(busy||(reading&&!blockControls)){if(blockControls)status.setText("已有诊断操作正在执行，请等待完成后再试。");return;}
        if(blockControls){busy=true;operations.forEach(c->c.setDisable(true));}else reading=true;
        worker.execute(()->{
            try{Object value=work.run();Platform.runLater(()->{if(!closed&&(blockControls||!busy))done.accept(value);});}
            catch(Exception e){Platform.runLater(()->{if(!closed&&(blockControls||!busy))status.setText("操作失败："+Objects.toString(e.getMessage(),e.getClass().getSimpleName())+"。请检查目标进程、权限或所选文件后重试。");});}
            finally{Platform.runLater(()->{if(blockControls){busy=false;operations.forEach(c->c.setDisable(false));}else reading=false;});}
        });
    }
    private void command(String... args){if(busy){status.setText("已有诊断操作正在执行，请等待完成后再试。");return;}status.setText("正在执行诊断，结果将在完成后显示…");task(()->DiagnosticCommands.execute(args),result->showResult(JSON.valueToTree(result)));}
    void showResult(JsonNode result){
        TextArea text=new TextArea(result.toPrettyString());text.setEditable(false);text.setAccessibleText("诊断结果 JSON");
        Button back=new Button("返回诊断证据");back.setId("diagnostics-result-back");back.setOnAction(event->setCenter(evidenceView));
        VBox content=new VBox(12,back,text);VBox.setVgrow(text,Priority.ALWAYS);setCenter(content);
        status.setText("诊断完成；可在下方查看完整结果。");
    }
    private void saveCommand(String title,String extension,String... base){FileChooser c=new FileChooser();c.setTitle(title);c.setInitialFileName("diagnostic-"+System.currentTimeMillis()+"."+extension);c.getExtensionFilters().add(new FileChooser.ExtensionFilter(extension,"*."+extension));var f=c.showSaveDialog(getScene().getWindow());if(f!=null){List<String> args=new ArrayList<>(List.of(base));args.addAll(List.of("--output",f.toString()));command(args.toArray(String[]::new));}}
    private void compare(){DirectoryChooser c=new DirectoryChooser();c.setTitle("选择优化前会话");var a=c.showDialog(getScene().getWindow());if(a==null)return;c.setTitle("选择优化后会话");var b=c.showDialog(getScene().getWindow());if(b!=null)command("compare","--before",a.toString(),"--after",b.toString());}
    private void compareHistograms(){FileChooser c=new FileChooser();c.setTitle("选择之前的对象直方图 JSON");var a=c.showOpenDialog(getScene().getWindow());if(a==null)return;c.setTitle("选择之后的对象直方图 JSON");var b=c.showOpenDialog(getScene().getWindow());if(b!=null)command("compare","--kind","histogram","--before",a.toString(),"--after",b.toString());}
    private void asyncProfile(String mode){FileChooser c=new FileChooser();c.setTitle("选择本机 async-profiler 的 asprof 可执行文件");var binary=c.showOpenDialog(getScene().getWindow());if(binary==null)return;saveCommand("保存 "+mode+" 火焰图","html","profile","async","--pid",pid,"--binary",binary.toString(),"--event",mode,"--duration","30s");}
    private void open(Path path){try{var result=SessionArtifactOpener.open(path);status.setText(result.message());}catch(Exception e){status.setText("无法打开："+e.getMessage());}}
    private static void add(MenuButton menu,String text,Runnable action){MenuItem item=new MenuItem(text);item.setOnAction(e->action.run());menu.getItems().add(item);}
}
