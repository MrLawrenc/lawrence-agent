package com.agentmonitor.app.ui;

import com.agentmonitor.app.model.CallNode;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** In-process render and interaction checks against the real controls. */
public final class PreviewChecks {
    private static final Path OUTPUT = Path.of("build/ui-preview");
    private static int failures;
    public static void run(Stage stage) {
        PauseTransition ready = new PauseTransition(Duration.seconds(1));
        ready.setOnFinished(event -> {
            try {
                capture(stage.getScene(), "workspace-wide");
                stage.setWidth(1080); stage.setHeight(720);
                PauseTransition resize = new PauseTransition(Duration.millis(250));
                resize.setOnFinished(resized -> {
                    try {
                        require(stage.getScene().getWidth() <= 1080, "Compact render uses the resized window");
                        capture(stage.getScene(), "workspace-compact");
                        checkWorkbench(stage, () -> checkWorkspaceActions(stage, () -> checkPackageActions(stage, () -> checkScope(stage, () -> {
                            try { checkDetailUpdates(stage,()->{try{checkAdvancedConfiguration(stage);checkInspectorReadingPosition(stage);checkCallTree(stage); checkMetrics(stage); checkSelector(stage);}catch(Throwable error){fail(error);finish();}}); }
                            catch (Throwable error) { fail(error); finish(); }
                        }))));
                    } catch (Throwable error) { fail(error); finish(); }
                });
                resize.play();
            } catch (Throwable error) { fail(error); finish(); }
        });
        ready.play();
    }
    private static void checkInspectorReadingPosition(Stage owner)throws Exception {
        var pane=new CallDetailPane(()->{});var scene=scene(pane,420,600,owner);
        String longValue="line of captured SQL parameters\n".repeat(150);
        var first=new com.agentmonitor.model.trace.TraceNode("example.Service","work","worker",20,false,0,"()V",longValue,"","",com.agentmonitor.model.span.SpanKind.BUSINESS,java.util.Map.of("agent.span.id","stable-span"),"stable-trace").freeze();
        var node=CallNode.fromTrace(first);pane.show(node,20);
        ((TabPane)pane.lookup(".tab-pane")).getSelectionModel().select(1);scene.getRoot().applyCss();scene.getRoot().layout();
        var payload=(TextArea)pane.lookup("#call-detail-payload");payload.selectRange(10,30);payload.setScrollTop(120);
        double scroll=payload.getScrollTop();pane.show(node,20);
        require(scroll>0&&payload.getScrollTop()==scroll&&payload.getSelection().getStart()==10,"Unrelated refreshes preserve long-detail reading position and selection");
        var updated=new com.agentmonitor.model.trace.TraceNode("example.Service","work","worker",30,false,0,"()V",longValue+"new line","","",com.agentmonitor.model.span.SpanKind.BUSINESS,java.util.Map.of("agent.span.id","stable-span"),"stable-trace").freeze();
        pane.show(CallNode.fromTrace(updated),30);
        require(payload.getScrollTop()==scroll&&payload.getSelection().getEnd()==30&&payload.getText().contains("new line"),"New content for the same call preserves the reading position");
        pane.show(new CallNode("example.Other","next","worker",10,false,0),10);
        require(payload.getCaretPosition()==0&&payload.getScrollTop()==0,"Selecting a different call resets detail reading position");
        var http=new CallNode("HTTP Server","GET /shop/orders/42","worker",100,false,0,"","","","",
                com.agentmonitor.model.span.SpanKind.HTTP_SERVER,java.util.Map.of("http.request.method","GET","url.path","/shop/orders/42"));
        http.getChildren().add(new CallNode("example.CommerceController","order","worker",80,false,1,"(Ljava/lang/String;)V","\"order-42\"",""));
        var boundary=new CallDetailPane(()->{});boundary.show(http,100);
        var boundaryTabs=(TabPane)boundary.getChildren().get(3);
        var dataBody=(javafx.scene.layout.VBox)boundaryTabs.getTabs().get(1).getContent();
        var boundaryPayload=(TextArea)dataBody.getChildren().get(1);
        require(boundaryPayload.getText().contains("HTTP 入口不读取请求体")&&boundaryPayload.getText().contains("order-42")
                &&boundaryPayload.getText().contains("CommerceController#order"),"HTTP boundary identifies parameters captured by the controller child");
    }
    private static void checkWorkbench(Stage owner,Runnable next)throws Exception {
        Scene scene=owner.getScene();var workbench=(WorkbenchPane)scene.lookup("#workbench");
        var scope=(ClassSelectionPane)scene.lookup(".scope-editor");
        require(workbench.selectedPage().equals("scope"),"Production controller starts in capture configuration");
        require(scene.lookup(".runtime-overview-scroll")==null,"Scope table gets the full workspace instead of a permanent metrics sidebar");
        var start=(Button)scene.lookup("#capture-start");
        require(start.localToScene(start.getBoundsInLocal()).getMaxX()<=scene.getWidth(),"Capture action remains visible at 1080px");
        var loading=(StackPane)scene.lookup(".loading-overlay");
        var loadingPanel=loading.lookup(".loading-panel");
        ((Label)loading.lookup(".loading-label")).setText("正在注入 agent，请稍候...");
        loading.setVisible(true);
        scene.getRoot().applyCss();scene.getRoot().layout();
        require(loadingPanel.getBoundsInParent().getHeight()<=90&&loadingPanel.getBoundsInParent().getWidth()<=450,
                "Loading status stays compact inside the workbench");
        capture(scene,"workspace-loading");
        loading.setVisible(false);
        int windows=javafx.stage.Window.getWindows().size();
        var search=(TextField)scope.lookup("#scope-class-search");search.setText("Order");
        start.fireEvent(shortcut(javafx.scene.input.KeyCode.F));
        require(scene.getFocusOwner()==search&&search.getSelectedText().equals("Order"),"Find shortcut focuses the active page search even from the global capture bar");
        var beforeSearch=scope.getIncludedPackages();
        search.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",javafx.scene.input.KeyCode.ESCAPE,false,false,false,false));
        require(search.getText().isEmpty()&&scope.getIncludedPackages().equals(beforeSearch),"Escape clears the filter without changing capture scope");
        search.setText("Order");((Button)search.getParent().lookup(".search-clear")).fire();
        require(search.getText().isEmpty()&&scene.getFocusOwner()==search,"Clear search returns keyboard focus to the native field");search.setText("Order");
        ((Button)scope.lookup("#scope-edit-rules")).fire();
        boolean browserVisible=true;
        for(Node node=scope.lookup("#scope-class-table");node!=null;node=node.getParent())browserVisible&=node.isVisible();
        require(!browserVisible,"Rule editor replaces the browser instead of squeezing its height");

        ((Button)scope.lookup("#scope-edit-rules")).fire();
        workbench.selectPage("calls");
        var calls=(CallTreePane)scene.lookup(".call-tree-pane");
        var root=new CallNode("com.example.orders.OrderController","create","http-nio-8080-exec-1",128_000_000,false,0);
        root.getChildren().add(new CallNode("com.example.orders.OrderService","validateOrder","http-nio-8080-exec-1",34_000_000,false,1));
        root.getChildren().add(new CallNode("com.example.orders.OrderRepository","save","http-nio-8080-exec-1",81_000_000,false,1));
        calls.showOfflineReplay(List.of(root));
        scene.getRoot().applyCss();scene.getRoot().layout();
        var table=(TreeTableView<?>)calls.lookup("#call-tree-table");table.getSelectionModel().select(0);
        ((Button)calls.lookup("#call-show-details")).fire();

        workbench.selectPage("overview");
        workbench.selectPage("calls");
        require(calls==scene.lookup(".call-tree-pane")&&calls.lookup("#call-detail-pane")!=null,"Switching pages preserves the call inspector and selection");
        workbench.selectPage("advanced");
        var editor=(TextArea)scene.lookup("#advanced-yaml-editor");
        require(((Button)scene.lookup("#advanced-config-apply")).isDisabled(),"Unchanged YAML cannot be redundantly applied");
        editor.setText("version: [unfinished");
        require(!((Button)scene.lookup("#advanced-config-apply")).isDisabled()&&((Label)scene.lookup("#advanced-config-state")).getText().contains("未应用"),"YAML edits expose an explicit draft state and enable application");
        workbench.selectPage("scope");require(search.getText().equals("Order"),"Scope search survives navigation");
        workbench.selectPage("advanced");
        require(editor==scene.lookup("#advanced-yaml-editor")&&editor.getText().equals("version: [unfinished"),"Navigation retains an unapplied YAML draft");
        ((Button)scene.lookup("#advanced-config-apply")).fire();
        require(scene.lookup("#advanced-config-error").isVisible(),"Invalid YAML application stays on the page with an error");
        ((Button)scene.lookup("#advanced-config-reset")).fire();
        require(((Button)scene.lookup("#advanced-config-apply")).isDisabled(),"Restoring current configuration clears the draft state");
        require(editor.getText().contains("com.example.orders"),"Production scope actions synchronize the advanced configuration model");
        workbench.selectPage("diagnostics");scene.getRoot().applyCss();scene.getRoot().layout();
        var sections=(ListView<?>)scene.lookup("#diagnostics-sections");
        require(sections.getItems().size()==10,"All diagnostic evidence categories are reachable in the sidebar");
        sections.getSelectionModel().select(2);
        scene.getRoot().applyCss();scene.getRoot().layout();start.fireEvent(shortcut(javafx.scene.input.KeyCode.F));
        require(scene.getFocusOwner() instanceof TextField field&&field.getAccessibleText().equals("线程与锁"),"Find targets the selected diagnostic category, not a hidden evidence table");
        var diagnostics=(DiagnosticsPane)scene.lookup("#diagnostics-pane");var evidence=diagnostics.getCenter();
        diagnostics.showResult(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("result","A"));
        diagnostics.showResult(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("result","B"));
        ((Button)diagnostics.lookup("#diagnostics-result-back")).fire();
        require(diagnostics.getCenter()==evidence,"One back action returns to evidence after consecutive diagnostic results");
        workbench.selectPage("sessions");
        require(javafx.stage.Window.getWindows().size()==windows,"Main navigation and diagnostics open no additional windows");
        workbench.selectPage("scope");
        start.fireEvent(shortcut(javafx.scene.input.KeyCode.DIGIT2));
        require(workbench.selectedPage().equals("calls"),"Shortcut plus 2 opens calls even from the global toolbar");
        workbench.selectPage("scope");search.clear();
        ((Button)scope.lookup("#scope-edit-rules")).fire();
        after(()->{
            try{
                Node apply=scope.lookup("#scope-apply-rules");
                require(apply.localToScene(apply.getBoundsInLocal()).getMaxY()<scene.getHeight()-40,"Rule application remains above the footer at the 1080 by 720 minimum");
                capture(scene,"workspace-rules");
            }catch(Exception error){fail(error);}
            ((Button)scope.lookup("#scope-edit-rules")).fire();
            captureWorkbenchPages(owner,workbench,List.of("calls","overview","advanced","diagnostics","sessions"),0,next);
        });
    }
    private static javafx.scene.input.KeyEvent shortcut(javafx.scene.input.KeyCode code){
        return new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",code,false,true,false,true);
    }
    private static void captureWorkbenchPages(Stage owner,WorkbenchPane workbench,List<String> pages,int index,Runnable next){
        if(index==pages.size()){workbench.selectPage("scope");checkLongScope(owner,next);return;}
        String id=pages.get(index);workbench.selectPage(id);
        after(()->{try{capture(owner.getScene(),"workspace-"+id);}catch(Exception error){fail(error);}
            captureWorkbenchPages(owner,workbench,pages,index+1,next);});
    }
    private static void checkLongScope(Stage owner,Runnable next) {
        var scope=(ClassSelectionPane)owner.getScene().lookup(".scope-editor");
        var packages=scope.getIncludedPackages();var classes=scope.getIncludedClasses();
        var excluded=scope.getExcludedClassConditionList();var methods=scope.getExcludedMethods();
        String longPackage="com.example.orders.internal.enterprise.fulfilment.processing.distribution.shipping.reconciliation";
        scope.applyConfiguration(List.of(longPackage),classes,List.of("pkg:"+longPackage+".legacy"),methods);
        after(()->{
            try{
                var summary=(Label)scope.lookup("#scope-included-rules");
                require(summary.getWidth()<=420&&summary.getTooltip().getText().contains(longPackage),"Long package rules stay bounded with the full identifier available in a tooltip");
                Node table=scope.lookup("#scope-class-table");
                require(table.localToScene(table.getBoundsInLocal()).getMaxY()<owner.getScene().getHeight()-70&&table.getBoundsInLocal().getHeight()>100,"Long rules retain a usable table at the minimum window size");
                capture(owner.getScene(),"workspace-long-rules");
            }catch(Exception error){fail(error);}
            scope.applyConfiguration(packages,classes,excluded,methods);next.run();
        });
    }
    private static void checkDetailUpdates(Stage owner,Runnable next)throws Exception {
        var calls=new CallTreePane();Scene scene=scene(calls,1100,650,owner);
        var before=new com.agentmonitor.model.trace.TraceNode("example.Service","work","long-worker-thread-name",20_000_000,false,0,"()V","before","","",com.agentmonitor.model.span.SpanKind.BUSINESS,java.util.Map.of("agent.span.id","detail-span"),"detail-trace").freeze();
        calls.showOfflineReplay(List.of(CallNode.fromTrace(before)));
        var table=(TreeTableView<?>)calls.lookup("#call-tree-table");table.getSelectionModel().select(0);((Button)calls.lookup("#call-show-details")).fire();
        scene.getRoot().applyCss();scene.getRoot().layout();
        ((TitledPane)calls.lookup(".call-detail-raw")).setExpanded(true);
        scene.getRoot().applyCss();scene.getRoot().layout();
        var context=(TextArea)calls.lookup("#call-detail-overview");context.selectRange(2,8);
        var after=new com.agentmonitor.model.trace.TraceNode("example.Service","work","long-worker-thread-name",90_000_000,true,0,"()V","after","","fixture stack",com.agentmonitor.model.span.SpanKind.BUSINESS,java.util.Map.of("agent.span.id","detail-span"),"detail-trace").freeze();
        calls.addTrace(after);
        after(()->{
            require(((Label)calls.lookup("#call-detail-summary")).getText().contains("90.00"),"A complete snapshot refreshes the open inspector by trace and span identity");
            require(((TextArea)calls.lookup("#call-detail-errors")).getText().contains("fixture stack"),"Updated exception evidence replaces the earlier call preview");
            require(context.getSelection().getStart()==2&&context.getSelection().getEnd()==8,"An updated snapshot preserves selection in unchanged call details");
            try{capture(scene,"calls-details-updated");}catch(Exception error){fail(error);}
            ((TextField)calls.lookup(".search-field")).setText("no-visible-calls");
            require(calls.lookup("#call-detail-pane")==null,"Filtering a call out of view clears stale inspector content");next.run();
        });
    }
    private static void checkAdvancedConfiguration(Stage owner)throws Exception {
        var initial=com.agentmonitor.model.config.MonitoringConfig.defaults().withScope(new com.agentmonitor.model.config.MonitoringConfig.Scope(List.of("com.example.orders"),List.of(),List.of("cls:*DTO"),List.of("get*")));
        var current=new java.util.concurrent.atomic.AtomicReference<>(initial);
        var pane=new AdvancedConfigPane(current::get,true);Scene scene=scene(pane,900,680,owner);
        var editor=(TextArea)pane.lookup("#advanced-yaml-editor");
        require(!pane.dirtyProperty().get(),"A newly loaded YAML editor is clean");
        require(editor.getText().equals(com.agentmonitor.core.config.MonitoringConfigYaml.write(initial)),"Advanced YAML starts from the complete current configuration");
        editor.setText("version: 2\nscope: [invalid\n");
        require(pane.validateConfiguration().isEmpty()&&pane.lookup("#advanced-config-error").isVisible(),"Invalid YAML remains an editable draft with an inline error");
        require(current.get().equals(initial),"Invalid advanced drafts do not replace the current configuration");capture(scene,"advanced-invalid");
        editor.setText(com.agentmonitor.core.config.MonitoringConfigYaml.write(initial));
        require(!pane.dirtyProperty().get()&&!pane.lookup("#advanced-config-error").isVisible(),"Editing back to the clean baseline removes obsolete validation errors");
        editor.setText(com.agentmonitor.core.config.MonitoringConfigYaml.write(initial).replace("ratePercent: 10","ratePercent: 37"));
        var parsed=pane.validateConfiguration().orElseThrow();require(parsed.sampling().ratePercent()==37&&parsed.scope().equals(initial.scope()),"Custom sampling preserves the existing visual scope");
        editor.appendText("\n# Keep this operator note\n");current.set(parsed);pane.markApplied(parsed);
        require(!pane.dirtyProperty().get()&&editor.getText().contains("operator note")&&((Label)pane.lookup("#advanced-config-state")).getText().contains("已应用"),"Applying preserves YAML comments and reports the accepted draft as clean");
        current.set(parsed);editor.setText("discard this draft");((Button)pane.lookup("#advanced-config-reset")).fire();
        require(editor.getText().equals(com.agentmonitor.core.config.MonitoringConfigYaml.write(parsed)),"Reset reloads current settings rather than factory defaults");capture(scene,"advanced-config");
        editor.setText(editor.getText().replace("ratePercent: 37","ratePercent: 42"));
        current.set(parsed.withScope(new com.agentmonitor.model.config.MonitoringConfig.Scope(List.of("com.example.billing"),List.of(),List.of(),List.of())));
        require(pane.validateConfiguration().isEmpty(),"A stale YAML draft cannot overwrite scope changes from another page");
        ((Button)pane.lookup("#advanced-config-reset")).fire();
        require(pane.validateConfiguration().isPresent(),"Reloading current settings resolves the cross-page draft conflict");
    }
    private static void checkPackageActions(Stage owner,Runnable next) {
        var scope=new ClassSelectionPane("preview");
        var popup=new Stage();popup.initOwner(owner);var scene=new Scene(scope,1100,820);scene.getStylesheets().addAll(owner.getScene().getStylesheets());popup.setScene(scene);popup.show();
        scope.applyConfiguration(List.of(),List.of(),List.of(),List.of());
        var notifications=new java.util.concurrent.atomic.AtomicInteger();scope.setOnConfigChanged(ignored->notifications.incrementAndGet());
        await(()->scope.lookup("#scope-package-tree") instanceof TreeView<?> tree && findRulePackage(tree.getRoot(),"com.example")!=null,100,()->{
            @SuppressWarnings("unchecked") var tree=(TreeView<String>)scope.lookup("#scope-package-tree");
            tree.getSelectionModel().select(findRulePackage(tree.getRoot(),"com.example"));
            ((Button)scope.lookup("#scope-include-package")).fire();
            require(scope.getIncludedPackages().equals(List.of("com.example")),"Include whole parent is a package rule, not a current-page selection");
            tree.getSelectionModel().select(findRulePackage(tree.getRoot(),"com.example.billing"));
            ((Button)scope.lookup("#scope-exclude-package")).fire();
            await(()->((Label)scope.lookup("#scope-package-summary")).getText().contains("纳入采集 0"),100,()->{
                require(scope.getIncludedPackages().equals(List.of("com.example"))&&scope.getExcludedClassConditionList().contains("pkg:com.example.billing"),"Excluding a child preserves the included parent and records a future-safe package exclusion");
                require(((Label)scope.lookup("#scope-excluded-packages")).getText().contains("com.example.billing"),"Active child exclusions are visible without opening advanced rules");
                require(notifications.get()==2,"Parent and child edits both notify the YAML/controller binding");
                try{capture(scene,"scope-parent-child-rules");}catch(Exception failure){fail(failure);}
                ((Button)scope.lookup("#scope-clear")).fire();
                require(scope.getIncludedPackages().isEmpty()&&scope.lookup("#scope-undo-clear").isVisible(),"Clearing scope offers a visible undo action");
                ((Button)scope.lookup("#scope-browse-all")).fire();
                ((Button)scope.lookup("#scope-undo-clear")).fire();
                require(scope.getIncludedPackages().equals(List.of("com.example"))&&scope.getExcludedClassConditionList().contains("pkg:com.example.billing")&&notifications.get()==4,"Undo survives browsing and restores exact rules through the controller binding");
                tree.getSelectionModel().select(findRulePackage(tree.getRoot(),"com.example.billing"));
                ((Button)scope.lookup("#scope-inherit-package")).fire();
                require(scope.getExcludedClassConditionList().isEmpty()&&notifications.get()==5,"Restore follows parent and notifies the controller");
                ((Button)scope.lookup("#scope-clear")).fire();
                ((Button)scope.lookup("#scope-include-package")).fire();
                require(!scope.lookup("#scope-undo-clear").isVisible(),"A newer scope edit invalidates undo instead of allowing old rules to overwrite it");
                scope.dispose();popup.close();next.run();
            });
        });
    }
    private static <T> TreeItem<T> findRulePackage(TreeItem<T> root,String path) {
        if(root==null)return null;if(path.equals(root.getValue()))return root;
        for(var child:root.getChildren()){var found=findRulePackage(child,path);if(found!=null)return found;}return null;
    }
    private static void checkWorkspaceActions(Stage owner,Runnable next) {
        var hits=new java.util.concurrent.atomic.AtomicInteger();
        Button refresh=new Button("刷新范围"),calls=new Button("查看调用链"),imported=new Button("导入配置"),saved=new Button("保存配置"),logs=new Button("运行日志"),history=new Button("历史会话"),current=new Button("本次会话");
        for(Button button:List.of(imported,saved,logs,history,current))button.setOnAction(e -> hits.incrementAndGet());
        current.setDisable(true);
        var bar=new WorkspaceActionBar(new javafx.scene.layout.VBox(2,new Label("监控中"),new Label("已运行 00:12:30")),refresh,calls,imported,saved,logs,history,current);
        var stage=new Stage();stage.initOwner(owner);stage.setTitle("工具栏交互检查 · 示例");
        var root=new javafx.scene.layout.VBox(bar);root.setPadding(new javafx.geometry.Insets(10));
        var scene=new Scene(root,930,120);scene.getStylesheets().addAll(owner.getScene().getStylesheets());stage.setScene(scene);stage.show();
        after(() -> {
            var config=(MenuButton)bar.lookup("#workspace-config-menu");var sessions=(MenuButton)bar.lookup("#workspace-session-menu");
            require(config.getItems().size()==2,"Configuration menu groups import and save actions");
            config.getItems().get(0).fire();config.getItems().get(1).fire();
            require(hits.get()==2,"Menu actions delegate to the existing config handlers");
            imported.setDisable(true);config.getItems().get(0).fire();
            require(config.getItems().get(0).isDisable() && hits.get()==2,"Disabled source actions stay disabled in menus");
            var result=sessions.getItems().get(0);require(result.isDisable() && result.getText().contains("停止后"),"Unavailable current-session results explain when they become available");
            result.fire();require(hits.get()==2,"Unavailable session menu cannot invoke the source action");
            current.setDisable(false);result.fire();
            require(!result.isDisable() && result.getText().equals("本次采集结果") && hits.get()==3,"Session menu follows the source action's live availability");
            var advancedHits=new java.util.concurrent.atomic.AtomicInteger();var advanced=new Button("高级配置");advanced.setOnAction(e->advancedHits.incrementAndGet());bar.addAdvancedConfigurationAction(advanced);
            config.getItems().get(0).fire();require(advancedHits.get()==1&&config.getItems().get(0).getId().equals("workspace-advanced-config"),"Advanced YAML is discoverable from the configuration menu");
            require(config.getItems().get(2).isDisable(),"Adding advanced settings preserves existing import availability");
            bar.applyCss();bar.layout();
            var stateBottom=bar.getChildren().get(0).localToScene(bar.getChildren().get(0).getBoundsInLocal()).getMaxY();
            require(stateBottom<=bar.localToScene(bar.getBoundsInLocal()).getMaxY()+.5,"Two-line running status stays inside the action bar");
            config.show();require(config.isShowing(),"Native configuration popup opens on the visible toolbar");config.hide();
            try {capture(scene,"toolbar-wide");}catch(Exception error){fail(error);}
            stage.setWidth(460);
            after(() -> {
                require(config.localToScene(config.getBoundsInLocal()).getMinY()>calls.localToScene(calls.getBoundsInLocal()).getMaxY(),"Narrow toolbar moves secondary groups to a second row");
                require(calls.localToScene(calls.getBoundsInLocal()).getMaxX()<scene.getWidth(),"Primary actions remain inside the narrow viewport");
                require(logs.localToScene(logs.getBoundsInLocal()).getMaxX()<scene.getWidth(),"Secondary actions remain inside the narrow viewport");
                try {capture(scene,"toolbar-compact");}catch(Exception error){fail(error);}
                stage.setWidth(950);
                after(() -> {
                    require(Math.abs(config.localToScene(config.getBoundsInLocal()).getMinY()-calls.localToScene(calls.getBoundsInLocal()).getMinY())<12,"Widening restores one toolbar row without duplicate actions");
                    require(bar.lookupAll("#workspace-config-menu").size()==1,"Responsive reparenting preserves a single live menu");
                    stage.close();next.run();
                });
            });
        });
    }
    private static void checkScope(Stage stage, Runnable next) {
        ClassSelectionPane scope = (ClassSelectionPane) stage.getScene().lookup(".scope-editor");
        var beforePackages = scope.getIncludedPackages(); var beforeClasses = scope.getIncludedClasses();
        TextField search = (TextField) scope.lookup("#scope-class-search");
        ((Button) scope.lookup("#scope-browse-all")).fire(); search.setText("PaymentGateway");
        after(() -> {
            require(beforePackages.equals(scope.getIncludedPackages()) && beforeClasses.equals(scope.getIncludedClasses()), "Browsing and searching never mutate rules");
            @SuppressWarnings("unchecked") TableView<ClassSelectionPane.ClassRow> table = (TableView<ClassSelectionPane.ClassRow>) scope.lookup("#scope-class-table");
            require(table.getItems().size() == 1, "Global search finds classes outside selected packages");
            if (!table.getItems().isEmpty()) table.getItems().get(0).includedProperty().set(true);
            require(scope.getIncludedClasses().contains("com.example.billing.PaymentGateway"), "A class can be independently included");
            search.setText("OrderDTO");
            after(() -> {
                require(table.getItems().size() == 1, "Rule-excluded classes remain discoverable");
                if (!table.getItems().isEmpty()) table.getItems().get(0).includedProperty().set(true);
                require(!scope.getIncludedClasses().contains("com.example.orders.OrderDTO") && !table.getItems().get(0).isIncluded(), "Wildcard-excluded class cannot show a false selected state");
                ((Button) scope.lookup("#scope-clear")).fire();
                require(scope.getIncludedPackages().isEmpty() && scope.getIncludedClasses().isEmpty(), "Clear produces a truly empty draft scope");
                TextArea rules = (TextArea) scope.lookup("#scope-class-rules"); rules.setText("cls:*Service");
                require(scope.hasPendingRuleEdits(), "Rule text remains an explicit unapplied draft");
                ((Button) scope.lookup("#scope-apply-rules")).fire();
                require(!scope.hasPendingRuleEdits() && scope.getExcludedClassConditionList().equals(List.of("cls:*Service")), "Applying rules updates the sole selection model");
                scope.applyConfiguration(List.of("com.example.orders"), List.of("missing.Future"), List.of("cls:*DTO"), List.of());
                scope.reloadClasses();
                after(() -> {
                    require(scope.getIncludedClasses().contains("missing.Future"), "Index reload preserves unloaded exact-class rules");
                    checkScopeRaces(stage,next);
                });
            });
        });
    }
    private static void checkScopeRaces(Stage owner,Runnable next) {
        var scope=new ClassSelectionPane("large-preview");
        scene(scope,1000,800,owner);
        scope.applyConfiguration(List.of(),List.of(),List.of(),List.of());
        ((TextField)scope.lookup("#scope-class-search")).setText("Item");
        after(() -> after(() -> after(() -> {
            var tree=(TreeView<?>)scope.lookup("#scope-package-tree");
            require(!tree.getRoot().getChildren().isEmpty(),"Delayed index commit rebuilds package tree after early query");
            @SuppressWarnings("unchecked") var table=(TableView<ClassSelectionPane.ClassRow>)scope.lookup("#scope-class-table");
            ((Button)scope.lookup("#scope-next-page")).fire();
            var row=table.getItems().get(0); String secondPage=row.getClassName(); row.includedProperty().set(true);
            after(() -> {
                require(table.getItems().get(0).getClassName().equals(secondPage),"Editing a class preserves the current page");
                var before=scope.getIncludedClasses();
                ((TextArea)scope.lookup("#scope-class-rules")).setText("cls:*DTO");
                table.getItems().get(0).includedProperty().set(false);
                ((Button)scope.lookup("#scope-include-page")).fire();
                require(before.equals(scope.getIncludedClasses()),"Unapplied rule drafts block conflicting checkbox and batch mutations");
                scope.dispose(); checkScaleScope(owner,next);
            });
        })));
    }
    private static void checkScaleScope(Stage owner,Runnable next) {
        var scope=new ClassSelectionPane("scale-preview");scene(scope,1180,760,owner);
        scope.applyConfiguration(List.of("com.example.orders"),List.of(),List.of(),List.of());
        var pulse=new long[]{System.nanoTime(),0};
        var timer=new javafx.animation.AnimationTimer(){public void handle(long now){pulse[1]=Math.max(pulse[1],now-pulse[0]);pulse[0]=now;}};timer.start();
        await(() -> ((Label)scope.lookup("#scope-global-summary")).getText().contains("2,250"),100,() -> {
            require(((Label)scope.lookup("#scope-index-summary")).getText().contains("141,307"),"Large-scale index count is separately labeled as target JVM data");
            require(((Label)scope.lookup("#scope-global-summary")).getText().contains("全局待采集"),"Global capture count is explicitly distinguished from browsing");
            @SuppressWarnings("unchecked") var tree=(TreeView<String>)scope.lookup("#scope-package-tree");
            await(() -> findPackage(tree.getRoot(),"com.example.orders.group007")!=null,100,() -> {
                var beforePackages=scope.getIncludedPackages();var beforeClasses=scope.getIncludedClasses();
                var branch=findPackage(tree.getRoot(),"com.example.orders.group007");
                for(var ancestor=branch.getParent();ancestor!=null;ancestor=ancestor.getParent())ancestor.setExpanded(true);
                tree.getSelectionModel().select(branch);
                require(((Button)scope.lookup("#scope-include-page")).isDisabled(),"Switching packages blocks bulk edits until the new list commits");
                ((Button)scope.lookup("#scope-include-page")).fire();
                require(beforePackages.equals(scope.getIncludedPackages()) && beforeClasses.equals(scope.getIncludedClasses()),"An immediate bulk click cannot select classes from the previous package");
                await(() -> ((Label)scope.lookup("#scope-package-summary")).getText().contains("35 类"),100,() -> {
                    require(((Label)scope.lookup("#scope-location")).getText().contains("com.example.orders.group007"),"Current package full path remains visible beside its own counts");
                    require(((Label)scope.lookup("#scope-global-summary")).getText().contains("2,250"),"Browsing a 35-class package does not replace the 2250-class global scope");
                    @SuppressWarnings("unchecked") var table=(TableView<ClassSelectionPane.ClassRow>)scope.lookup("#scope-class-table");
                    require(table.getItems().size()==35,"Real controls render the exact package range at 141307 indexed classes");
                    ((TextField)scope.lookup("#scope-class-search")).setText("00000");
                    ((TextField)scope.lookup("#scope-class-search")).setText("00250");
                    await(() -> table.getItems().size()==1 && table.getItems().get(0).getClassName().endsWith("00250"),100,() -> {
                        require(((Label)scope.lookup("#scope-package-summary")).getText().contains("35 类"),"Text search preserves package totals while narrowing the current list");
                        require(((Label)scope.lookup("#scope-filter-summary")).getText().contains("当前列表：1 类"),"Search results have their own unambiguous count");
                        timer.stop();System.out.println("UI_SCALE_MAX_PULSE_GAP_MS="+pulse[1]/1e6);
                        try {
                            var measurement=java.util.Map.of("indexClasses",141307,"selectedClasses",2250,"browsedPackageClasses",35,"maxPulseGapMs",pulse[1]/1e6,
                                "scope","fixture-driven class loading, indexing, tree construction and two searches; JavaFX pulse includes OS scheduling");
                            Files.createDirectories(OUTPUT);Files.writeString(OUTPUT.resolve("scale-interaction.json"),new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(measurement));
                            capture(scope.getScene(),"scope-large-index");
                        } catch(Exception error){fail(error);}
                        scope.dispose();next.run();
                    });
                });
            });
        });
    }
    private static TreeItem<String> findPackage(TreeItem<String> root,String name) {
        if(root==null)return null;
        var queue=new java.util.ArrayDeque<TreeItem<String>>();queue.add(root);
        while(!queue.isEmpty()){var next=queue.removeFirst();if(name.equals(next.getValue()))return next;queue.addAll(next.getChildren());}return null;
    }
    private static void await(java.util.function.BooleanSupplier condition,int attempts,Runnable action) {
        if(condition.getAsBoolean()){try{action.run();}catch(Throwable error){fail(error);finish();}return;}
        if(attempts==0){fail(new AssertionError("Timed out awaiting current scope result"));finish();return;}
        var wait=new PauseTransition(Duration.millis(50));wait.setOnFinished(e -> await(condition,attempts-1,action));wait.play();
    }
    private static void after(Runnable action) {
        PauseTransition delay = new PauseTransition(Duration.millis(350));
        delay.setOnFinished(event -> { try { action.run(); } catch (Throwable error) { fail(error); finish(); } }); delay.play();
    }
    private static void checkCallTree(Stage owner) throws Exception {
        CallTreePane calls = new CallTreePane();
        CallNode root = new CallNode("com.example.OrderController", "create", "request-1", 128_000_000, false, 0);
        root.getChildren().add(new CallNode("com.example.OrderService", "validateOrder", "worker-2", 34_000_000, false, 1));
        calls.showOfflineReplay(List.of(root));
        Scene scene = scene(calls, 1020, 600, owner);
        TextField search = (TextField) calls.lookup(".search-field");
        TreeTableView<?> table = (TreeTableView<?>) calls.lookup(".call-tree-table");
        search.setText("validateOrder");
        require(table.getRoot().getChildren().size() == 1, "Child-call search preserves its root");
        capture(scene, "calls-child-match");
        table.getSelectionModel().select(0);((Button)calls.lookup("#call-show-details")).fire();
        require(calls.lookup("#call-detail-pane")!=null,"Call details open beside the tree without another dialog");
        scene.getRoot().applyCss();scene.getRoot().layout();
        require(((Label)calls.lookup("#call-detail-thread")).getText().contains("request-1"),"Inspector retains the full thread context");
        capture(scene,"calls-with-details");
        ((Button)calls.lookup("#call-show-details")).fire();
        require(calls.lookup("#call-detail-pane")==null,"Inspector can be collapsed to return space to the tree");
        search.setText("no-match");
        require(table.getRoot().getChildren().isEmpty(), "Unmatched search empties the table");
        require(calls.lookupAll(".empty-state-title").stream().anyMatch(n -> n.isVisible() && ((Label)n).getText().contains("没有匹配")), "No-match guidance is displayed");
        capture(scene, "calls-no-match");
        calls.clear();
        search.clear();
        calls.showOfflineReplay(List.of(root));
        require(table.getRoot().getChildren().size() == 1, "Replay can be loaded after clearing");
        var shared = new com.agentmonitor.model.trace.TraceNode("example.Service", "work", "worker", 20_000_000, false, 0);
        shared.addChild(new com.agentmonitor.model.trace.TraceNode("example.Dao", "read", "worker", 10_000_000, false, 1));
        shared.freeze();
        CallNode adapted = CallNode.fromTrace(shared);
        require(adapted.getChildren().size() == 1 && adapted.getDurationNanos() == shared.getDurationNanos(), "Desktop adapter preserves headless trace evidence");
        boolean immutable = false;
        try { shared.addChild(shared); } catch (IllegalStateException expected) { immutable = true; }
        require(immutable, "Published headless trees reject mutation");
        calls.addTrace(shared, () -> false);
        javafx.application.Platform.runLater(() -> {
            try {
                require(table.getRoot().getChildren().size() == 1, "Queued traces from an old session are discarded");
                calls.beginMonitoringSession();
                for (int pass=0;pass<2;pass++) for(int i=0;i<600;i++) {
                    var trace=new com.agentmonitor.model.trace.TraceNode("example.Service","work","worker",100,false,0,"","","","",com.agentmonitor.model.span.SpanKind.BUSINESS,java.util.Map.of(),"trace-"+i).freeze();
                    calls.addTrace(trace);
                }
                Platform.runLater(() -> {
                    try {require(calls.getObservedRootCount()==600,"Final snapshots do not recount previews evicted by the 500-row window");}
                    catch(Throwable error){fail(error);}
                });
            } catch(Throwable error){fail(error);}
        });
    }
    private static void checkMetrics(Stage owner) throws Exception {
        JvmMetricsPane metrics = new JvmMetricsPane();
        Scene scene = scene(metrics, 340, 620, owner);
        require(metrics.lookupAll(".metric-card-value").stream().allMatch(n -> ((Label)n).getText().equals("—")), "Unsampled metrics do not display fabricated zeroes");
        capture(scene, "metrics-waiting");
        metrics.update(new com.agentmonitor.model.jvm.JvmMetrics(10, 20, 40, 5, 10, 12, 6, 200, 210, 10, 3, 20, 0, ""));
        metrics.update(com.agentmonitor.model.jvm.JvmMetrics.unavailable("示例采集失败"));
        require(metrics.lookupAll(".metric-card-value").stream().allMatch(n -> ((Label)n).getText().equals("—") || ((Label)n).getText().equals("不可用")), "Failed sampling clears all stale metrics");
    }
    private static void checkSelector(Stage owner) {
        JvmSelectDialog selector = new JvmSelectDialog(owner);
        java.util.concurrent.atomic.AtomicInteger confirmations = new java.util.concurrent.atomic.AtomicInteger();
        selector.show(p -> confirmations.incrementAndGet());
        PauseTransition ready = new PauseTransition(Duration.millis(350));
        ready.setOnFinished(event -> {
            try {
                java.lang.reflect.Field field = JvmSelectDialog.class.getDeclaredField("dialog");
                field.setAccessible(true);
                Stage dialog = (Stage) field.get(selector);
                TextField search = (TextField) dialog.getScene().lookup(".search-field");
                ListView<?> list = (ListView<?>) dialog.getScene().lookup(".jvm-list-view");
                capture(dialog.getScene(), "selector");
                search.setText("NO_SUCH_PROCESS");
                require(list.getItems().isEmpty(), "Selector filters unmatched queries");
                capture(dialog.getScene(), "selector-no-match");
                search.clear();
                require(list.getItems().size() == 3, "Clearing the query restores all fixture processes");
                search.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
                require(confirmations.get() == 1 && !dialog.isShowing(), "Enter in the search field confirms the selected process once");
            } catch (Throwable error) { fail(error);finish();return; }
            int windows=javafx.stage.Window.getWindows().size();
            var embedded=new JvmSelectDialog(owner);var selected=new java.util.concurrent.atomic.AtomicInteger();
            embedded.showEmbedded(owner.getScene(),p->selected.incrementAndGet());
            after(()->{
                try{
                    require(javafx.stage.Window.getWindows().size()==windows,"Embedded process selection stays in the existing window");
                    require(owner.getScene().lookup(".jvm-list-view") instanceof ListView<?>,"Embedded selector exposes the real process list");
                    capture(owner.getScene(),"selector-embedded");
                    var field=(TextField)owner.getScene().lookup(".search-field");
                    field.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED,"","",javafx.scene.input.KeyCode.ENTER,false,false,false,false));
                    require(selected.get()==1,"Embedded process selection confirms once with Enter");
                    var app=new com.agentmonitor.app.MainApp();
                    Stage compact=new Stage();
                    try {
                        app.start(compact);
                        require(compact.getWidth()<=800 && compact.getHeight()<=560,
                                "Production target selector resizes the native window");
                        capture(compact.getScene(),"selector-window-compact");
                        var showMain=com.agentmonitor.app.MainApp.class.getDeclaredMethod("showMain",com.agentmonitor.model.jvm.JvmProcess.class);
                        showMain.setAccessible(true);
                        showMain.invoke(app,new com.agentmonitor.model.jvm.JvmProcess("preview","Preview JVM"));
                        require(compact.getWidth()>=1080 && compact.getHeight()>=720,
                                "Entering the workbench restores its usable window size");
                        var controller=com.agentmonitor.app.MainApp.class.getDeclaredField("controller");
                        controller.setAccessible(true);
                        ((com.agentmonitor.app.controller.MainController)controller.get(app)).onWindowClose();
                        var showSelector=com.agentmonitor.app.MainApp.class.getDeclaredMethod("showSelectDialog");
                        showSelector.setAccessible(true);
                        showSelector.invoke(app);
                        require(compact.getWidth()<=800 && compact.getHeight()<=560,
                                "Switching targets returns to the compact native window");
                    } finally { compact.close(); }
                }catch(Throwable error){fail(error);}finish();
            });
        });
        ready.play();
    }
    private static Scene scene(Node content, double width, double height, Stage owner) {
        Scene scene = new Scene(new StackPane(content), width, height);
        scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        scene.getRoot().applyCss(); scene.getRoot().layout();
        return scene;
    }
    private static void capture(Scene scene, String name) throws Exception {
        scene.getRoot().applyCss(); scene.getRoot().layout();
        WritableImage image = scene.getRoot().snapshot(null, null);
        BufferedImage png = new BufferedImage((int)image.getWidth(), (int)image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Files.createDirectories(OUTPUT);
        ImageIO.write(png, "png", OUTPUT.resolve(name + ".png").toFile());
        System.out.println("RENDER " + OUTPUT.resolve(name + ".png"));
    }
    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }
    private static void fail(Throwable error) { failures++; error.printStackTrace(); }
    private static void finish() { System.out.println("UI_CHECKS_FAILURES=" + failures); Platform.exit(); if (failures > 0) System.exit(1); }
}
