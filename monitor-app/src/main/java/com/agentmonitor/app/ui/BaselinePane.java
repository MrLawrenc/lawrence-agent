package com.agentmonitor.app.ui;

import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.core.catalog.SessionCatalog;
import com.agentmonitor.core.diagnostics.SessionComparison;
import com.agentmonitor.core.evidence.SessionEvidenceReader;
import com.fasterxml.jackson.databind.*;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Desktop experiment bindings; completeness and comparability remain in core. */
public final class BaselinePane extends ScrollPane implements AutoCloseable {
    private record BoundSession(Path directory,String sessionId,String build,List<String> reasons,JsonNode declaration) { }
    private final List<TextField> fields=new ArrayList<>();
    private final Label status=new Label(),beforeStatus=new Label("基线：尚未绑定"),afterStatus=new Label("变更后：尚未绑定");
    private final Button compare=new Button("严格比较已绑定会话"),baseline=new Button("开始基线采集"),after=new Button("开始变更后采集");
    private final Supplier<MonitoringConfig> config;
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"baseline-guide");t.setDaemon(true);return t;});
    private final Map<String,Long> generations=new HashMap<>();
    private final List<Control> bindingControls=new ArrayList<>();
    private final Set<String> loading=new HashSet<>();
    private final BooleanSupplier capture;
    private BoundSession beforeSession,afterSession;
    private String pendingRole;
    private Path runningSession;
    private boolean closed;

    public BaselinePane(Supplier<MonitoringConfig> config,Consumer<MonitoringConfig.Experiment> save,Runnable capture,Runnable compare){
        this(config,save,()->{capture.run();return true;},(a,b)->compare.run(),ignored->{});
    }
    public BaselinePane(Supplier<MonitoringConfig> config,Consumer<MonitoringConfig.Experiment> save,BooleanSupplier capture,BiConsumer<Path,Path> comparison,Consumer<Path> view){
        this.config=config;this.capture=capture;setId("baseline-pane");setFitToWidth(true);
        Label intro=new Label("先固定业务负载、数据集和预热，再分别采集基线与变更后版本。会话绑定保存在个人设置中；严格比较复用原始质量证据，仍需验证实际流量与业务正确性。");intro.setWrapText(true);
        VBox body=new VBox(12,new Label("实验条件"),intro);body.setPadding(new javafx.geometry.Insets(12));
        String[] labels={"业务场景 / workloadId","数据集版本 / datasetId","并发、速率与时长 / loadProfile","预热方式 / warmupId","代码或构建版本 / buildId"};
        var experiment=config.get().experiment();String[] values={experiment.workloadId(),experiment.datasetId(),experiment.loadProfile(),experiment.warmupId(),experiment.buildId()};
        for(int i=0;i<labels.length;i++){TextField field=new TextField(values[i]);field.setAccessibleText(labels[i]);field.setPromptText(labels[i]);field.setMaxWidth(650);fields.add(field);body.getChildren().addAll(new Label(labels[i]),field);}
        Button apply=new Button("保存到下次采集"),load=new Button("载入已有基线声明…");
        apply.setOnAction(e->{if(fields.stream().anyMatch(f->f.getText().isBlank())){status.setText("请填写全部五项实验条件；构建版本在变更后采集时更新。");return;}save.accept(declaration());status.setText("实验声明已保存。保持负载、数据、预热和采集配置一致；变更后更新构建版本。");});
        baseline.setId("experiment-start-before");after.setId("experiment-start-after");compare.setId("experiment-compare");status.setId("experiment-status");
        baseline.setOnAction(e->start("before"));after.setOnAction(e->start("after"));
        compare.setOnAction(e->{if(beforeSession!=null&&afterSession!=null)comparison.accept(beforeSession.directory(),afterSession.directory());});
        load.setOnAction(e->choose("before",true));bindingControls.add(load);
        status.setWrapText(true);body.getChildren().addAll(new FlowPane(8,6,apply,load,baseline,after,compare),status);
        for(String role:List.of("before","after")){
            Label label=role.equals("before")?beforeStatus:afterStatus;label.setWrapText(true);label.setId("experiment-"+role+"-status");
            Button bind=new Button(role.equals("before")?"绑定已有基线…":"绑定变更后会话…"),open=new Button("查看会话"),clear=new Button("解除绑定");
            bind.setOnAction(e->choose(role,false));open.setOnAction(e->{BoundSession bound=role.equals("before")?beforeSession:afterSession;if(bound!=null)view.accept(bound.directory());});
            clear.setOnAction(e->{generations.merge(role,1L,Long::sum);loading.remove(role);if(role.equals("before"))beforeSession=null;else afterSession=null;DesktopPreferences.get().put("experiment."+role,"");label.setText(role.equals("before")?"基线：尚未绑定":"变更后：尚未绑定");updateActions();});
            bindingControls.addAll(List.of(bind,open,clear));body.getChildren().addAll(label,new FlowPane(8,6,bind,open,clear));
        }
        setContent(body);updateActions();
        for(String role:List.of("before","after")){String saved=DesktopPreferences.get().value("experiment."+role,"");if(!saved.isBlank())try{bindSession(role,Path.of(saved),false);}catch(RuntimeException invalid){status.setText("已保存的会话路径无效，请重新绑定。");}}
    }
    private void choose(String role,boolean loadDeclaration){DirectoryChooser chooser=new DirectoryChooser();chooser.setTitle(role.equals("before")?"选择基线会话":"选择变更后会话");var selected=chooser.showDialog(getScene().getWindow());if(selected!=null)bindSession(role,selected.toPath(),loadDeclaration);}
    void bindSession(String role,Path directory,boolean loadDeclaration){
        if(!List.of("before","after").contains(role))throw new IllegalArgumentException("Unknown experiment role");
        long generation=generations.merge(role,1L,Long::sum);Label label=role.equals("before")?beforeStatus:afterStatus;label.setText("正在核验会话："+directory);loading.add(role);DesktopPreferences.get().put("experiment."+role,"");if(role.equals("before"))beforeSession=null;else afterSession=null;updateActions();
        worker.execute(()->{try{
            Path real=directory.toRealPath();new SessionCatalog().show(real);
            JsonNode root=new ObjectMapper().valueToTree(new SessionEvidenceReader().read(real));JsonNode report=root.path("report");
            if(!root.path("identity").path("sessionId").asText().equals(report.path("sessionId").asText()))throw new IllegalArgumentException("会话报告缺失或身份不符，请核对会话目录");
            var reasons=new SessionComparison().eligibility(real);var declaration=report.path("monitoringConfig").path("experiment");
            BoundSession bound=new BoundSession(real,report.path("sessionId").asText(),declaration.path("buildId").asText("未声明"),reasons,declaration);
            Platform.runLater(()->{if(closed||!Objects.equals(generations.get(role),generation))return;
                loading.remove(role);if(role.equals("before"))beforeSession=bound;else afterSession=bound;
                DesktopPreferences.get().put("experiment."+role,real.toString());
                String problems=String.join("\n",reasons.stream().map(UiText::comparisonReason).toList());
                label.setText((role.equals("before")?"基线":"变更后")+" · "+bound.sessionId()+" · 构建 "+bound.build()+"\n"+real+"\n"+(reasons.isEmpty()?"单会话质量条件满足；两次采集仍需严格比较":problems));
                if(loadDeclaration){String[] keys={"workloadId","datasetId","loadProfile","warmupId","buildId"};for(int i=0;i<keys.length;i++)fields.get(i).setText(declaration.path(keys[i]).asText());status.setText("已载入并绑定基线；请核对采集配置，变更后更新 buildId。");}
                updateActions();
            });
        }catch(Exception error){Platform.runLater(()->{if(!closed&&Objects.equals(generations.get(role),generation)){loading.remove(role);if(role.equals("before"))beforeSession=null;else afterSession=null;label.setText("绑定失败："+error.getMessage()+"；请选择完整且已停止的会话目录。");updateActions();}});}});
    }
    private void start(String role){
        if(pendingRole!=null)return;
        if(fields.stream().anyMatch(f->f.getText().isBlank())||!declaration().equals(config.get().experiment())){status.setText("请先填写并保存当前实验声明，再开始采集。");return;}
        if(role.equals("after")){
            if(beforeSession==null||!beforeSession.reasons().isEmpty()){status.setText("请先绑定质量条件满足的基线会话。");return;}
            String[] keys={"workloadId","datasetId","loadProfile","warmupId"};for(int i=0;i<keys.length;i++)if(!fields.get(i).getText().equals(beforeSession.declaration().path(keys[i]).asText())){status.setText("变更后实验条件与基线不同："+keys[i]+"。请载入基线声明并核对负载。");return;}
            if(fields.get(4).getText().equals(beforeSession.build())){status.setText("请先更新并保存变更后的 buildId，区分两次代码版本。");return;}
        }
        pendingRole=role;updateActions();
        if(!capture.getAsBoolean()){BoundSession previous=role.equals("before")?beforeSession:afterSession;DesktopPreferences.get().put("experiment."+role,previous==null?"":previous.directory().toString());pendingRole=null;runningSession=null;status.setText("尚未开始采集，请检查采集范围与当前状态后重试。");updateActions();}
        else status.setText((role.equals("before")?"基线":"变更后")+"采集中；停止并确认还原后自动绑定会话。");
    }
    public void captureStarted(Path session){if(pendingRole!=null){runningSession=session.toAbsolutePath().normalize();DesktopPreferences.get().put("experiment."+pendingRole,runningSession.toString());}}
    public void captureFinished(Path session,boolean restored){
        if(pendingRole==null||runningSession==null||!runningSession.equals(session.toAbsolutePath().normalize()))return;
        String role=pendingRole;pendingRole=null;runningSession=null;
        status.setText(restored?"采集已停止，正在核验并绑定实验会话。":"还原未确认；保留会话路径，请核对停止回执。不会自动开始下一次采集。");
        if(restored)bindSession(role,session,false);
        else{if(role.equals("before"))beforeSession=null;else afterSession=null;(role.equals("before")?beforeStatus:afterStatus).setText("还原未确认；请核对停止回执：\n"+session);}
        updateActions();
    }
    private void updateActions(){boolean running=pendingRole!=null;baseline.setDisable(running);after.setDisable(running||beforeSession==null||!beforeSession.reasons().isEmpty());bindingControls.forEach(c->c.setDisable(running));compare.setDisable(running||!loading.isEmpty()||beforeSession==null||afterSession==null||(beforeSession.directory().equals(afterSession.directory())||beforeSession.sessionId().equals(afterSession.sessionId())));}
    private MonitoringConfig.Experiment declaration(){return new MonitoringConfig.Experiment(fields.get(0).getText(),fields.get(1).getText(),fields.get(2).getText(),fields.get(3).getText(),fields.get(4).getText());}
    @Override public void close(){closed=true;worker.shutdownNow();}
}
