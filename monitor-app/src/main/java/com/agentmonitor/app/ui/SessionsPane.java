package com.agentmonitor.app.ui;

import com.agentmonitor.core.catalog.SessionCatalog;
import com.agentmonitor.core.evidence.SessionEvidenceReader;
import com.agentmonitor.app.util.SessionArtifactOpener;
import com.fasterxml.jackson.databind.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Read-only session browser and report overview. Capture/quality judgments remain in core. */
public final class SessionsPane extends BorderPane implements AutoCloseable {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"session-browser");t.setDaemon(true);return t;});
    private final EvidenceTablePane catalog=new EvidenceTablePane("最近会话",new String[]{"开始时间","会话","状态","Trace 完整","可分析"},new String[]{"startedAt","sessionId","status","captureComplete","analysisReady"});
    private final Label status=new Label("选择会话根目录以浏览最近会话"),quality=new Label("采集完成后，在这里查看报告。"),restore=new Label();
    private final TabPane report=new TabPane();
    private final List<EvidenceTablePane> reportTables=new ArrayList<>();
    private final Consumer<Path> select;
    private Path root,viewed,rollingMaster;
    private long generation;
    private boolean closed;
    private EvidenceTablePane rollingTable;
    private final javafx.animation.Timeline rollingRefresh=new javafx.animation.Timeline();
    public SessionsPane(Consumer<Path> select,Runnable diagnostics,Runnable calls) {
        this.select=select;catalog.setShowDetails(false);setId("sessions-pane");
        Button browse=new Button("选择会话根目录…"),open=new Button("打开历史会话…"),refresh=new Button("刷新列表"),load=new Button("查看选中会话"),current=new Button("返回本次会话");
        browse.setOnAction(e->{DirectoryChooser chooser=new DirectoryChooser();var dir=chooser.showDialog(getScene().getWindow());if(dir!=null)setCatalogRoot(dir.toPath());});
        open.setOnAction(e->{DirectoryChooser chooser=new DirectoryChooser();chooser.setTitle("选择完整会话目录");var dir=chooser.showDialog(getScene().getWindow());if(dir!=null)select.accept(dir.toPath());});
        refresh.setOnAction(e->{refreshCatalog();showSession(viewed,restore.getText());});load.setOnAction(e->{var row=catalog.table().getSelectionModel().getSelectedItem();if(row!=null)select.accept(Path.of(row.path("sessionDirectory").asText()));});current.setOnAction(e->select.accept(null));
        load.disableProperty().bind(catalog.table().getSelectionModel().selectedItemProperty().isNull());
        status.setWrapText(true);quality.setWrapText(true);restore.setWrapText(true);quality.setMinHeight(Region.USE_PREF_SIZE);restore.setMinHeight(Region.USE_PREF_SIZE);
        VBox header=new VBox(8,new javafx.scene.layout.FlowPane(8,6,browse,open,refresh,load,current),status);header.setPadding(new javafx.geometry.Insets(0,0,10,0));setTop(header);
        Button windows=new Button("返回滚动主会话");windows.setOnAction(e->{if(rollingMaster!=null)select.accept(rollingMaster);});
        Button directory=new Button("打开会话目录"),raw=new Button("打开原始报告"),prompt=new Button("复制 AI 分析提示词"),diag=new Button("诊断与证据"),traces=new Button("查看调用链");
        directory.setOnAction(e->open(viewed));raw.setOnAction(e->{if(viewed!=null)open(viewed.resolve("reports/performance-report.json"));});
        prompt.setOnAction(e->{if(viewed==null)return;Path file=viewed.resolve("ai-analysis-prompt.txt");worker.execute(()->{try{if(Files.size(file)>1024*1024)throw new java.io.IOException("提示词文件过大");String text=Files.readString(file);Platform.runLater(()->{if(!closed){var value=new javafx.scene.input.ClipboardContent();value.putString(text);status.setText(javafx.scene.input.Clipboard.getSystemClipboard().setContent(value)?"AI 分析提示词已复制":"剪贴板写入失败");}});}catch(Exception error){feedback(error);}});});
        diag.setOnAction(e->diagnostics.run());traces.setOnAction(e->calls.run());
        report.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        VBox summary=new VBox(10,quality,restore,new javafx.scene.layout.FlowPane(8,6,directory,raw,prompt,diag,traces,windows),report);VBox.setVgrow(report,Priority.ALWAYS);
        rollingRefresh.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(5),e->{Path selected=viewed;if(closed||getScene()==null||selected==null||!Files.isRegularFile(selected.resolve("rolling-capture.json")))return;if(rollingTable==null){showSession(selected,restore.getText());return;}long version=generation;worker.execute(()->{try{var snapshot=JSON.valueToTree(new com.agentmonitor.core.catalog.RollingSessionCatalog().list(selected));javafx.application.Platform.runLater(()->{if(!closed&&version==generation&&rollingTable!=null)rollingTable.setRows(snapshot.path("windows"));});}catch(Exception error){feedback(error);}});}));rollingRefresh.setCycleCount(javafx.animation.Timeline.INDEFINITE);rollingRefresh.play();
        SplitPane split=new SplitPane(catalog,summary);split.setOrientation(javafx.geometry.Orientation.VERTICAL);split.setDividerPositions(.32);setCenter(split);
    }
    public void setCatalogRoot(Path path){root=path;refreshCatalog();}
    public void refreshCatalog(){if(root==null||closed)return;Path selected=root;worker.execute(()->{try{JsonNode data=JSON.valueToTree(new SessionCatalog().list(selected,200));Platform.runLater(()->{if(!closed&&Objects.equals(selected,root)){catalog.setRows(data.path("sessions"));status.setText("会话根目录："+selected+" · 匹配 "+data.path("matchedSessions").asInt()+" · 扫描截断 "+UiText.status(Boolean.toString(data.path("scanTruncated").asBoolean()))+(data.path("warnings").isEmpty()?"":" · "+data.path("warnings")));}});}catch(Exception error){feedback(error);}});}
    public void showSession(Path path,String restoration){
        viewed=path;if(path!=null&&Files.isRegularFile(path.resolve("rolling-capture.json")))rollingMaster=path;
        if(path!=null&&Files.isRegularFile(path.resolve("window.json"))){Path parent=path.getParent();if(parent!=null&&parent.getParent()!=null&&parent.getParent().getFileName().toString().equals("windows"))rollingMaster=parent.getParent().getParent();}
        restore.setText(restoration);long version=++generation;
        rollingTable=null;reportTables.forEach(EvidenceTablePane::close);reportTables.clear();report.getTabs().clear();
        if(path==null){quality.setText("尚无会话。开始采集，或从最近会话选择历史证据。");report.getTabs().clear();reportTables.forEach(EvidenceTablePane::close);reportTables.clear();return;}
        quality.setText("正在读取报告…");worker.execute(()->{try{JsonNode data=JSON.valueToTree(new SessionEvidenceReader().read(path));JsonNode rolling=JSON.valueToTree(new com.agentmonitor.core.catalog.RollingSessionCatalog().list(path));Platform.runLater(()->{if(closed||version!=generation)return;
            JsonNode result=data.path("report");quality.setText("会话："+path.getFileName()+"\nTrace 完整："+UiText.status(result.path("captureComplete").asText("未知"))+" · 可分析："+UiText.status(result.path("analysisReady").asText("未知"))+" · 诊断质量："+UiText.status(data.path("manifest").path("status").asText("未采集"))+"\nSpan 接收："+result.path("captureQuality").path("acceptedSpans").asText("未知")+" · 丢弃："+result.path("captureQuality").path("discardedSpans").asText("未知")+" · 未收到："+result.path("captureQuality").path("unreceivedSpans").asText("未知"));
            reportTables.forEach(EvidenceTablePane::close);reportTables.clear();report.getTabs().clear();
            add("热点方法",result.path("hotspots"),new String[]{"方法","观测次数","自身耗时","累计耗时","最大耗时"},new String[]{"method","observedCalls","selfNanos","inclusiveNanos","maxNanos"});
            add("慢调用",result.path("slowTraces"),new String[]{"Trace","根方法","耗时","异常"},new String[]{"traceId","rootMethod","durationNanos","error"});
            add("SQL",result.path("diagnosticAggregates").path("sql"),new String[]{"SQL 指纹","观测次数","累计耗时","最大耗时"},new String[]{"key","observedCalls","inclusiveNanos","maxNanos"});
            add("错误",result.path("diagnosticAggregates").path("exceptions"),new String[]{"异常","观测错误","累计耗时"},new String[]{"key","observedErrors","inclusiveNanos"});
            if(rolling.path("rolling").asBoolean()){
                EvidenceTablePane table=new EvidenceTablePane("滚动窗口",new String[]{"序号","开始时间","结束时间","根调用","磁盘","JFR 检查点","状态"},new String[]{"sequence","startedAtMillis","endedAtMillis","roots","diskBytes","checkpoint","status"});table.setShowDetails(false);table.setRows(rolling.path("windows"));rollingTable=table;reportTables.add(table);
                Button loadWindow=new Button("查看选中窗口");loadWindow.disableProperty().bind(table.table().getSelectionModel().selectedItemProperty().isNull());loadWindow.setOnAction(e->{var row=table.table().getSelectionModel().getSelectedItem();if(row!=null&&row.hasNonNull("sessionDirectory"))select.accept(Path.of(row.path("sessionDirectory").asText()));});
                Label bounds=new Label("主会话："+UiText.status(rolling.path("status").asText())+" · 磁盘预算 "+rolling.path("maxBytes").asLong()/1048576+" MiB · "+rolling.path("boundary").asText());bounds.setWrapText(true);
                VBox content=new VBox(8,bounds,loadWindow,table);VBox.setVgrow(table,Priority.ALWAYS);report.getTabs().add(new Tab("滚动窗口",content));
            }
            TextArea warnings=new TextArea(data.path("warnings").toPrettyString());warnings.setEditable(false);report.getTabs().add(new Tab("读取状态",warnings));
        });}catch(Exception error){feedback(error);}});
    }
    private void add(String name,JsonNode rows,String[] titles,String[] fields){EvidenceTablePane table=new EvidenceTablePane(name,titles,fields);table.setShowDetails(false);table.setRows(rows);reportTables.add(table);report.getTabs().add(new Tab(name,table));}
    private void open(Path path){if(path!=null)status.setText(SessionArtifactOpener.open(path).message());}
    private void feedback(Exception error){Platform.runLater(()->{if(!closed)status.setText("读取失败："+error.getMessage()+"。请检查会话目录后刷新。");});}
    @Override public void close(){closed=true;generation++;rollingRefresh.stop();catalog.close();reportTables.forEach(EvidenceTablePane::close);worker.shutdownNow();}
}
