package com.agentmonitor.app.ui;

import com.agentmonitor.core.service.JvmService;
import com.agentmonitor.core.service.JvmClassIndexCache;
import com.agentmonitor.core.util.AppLog;
import com.agentmonitor.core.scope.ScopeClassIndex;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.config.ScopeSelection;
import com.fasterxml.jackson.databind.*;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.beans.property.*;
import javafx.collections.*;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** A rule editor with independent browsing. Only explicit edits change the scope contract. */
public class ClassSelectionPane extends VBox {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ACTUAL_RESULT_READ_ATTEMPTS = 15;
    private static final long ACTUAL_RESULT_READ_RETRY_MILLIS = 200;
    private static final int PAGE_SIZE = 150;
    private final String pid;
    private final JvmClassIndexCache cache;
    private final ExecutorService worker = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "scope-editor-worker"); thread.setDaemon(true); return thread;
    });
    private final ThreadPoolExecutor queryWorker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),task -> {
        Thread thread=new Thread(task,"scope-latest-query");thread.setDaemon(true);return thread;
    },new ThreadPoolExecutor.DiscardOldestPolicy());
    private Future<?> queryTask;
    private volatile ScopeClassIndex scopeIndex=new ScopeClassIndex(List.of(),Map.of());
    private final Label globalScopeLabel=new Label("全局采集范围：正在计算…");
    private final Label indexSummaryLabel=new Label("目标 JVM 索引：正在读取…");
    private final Label packageSummaryLabel=new Label();
    private ScopeSelection selection = new ScopeSelection(MonitoringConfig.Scope.defaults());
    private volatile List<String> classNames = List.of();
    private volatile Map<String, List<String>> methodIndex = Map.of();
    private List<String> packageNames = List.of();
    private List<String> filtered = List.of();
    private String browsingPackage = "";
    private final TreeView<String> packageTree = new TreeView<>(new TreeItem<>("全部包"));
    private final TableView<ClassRow> classTable = new TableView<>();
    private final TextField packageSearchField = new TextField();
    private final TextField classSearchField = new TextField();
    private final CheckBox onlySelected = new CheckBox("只看已纳入");
    private final Label classSummaryLabel = new Label("正在建立类索引…");
    private final Label locationLabel = new Label("当前浏览：全部已发现的类");
    private final Label notice = new Label("勾选包包含子包；排除规则优先。修改会同步到高级配置。");
    private final Button undoClear = new Button("撤销清空");
    private MonitoringConfig.Scope clearedScope;
    private final Label ruleSummary = new Label();
    private final Label includedRuleLabel=new Label(), excludedPackageLabel=new Label(), packageStateLabel=new Label();
    private final Button includePackage=new Button("纳入整包"),excludePackage=new Button("排除此包"),inheritPackage=new Button("恢复跟随父包");
    private final Label pageLabel = new Label();
    private final Button includePage=new Button("纳入本页"),excludePage=new Button("移出本页");
    private final Label emptyClassLabel=label("没有匹配的类\n可浏览全部、清除搜索，或关闭“只看已纳入”。","hint-label");
    private boolean queryPending=true;
    private final Button previous = new Button("上一页"), next = new Button("下一页");
    private final TextArea classRules = new TextArea(), methodRules = new TextArea();
    private final TitledPane rulesPane = new TitledPane();
    private final PauseTransition queryDelay = new PauseTransition(Duration.millis(160));
    private Consumer<Void> onConfigChanged;
    private Consumer<Boolean> onLoadingChanged;
    private Consumer<String> onProgressChanged;
    private Consumer<ActualEnhancementMetrics> onActualEnhancementResultChanged;
    private boolean loadingClasses, disposed, rendering, rulesDirty, syncingRules;
    private long revision, queryRevision, loadRevision, treeRevision, indexRevision, browseRevision, renderedBrowseRevision;
    private long renderedIndex = -1;
    private String renderedPackageNeedle = null;
    private javafx.animation.Timeline treeBuild;
    private int page;
    private ActualEnhancementResult actualEnhancementResult;
    private long actualEnhancementLoadGeneration;

    public ClassSelectionPane(String pid) {
        this.pid = pid; cache = new JvmClassIndexCache(pid);
        getStyleClass().addAll("class-selection-pane", "scope-editor"); setSpacing(10);
        Button clear = button("清空范围", this::clearScope); clear.setId("scope-clear");
        undoClear.setId("scope-undo-clear");undoClear.setMinWidth(Region.USE_PREF_SIZE);undoClear.setVisible(false);undoClear.managedProperty().bind(undoClear.visibleProperty());
        undoClear.setOnAction(event -> {
            if (clearedScope == null || rulesDirty) return;
            selection = new ScopeSelection(clearedScope);changed();notice.setText("已恢复清空前的采集范围。");
        });
        Button viewRules = button("编辑排除规则", () -> rulesPane.setExpanded(!rulesPane.isExpanded()));
        viewRules.setId("scope-edit-rules");
        viewRules.textProperty().bind(javafx.beans.binding.Bindings.when(rulesPane.expandedProperty()).then("返回包与类").otherwise("编辑排除规则"));
        HBox heading = new HBox(10, globalScopeLabel, spacer(), viewRules, clear); heading.setAlignment(Pos.CENTER_LEFT);
        ruleSummary.getStyleClass().add("scope-rule-summary");ruleSummary.setId("scope-rule-summary");
        includedRuleLabel.setId("scope-included-rules");excludedPackageLabel.setId("scope-excluded-packages");
        for(Label row:List.of(includedRuleLabel,excludedPackageLabel)){
            row.setMaxWidth(420);row.setTextOverrun(OverrunStyle.ELLIPSIS);row.getStyleClass().add("scope-rule-summary");
            Tooltip complete=new Tooltip();complete.setWrapText(true);complete.setMaxWidth(600);complete.textProperty().bind(row.textProperty());row.setTooltip(complete);
        }
        packageSearchField.setPromptText("查找包名"); packageSearchField.setId("scope-package-search"); packageSearchField.getStyleClass().add("search-field");
        classSearchField.setPromptText("搜索完整类名或简单类名"); classSearchField.setId("scope-class-search"); classSearchField.getStyleClass().add("search-field");
        packageTree.setShowRoot(false); packageTree.setId("scope-package-tree"); packageTree.getStyleClass().add("package-tree");
        packageTree.setCellFactory(tree -> packageCell());
        packageTree.getSelectionModel().selectedItemProperty().addListener((obs, before, item) -> {
            if (rendering || item == null) return;
            browsingPackage = item.getValue();updatePackageActions(); scheduleQuery();
        });
        Button all = button("浏览全部", () -> { browsingPackage = ""; packageTree.getSelectionModel().clearSelection();updatePackageActions(); scheduleQuery(); });
        all.setId("scope-browse-all");
        indexSummaryLabel.getStyleClass().add("scope-index-summary");indexSummaryLabel.setId("scope-index-summary");indexSummaryLabel.setWrapText(true);
        HBox packageHeader = new HBox(8,label("包目录", "section-header"),spacer(),all);packageHeader.setAlignment(Pos.CENTER_LEFT);
        VBox packages = new VBox(8, packageHeader, new SearchBox(packageSearchField,"包名",false), packageTree,indexSummaryLabel);
        packages.setMinWidth(190); packages.setPrefWidth(240); packages.setMaxWidth(280); packages.getStyleClass().add("scope-package-browser"); VBox.setVgrow(packageTree, Priority.ALWAYS);
        onlySelected.setId("scope-only-selected"); onlySelected.setOnAction(event -> scheduleQuery());
        includePage.setOnAction(e -> selectPage(true));includePage.getStyleClass().add("ghost-btn"); includePage.setId("scope-include-page");
        excludePage.setOnAction(e -> selectPage(false));excludePage.getStyleClass().add("ghost-btn"); excludePage.setId("scope-exclude-page");
        SearchBox classSearch = new SearchBox(classSearchField,"类名",true);
        HBox filters = new HBox(8, classSearch, onlySelected); filters.setAlignment(Pos.CENTER_LEFT); HBox.setHgrow(classSearch, Priority.ALWAYS);
        HBox actions = new HBox(8, includePage, excludePage, spacer(), previous, pageLabel, next); actions.setAlignment(Pos.CENTER_LEFT);
        locationLabel.getStyleClass().add("scope-location");locationLabel.setId("scope-location");locationLabel.setMaxWidth(Double.MAX_VALUE);locationLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        packageSummaryLabel.getStyleClass().add("scope-package-summary");packageSummaryLabel.setId("scope-package-summary");packageSummaryLabel.setWrapText(true);
        includePackage.setId("scope-include-package");excludePackage.setId("scope-exclude-package");inheritPackage.setId("scope-inherit-package");
        includePackage.setTooltip(new Tooltip("纳入当前包及子包；明确的子包排除会被恢复。"));
        excludePackage.setTooltip(new Tooltip("排除当前包及子包，包括之后加载的新类；父包仍保持纳入。"));
        inheritPackage.setTooltip(new Tooltip("移除当前包自己的纳入/排除规则；保留子包规则和独立类选择。"));
        includePackage.setOnAction(e->editPackage("include"));excludePackage.setOnAction(e->editPackage("exclude"));inheritPackage.setOnAction(e->editPackage("inherit"));
        packageStateLabel.setId("scope-package-state");packageStateLabel.setWrapText(true);
        FlowPane packageActions=new FlowPane(8,6,includePackage,excludePackage,inheritPackage);updatePackageActions();
        globalScopeLabel.getStyleClass().add("scope-global-summary");globalScopeLabel.setId("scope-global-summary");globalScopeLabel.setWrapText(true);
        classSummaryLabel.setId("scope-filter-summary");classSummaryLabel.setWrapText(true);
        buildClassTable();
        VBox packageHeading=new VBox(8,locationLabel,packageActions);
        FlowPane packageContext=new FlowPane(12,4,packageSummaryLabel,packageStateLabel);
        VBox classes = new VBox(9,new VBox(5,packageHeading,packageContext), filters, classTable, actions, classSummaryLabel); classes.getStyleClass().add("scope-class-browser");
        VBox.setVgrow(classTable, Priority.ALWAYS);
        classes.setMinWidth(0);
        SplitPane browsers = new SplitPane(packages, classes); browsers.setDividerPositions(.23); SplitPane.setResizableWithParent(packages,false); VBox.setVgrow(browsers, Priority.ALWAYS); browsers.setMinHeight(180);
        notice.setWrapText(true); notice.getStyleClass().add("scope-notice"); notice.setId("scope-notice");
        notice.setMinWidth(0);notice.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(notice,Priority.ALWAYS);
        HBox feedback=new HBox(12,notice,undoClear);feedback.setAlignment(Pos.CENTER_LEFT);
        buildRulesPane();
        browsers.visibleProperty().bind(rulesPane.expandedProperty().not());browsers.managedProperty().bind(browsers.visibleProperty());
        rulesPane.visibleProperty().bind(rulesPane.expandedProperty());rulesPane.managedProperty().bind(rulesPane.visibleProperty());
        VBox.setVgrow(rulesPane, Priority.ALWAYS);
        ruleSummary.getStyleClass().setAll("scope-count-summary");
        FlowPane appliedRules=new FlowPane(16,4,ruleSummary,includedRuleLabel,excludedPackageLabel);
        getChildren().addAll(heading,appliedRules, browsers, feedback, rulesPane);
        previous.setId("scope-previous-page"); next.setId("scope-next-page");
        previous.setOnAction(e -> { if (page > 0) { page--; renderPage(); } });
        next.setOnAction(e -> { if ((page + 1) * PAGE_SIZE < filtered.size()) { page++; renderPage(); } });
        queryDelay.setOnFinished(e -> query());
        packageSearchField.textProperty().addListener((obs, a, b) -> scheduleQuery());
        classSearchField.textProperty().addListener((obs, a, b) -> scheduleQuery());
        updateRuleSummary(); loadClasses(false);
    }
    private TreeCell<String> packageCell() {
        return new TreeCell<>() {
            private final CheckBox check = new CheckBox(); private final Label name = new Label();
            private final HBox content = new HBox(7, check, name);
            { content.setAlignment(Pos.CENTER_LEFT); check.setOnAction(e -> {
                String path = getItem(); if (path == null || rendering || rulesDirty) return;
                ScopeSelection.Change result = selection.selectPackage(path, check.isSelected());
                notice.setText(result == ScopeSelection.Change.EXCLUDED_BY_RULE
                        ? "这个包仍被上级排除规则覆盖。请在下方规则中调整排除条件。" : "范围已更新。包规则也适用于之后加载的新类。");
                changed();
            }); }
            @Override protected void updateItem(String path, boolean empty) {
                super.updateItem(path, empty); setText(null);
                if (empty || path == null) { setGraphic(null); return; }
                String parent = getTreeItem().getParent() == null ? "" : getTreeItem().getParent().getValue();
                name.setText(parent.isBlank() || !path.startsWith(parent + ".") ? path : path.substring(parent.length() + 1));
                check.setDisable(rulesDirty);
                check.setSelected(selection.packageIncluded(path)); check.setIndeterminate(selection.packagePartial(path));
                check.setAccessibleText("采集包 " + path + " 及子包");
                setTooltip(new Tooltip(path + "\n点击文字浏览；复选框编辑采集规则。")); setGraphic(content);
            }
        };
    }
    private void buildClassTable() {
        classTable.setEditable(true); classTable.setId("scope-class-table"); classTable.getStyleClass().add("class-table");
        classTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<ClassRow, Boolean> included = new TableColumn<>("采集");
        included.editableProperty().bind(rulesPane.textProperty().isEqualTo("排除规则 · 排除优先于纳入"));
        included.setCellValueFactory(c -> c.getValue().included); included.setCellFactory(CheckBoxTableCell.forTableColumn(included)); included.setMinWidth(52);included.setMaxWidth(52); included.setPrefWidth(52);
        TableColumn<ClassRow, String> name = new TableColumn<>("类"); name.setCellValueFactory(c -> new SimpleStringProperty(simple(c.getValue().name))); name.setMinWidth(150);name.setPrefWidth(200);
        name.setCellFactory(c -> new TableCell<>() { @Override protected void updateItem(String text, boolean empty) {
            super.updateItem(text, empty); setText(empty ? null : text);
            setTooltip(empty || getTableRow().getItem() == null ? null : new Tooltip(getTableRow().getItem().name));
        }});
        TableColumn<ClassRow, String> state = new TableColumn<>("生效原因"); state.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().reason)); state.setMinWidth(120);state.setPrefWidth(160);
        TableColumn<ClassRow, Number> methods = new TableColumn<>("方法"); methods.setCellValueFactory(c -> new SimpleIntegerProperty(methodIndex.getOrDefault(c.getValue().name, List.of()).size())); methods.setMinWidth(56);methods.setPrefWidth(60);methods.setMaxWidth(66);
        classTable.getColumns().addAll(included, name, state, methods);
        emptyClassLabel.setWrapText(true);classTable.setPlaceholder(emptyClassLabel);
    }
    private void buildRulesPane() {
        classRules.setPromptText("每行一个，例如 cls:*DTO 或 pkg:com.example.internal"); classRules.setPrefRowCount(3); classRules.setId("scope-class-rules");
        methodRules.setPromptText("每行一个，例如 get* 或 com.example.Service.find*"); methodRules.setPrefRowCount(2); methodRules.setId("scope-method-rules");
        classRules.textProperty().addListener((obs,a,b) -> ruleDraftChanged());
        methodRules.textProperty().addListener((obs,a,b) -> ruleDraftChanged());
        Button restore = button("还原未应用修改", () -> { rulesDirty = false; setQueryPending(queryPending); updateRuleSummary(); packageTree.refresh(); renderPage(); if (onConfigChanged != null) onConfigChanged.accept(null); });
        Button apply = button("应用排除规则", () -> {
            try {
                List<String> excluded = classRules.getText().lines().map(ClassSelectionRules::normalizeClassExcludeCondition).filter(s -> !s.isBlank()).distinct().toList();
                List<String> methods = methodRules.getText().lines().map(String::trim).filter(s -> !s.isBlank() && !s.startsWith("#")).distinct().toList();
                selection.replaceRules(excluded, methods); rulesDirty = false; notice.setText("排除规则已应用。命中排除规则的类不会被采集。"); changed();
            } catch (IllegalArgumentException error) { notice.setText(error.getMessage()); }
        }); apply.setId("scope-apply-rules"); apply.getStyleClass().add("primary-btn");
        Button defaults = button("填入常用规则", () -> { classRules.setText(String.join("\n", MonitoringConfig.Scope.DEFAULT_EXCLUDE_CONDITIONS)); methodRules.setText(String.join("\n", MonitoringConfig.Scope.DEFAULT_EXCLUDE_METHODS)); });
        VBox content = new VBox(8, label("排除类或包", "section-header"), classRules, label("排除方法", "section-header"), methodRules, new FlowPane(8,8,apply,restore,defaults));
        content.setPadding(new Insets(10)); rulesPane.setText("排除规则 · 排除优先于纳入"); rulesPane.setContent(content); rulesPane.setAnimated(false);rulesPane.setExpanded(false);
    }
    private void ruleDraftChanged() {
        if (syncingRules || disposed) return;
        invalidateClearUndo();
        rulesDirty = true;setQueryPending(queryPending);
        packageTree.refresh(); rulesPane.setText("排除规则 · 有未应用修改");
        notice.setText("规则编辑尚未应用。当前 YAML 保持已应用规则，请先应用或还原修改后开始采集。");
        if (onConfigChanged != null) onConfigChanged.accept(null);
    }
    public boolean hasPendingRuleEdits() { return rulesDirty; }
    private void loadClasses(boolean force) {
        if (disposed || loadingClasses) return;
        long generation = ++loadRevision; setLoading(true); classSummaryLabel.setText(force ? "正在重新扫描…已有规则将保留" : "正在读取目标 JVM 的类索引…");
        worker.submit(() -> {
            try {
                List<String> classes = force ? List.of() : cache.readClasses();
                Map<String, List<String>> methods = force ? Map.of() : cache.readMethods();
                if (classes.isEmpty()) { classes = JvmService.listLoadedClasses(pid); cache.writeClasses(classes); }
                if (methods.isEmpty()) { methods = JvmService.listClassMethods(pid); cache.writeMethods(methods); }
                List<String> names = classes.stream().distinct().sorted().toList(); Map<String, List<String>> index = Map.copyOf(methods);
                ScopeClassIndex prepared=new ScopeClassIndex(names,index); List<String> packages=prepared.packages();
                Platform.runLater(() -> {
                    if (disposed || generation != loadRevision) return;
                    classNames = names; methodIndex = index; packageNames = packages; scopeIndex=prepared; indexRevision++;
                    indexSummaryLabel.setText("目标 JVM 索引："+count(names.size())+" 类");
                    setLoading(false); query();
                });
            } catch (Exception error) {
                AppLog.error("类索引加载失败", error);
                Platform.runLater(() -> { if (!disposed && generation == loadRevision) { setLoading(false); notice.setText("类索引读取失败，可点击刷新重试。已有规则仍保留。"); } });
            }
        });
    }
    public void reloadClasses() { loadClasses(true); }
    private void setLoading(boolean value) { loadingClasses = value; if (onLoadingChanged != null) onLoadingChanged.accept(value); }
    public boolean isLoadingClasses() { return loadingClasses; }
    private void scheduleQuery() { browseRevision++; classTable.getItems().clear();scheduleRuleQuery(); }
    private void scheduleRuleQuery() {
        if (disposed) return;
        setQueryPending(true);
        queryRevision++; if(queryTask!=null)queryTask.cancel(true);
        locationLabel.setText(browsingPackage.isEmpty() ? "当前浏览：全部已发现的类" : "当前包："+browsingPackage+"（含子包）");
        locationLabel.setTooltip(new Tooltip(locationLabel.getText()));
        packageSummaryLabel.setText("当前范围统计：计算中…");
        classSummaryLabel.setText("正在筛选当前列表…");queryDelay.playFromStart();
    }
    private void query() {
        if (disposed) return;
        setQueryPending(true);
        if(queryTask!=null)queryTask.cancel(true);
        long generation = ++queryRevision, scopeRevision = revision;
        String packageNeedle = packageSearchField.getText().trim().toLowerCase(Locale.ROOT), needle = classSearchField.getText().trim();
        long indexVersion = indexRevision, browsingVersion = browseRevision;
        boolean needsTree = !packageNeedle.equals(renderedPackageNeedle) || indexVersion != renderedIndex;
        String browsed = browsingPackage; boolean selectedOnly = onlySelected.isSelected();
        var rules=selection.scope();var index=scopeIndex;List<String> packages=packageNames;
        queryTask=queryWorker.submit(() -> {
            try {
                var chosen=index.select(rules,() -> !Thread.currentThread().isInterrupted());
                var result=index.query(chosen,browsed,needle,selectedOnly,() -> !Thread.currentThread().isInterrupted());
                List<String> tree = needsTree ? packages.stream().filter(n -> packageNeedle.isEmpty() || n.toLowerCase(Locale.ROOT).contains(packageNeedle)).toList() : List.of();
                List<PackageSegment> segments = needsTree ? packageSegments(tree) : List.of();
                Platform.runLater(() -> {
                    if (disposed || generation != queryRevision || scopeRevision != revision || indexVersion != indexRevision) return;
                    filtered = result.matches();setQueryPending(false);
                    if (browsingVersion != renderedBrowseRevision) { page = 0; renderedBrowseRevision = browsingVersion; }
                    renderPage();
                    if (needsTree) { renderedPackageNeedle = packageNeedle; renderedIndex = indexVersion; rebuildPackageTree(segments); }
                    globalScopeLabel.setText("全局待采集："+count(chosen.classCount())+" 类 · 规则匹配数，非实际增强数");
                    packageSummaryLabel.setText((browsed.isEmpty() ? "全部索引：" : "本包范围：")+count(result.packageClassCount())+" 类 · 其中纳入采集 "+count(result.packageSelectedCount())+" 类");
                    classSummaryLabel.setText("当前列表："+count(result.matches().size())+" 类 · 其中纳入采集 "+count(result.matchedSelectedCount())+" 类"
                            +(needle.isEmpty() ? "" : " · 搜索："+needle));
                });
            } catch(CancellationException ignored) { /* A newer query owns publication. */ }
        });
    }
    private void setQueryPending(boolean value) {
        queryPending=value;classTable.setDisable(value);
        includePage.setDisable(value || rulesDirty);excludePage.setDisable(value || rulesDirty);
        if(value){previous.setDisable(true);next.setDisable(true);}
        emptyClassLabel.setText(value ? "正在更新当前包和列表…" : "没有匹配的类\n可浏览全部、清除搜索，或关闭“只看已纳入”。");
    }
    private static String count(long value){return String.format(Locale.ROOT,"%,d",value);}
    private record PackageSegment(String path, String parent) { }
    private static List<PackageSegment> packageSegments(List<String> packages) {
        Map<String,PackageSegment> segments = new LinkedHashMap<>();
        for (String pkg : packages) {
            String current = "";
            for (String part : pkg.split("\\.")) {
                String parent = current; current = current.isEmpty() ? part : current + "." + part;
                segments.putIfAbsent(current,new PackageSegment(current,parent));
            }
        }
        return List.copyOf(segments.values());
    }
    private void rebuildPackageTree(List<PackageSegment> segments) {
        if (treeBuild != null) treeBuild.stop();
        long generation = ++treeRevision;
        Set<String> expanded = new HashSet<>(); collectExpanded(packageTree.getRoot(), expanded);
        TreeItem<String> root = new TreeItem<>(""); root.setExpanded(true);
        Map<String, TreeItem<String>> nodes = new LinkedHashMap<>(); nodes.put("", root);
        int[] offset = {0};
        Runnable append = () -> {
            if (disposed || generation != treeRevision) { if (treeBuild != null) treeBuild.stop(); return; }
            int end = Math.min(segments.size(), offset[0] + 256);
            for (; offset[0] < end; offset[0]++) {
                PackageSegment segment = segments.get(offset[0]); TreeItem<String> item = new TreeItem<>(segment.path());
                nodes.put(segment.path(),item); nodes.get(segment.parent()).getChildren().add(item);
                item.setExpanded(expanded.contains(segment.path()) || !packageSearchField.getText().isBlank() || segment.path().split("\\.").length <= 2);
            }
            if (offset[0] == segments.size()) {
                if (treeBuild != null) treeBuild.stop(); rendering = true; packageTree.setRoot(root);
                if (nodes.containsKey(browsingPackage)) packageTree.getSelectionModel().select(nodes.get(browsingPackage));
                rendering = false;
            }
        };
        if (segments.size() <= 256) append.run();
        else {
            treeBuild = new javafx.animation.Timeline(new javafx.animation.KeyFrame(Duration.millis(16), event -> append.run()));
            treeBuild.setCycleCount(javafx.animation.Timeline.INDEFINITE); treeBuild.play();
        }
    }
    private void collectExpanded(TreeItem<String> root, Set<String> expanded) {
        if (root == null) return;
        ArrayDeque<TreeItem<String>> pending = new ArrayDeque<>(); pending.add(root);
        while (!pending.isEmpty()) { var node = pending.removeFirst(); if (node.isExpanded()) expanded.add(node.getValue()); pending.addAll(node.getChildren()); }
    }
    private void renderPage() {
        rendering = true;
        page = Math.max(0, Math.min(page, Math.max(1,(filtered.size()+PAGE_SIZE-1)/PAGE_SIZE)-1));
        int start = Math.min(page * PAGE_SIZE, filtered.size()), end = Math.min(start + PAGE_SIZE, filtered.size());
        List<ClassRow> rows = new ArrayList<>();
        Set<String> exactClasses=new HashSet<>(selection.scope().includeClasses());
        for (String name : filtered.subList(start, end)) {
            String excluded = selection.exclusion(name);
            String reason = !excluded.isEmpty() ? "排除 · " + excluded : selection.selected(name)
                    ? (exactClasses.contains(name) ? "独立纳入" : "随包纳入") : "未纳入";
            ClassRow row = new ClassRow(name, selection.selected(name), reason);
            row.included.addListener((obs, before, after) -> {
                if (rendering) return;
                if(queryPending){renderPage();return;}
                if (rulesDirty) { renderPage(); notice.setText("请先应用或还原规则草稿，再编辑采集勾选。"); return; }
                ScopeSelection.Change result = selection.selectClass(row.name, after);
                notice.setText(result == ScopeSelection.Change.EXCLUDED_BY_RULE
                        ? "该类命中排除规则：" + selection.exclusion(row.name) + "。请先调整下方排除规则。"
                        : "范围已更新。独立选择的类不会因浏览其他包而丢失。");
                changed();
            }); rows.add(row);
        }
        classTable.getItems().setAll(rows); pageLabel.setText(filtered.isEmpty() ? "0 / 0" : (page + 1) + " / " + ((filtered.size() + PAGE_SIZE - 1) / PAGE_SIZE));
        previous.setDisable(queryPending || page == 0); next.setDisable(queryPending || end >= filtered.size()); rendering = false;
    }
    private void selectPage(boolean include) {
        if(queryPending){notice.setText("当前列表正在更新，请稍后选择本页。");return;}
        if (rulesDirty) { notice.setText("请先应用或还原规则草稿，再编辑本页采集范围。"); return; }
        boolean blocked = false;
        for (ClassRow row : List.copyOf(classTable.getItems())) blocked |= selection.selectClass(row.name, include) == ScopeSelection.Change.EXCLUDED_BY_RULE;
        notice.setText(blocked ? "本页部分类型命中排除规则，保持排除。可在下方规则中调整。" : "当前页的采集范围已更新；其他页不受影响。"); changed();
    }
    private void changed() {
        invalidateClearUndo();
        globalScopeLabel.setText("全局待采集：正在计算…");
        revision++; updateRuleSummary(); packageTree.refresh(); renderPage(); scheduleRuleQuery();
        if (onConfigChanged != null) onConfigChanged.accept(null);
    }
    private void clearScope() {
        if (rulesDirty) { notice.setText("请先应用或还原规则草稿。"); return; }
        var before=selection.scope();
        if(before.includePackages().isEmpty()&&before.includeClasses().isEmpty())return;
        selection.clearIncludes();changed();clearedScope=before;undoClear.setVisible(true);
        notice.setText("采集范围已清空。可撤销；继续编辑范围后将保留新的选择。");
    }
    private void invalidateClearUndo(){clearedScope=null;undoClear.setVisible(false);}
    private void editPackage(String action) {
        if(browsingPackage.isBlank()||rulesDirty)return;
        if(action.equals("exclude")){selection.excludePackage(browsingPackage);notice.setText("已排除 "+browsingPackage+" 及子包，其他纳入范围保持不变。");}
        else if(action.equals("inherit")){selection.inheritPackage(browsingPackage);notice.setText("已移除当前包的单独规则，跟随父包；子包规则和独立类保持不变。");}
        else {var result=selection.selectPackage(browsingPackage,true);notice.setText(result==ScopeSelection.Change.EXCLUDED_BY_RULE?"该包仍受上级排除规则限制，请先恢复上级包。":"已纳入 "+browsingPackage+" 及子包。");}
        changed();
    }
    private void updatePackageActions() {
        boolean blocked=browsingPackage.isBlank()||rulesDirty;
        for(Button action:List.of(includePackage,excludePackage,inheritPackage))action.setDisable(blocked);
        for(Button action:List.of(includePackage,excludePackage,inheritPackage)){action.setVisible(!browsingPackage.isBlank());action.setManaged(!browsingPackage.isBlank());}
        if(browsingPackage.isBlank()){packageStateLabel.setText("选择左侧包目录可设置整包规则");return;}
        String excluded=selection.packageExclusion(browsingPackage);
        packageStateLabel.setText(!excluded.isEmpty()?"当前包已排除 · "+excluded:selection.packageIncluded(browsingPackage)?(selection.packagePartial(browsingPackage)?"当前包已纳入 · 部分子包被排除":"当前包已纳入（含子包）"):"当前包未整包纳入 · 可单独选择类");
    }
    private void updateRuleSummary() {
        var scope = selection.scope();
        updatePackageActions();
        includedRuleLabel.setText(scope.includePackages().isEmpty()?"纳入包：无（可勾选包，或单独纳入类）":"纳入包："+String.join("、",scope.includePackages()));
        var excludedPackages=scope.excludeConditions().stream().filter(v->v.startsWith("pkg:")).map(v->v.substring(4)).toList();
        excludedPackageLabel.setText(excludedPackages.isEmpty()?"排除子包：无":"排除子包："+String.join("、",excludedPackages));
        excludedPackageLabel.setVisible(!excludedPackages.isEmpty());excludedPackageLabel.setManaged(!excludedPackages.isEmpty());
        ruleSummary.setText(scope.includePackages().isEmpty() && scope.includeClasses().isEmpty() ? "尚未选择采集范围"
                : scope.includePackages().size() + " 条包规则  +  " + scope.includeClasses().size() + " 个独立类  ·  " + scope.excludeConditions().size() + " 条排除规则");
        ruleSummary.setTooltip(new Tooltip("纳入包：" + String.join(", ", scope.includePackages()) + "\n独立类：" + String.join(", ", scope.includeClasses())));
        if (!rulesDirty) {
            syncingRules = true;
            classRules.setText(String.join("\n", scope.excludeConditions())); methodRules.setText(String.join("\n", scope.excludeMethods()));
            syncingRules = false; rulesPane.setText("排除规则 · 排除优先于纳入");
        }
    }
    public List<String> getIncludedPackages() { return selection.scope().includePackages(); }
    public List<String> getIncludedClasses() { return selection.scope().includeClasses(); }
    public List<String> getExcludedClassConditionList() { return selection.scope().excludeConditions(); }
    public List<String> getExcludedMethods() { return selection.scope().excludeMethods(); }
    public List<String> getExcludedClasses() { ScopeSelection snapshot = selection.copy(); return classNames.stream().filter(n -> !snapshot.exclusion(n).isEmpty()).toList(); }
    public String getPackagePrefixes() { return String.join("|", getIncludedPackages()); }
    public String getExcludedClassConditions() { return String.join("|", getExcludedClassConditionList()); }
    public void applyConfiguration(List<String> p, List<String> c, List<String> e, List<String> m) {
        invalidateClearUndo();
        if (disposed) return; rulesDirty = false; selection = new ScopeSelection(new MonitoringConfig.Scope(p, c, e, m)); revision++; globalScopeLabel.setText("全局待采集：正在计算…");updateRuleSummary(); query();
    }
    public void applyConfigurationAsync(List<String> p, List<String> c, List<String> e, List<String> m, Runnable complete) {
        applyConfiguration(p, c, e, m); long expected = revision;
        if (complete != null) Platform.runLater(() -> { if (!disposed && expected == revision) complete.run(); });
    }
    public SelectionMetrics getSelectionMetrics() { return metrics(scopeIndex,selection.scope()); }
    public void getSelectionMetricsAsync(Consumer<SelectionMetrics> callback) {
        if (disposed) return;
        var rules=selection.scope();var index=scopeIndex;long version=revision,indexVersion=indexRevision;
        worker.submit(() -> { SelectionMetrics result=metrics(index,rules);Platform.runLater(() -> {
            if(!disposed && version==revision && indexVersion==indexRevision && callback!=null)callback.accept(result);
        }); });
    }
    public SelectionMetrics metricsForConfiguration(List<String> p,List<String> c,List<String> e,List<String> m) {
        return metrics(scopeIndex,new MonitoringConfig.Scope(p,c,e,m));
    }
    private static SelectionMetrics metrics(ScopeClassIndex index,MonitoringConfig.Scope rules) {
        var selected=index.select(rules);return new SelectionMetrics(selected.classCount(),selected.methodCount());
    }
    public record SelectionMetrics(long enhancedClasses, long enhancedMethods) { }
    public static final class ClassRow {
        private final String name, reason; private final BooleanProperty included;
        ClassRow(String name, boolean selected, String reason) { this.name = name; this.reason = reason; included = new SimpleBooleanProperty(selected); }
        public String getClassName() { return name; } public boolean isIncluded() { return included.get(); } public BooleanProperty includedProperty() { return included; }
    }
    private static String simple(String name) { int dot = name.lastIndexOf('.'); return dot < 0 ? name : name.substring(dot + 1); }
    private static Label label(String text, String css) { Label label = new Label(text); label.getStyleClass().add(css); return label; }
    private static Region spacer() { Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); return spacer; }
    private static Button button(String text, Runnable action) { Button button = new Button(text); button.getStyleClass().add("ghost-btn"); button.setOnAction(e -> action.run()); return button; }
    public void setOnConfigChanged(Consumer<Void> callback) { onConfigChanged = callback; }
    public void setOnLoadingChanged(Consumer<Boolean> callback) { onLoadingChanged = callback; }
    public void setOnProgressChanged(Consumer<String> callback) { onProgressChanged = callback; }
    public void setOnActualEnhancementResultChanged(Consumer<ActualEnhancementMetrics> callback) { onActualEnhancementResultChanged = callback; }
    public void dispose() { disposed = true; actualEnhancementLoadGeneration++; queryRevision++; loadRevision++; queryDelay.stop(); if (treeBuild != null) treeBuild.stop(); worker.shutdownNow(); queryWorker.shutdownNow(); }
    /** Loads the Agent's completed attach-time result without blocking the JavaFX event thread. */
    public void loadActualEnhancementResult(Path resultFile) {
        long generation = ++actualEnhancementLoadGeneration;
        Thread reader = new Thread(() -> {
            ActualEnhancementResult result = null;
            Exception lastError = null;
            for (int attempt = 0; attempt < ACTUAL_RESULT_READ_ATTEMPTS; attempt++) {
                try {
                    result = readActualEnhancementResult(resultFile);
                    break;
                } catch (Exception error) {
                    lastError = error;
                    if (attempt + 1 < ACTUAL_RESULT_READ_ATTEMPTS && !waitForActualResultRetry()) return;
                }
            }
            if (result == null) {
                AppLog.warn("[ClassSelectionPane] read enhancement result failed: "
                        + (lastError == null ? "unknown error" : lastError.getMessage()));
                return;
            }
            ActualEnhancementResult loadedResult = result;
            Platform.runLater(() -> {
                if (disposed || generation != actualEnhancementLoadGeneration) return;
                actualEnhancementResult = loadedResult;
                if (onActualEnhancementResultChanged != null) {
                    onActualEnhancementResultChanged.accept(new ActualEnhancementMetrics(
                            loadedResult.transformedClasses, loadedResult.failedClasses, loadedResult.transformedMethods));
                }
            });
        }, "read-enhancement-result");
        reader.setDaemon(true);
        reader.start();
    }

    /** Clears the prior session result before a new attach begins. */
    public void clearActualEnhancementResult() {
        actualEnhancementLoadGeneration++;
        actualEnhancementResult = null;
    }

    private static boolean waitForActualResultRetry() {
        try {
            Thread.sleep(ACTUAL_RESULT_READ_RETRY_MILLIS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static ActualEnhancementResult readActualEnhancementResult(Path resultFile) throws Exception {
        JsonNode root = JSON.readTree(Files.readString(resultFile));
        if (root == null || root.path("schemaVersion").asInt() != 1
                || !"completed".equals(root.path("status").asText())) {
            throw new IllegalArgumentException("结果文件尚未完成");
        }
        List<ActualEnhancementRow> rows = new ArrayList<>();
        for (JsonNode node : root.path("classes")) {
            List<String> names = new ArrayList<>();
            for (JsonNode method : node.path("methodNames")) names.add(method.asText());
            rows.add(new ActualEnhancementRow(node.path("className").asText(), node.path("category").asText(),
                    node.path("status").asText(), node.path("trigger").asText(), node.path("methodCount").asInt(),
                    names, node.path("reason").asText()));
        }
        long transformed = rows.stream().filter(row -> "transformed".equals(row.status)).count();
        long failed = rows.stream().filter(row -> "failed".equals(row.status)).count();
        long methods = rows.stream().filter(row -> "transformed".equals(row.status))
                .mapToLong(row -> row.methodCount).sum();
        return new ActualEnhancementResult(transformed, failed, methods, rows);
    }

    public void showActualEnhancementResult() {
        if (actualEnhancementResult == null) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("实际增强结果");
            alert.setHeaderText("本次 attach 的实际明细尚未就绪");
            alert.setContentText("当前还没有可读取的 enhancement-result.json。\n\n"
                    + "请先应用配置；Agent 完成增强后，这里会自动显示实际增强的类、方法和失败原因。");
            alert.show();
            return;
        }
        ObservableList<ActualEnhancementRow> rows = FXCollections.observableArrayList(actualEnhancementResult.rows);
        TableView<ActualEnhancementRow> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<ActualEnhancementRow, String> className = new TableColumn<>("类");
        className.setCellValueFactory(value -> new SimpleStringProperty(value.getValue().className));
        className.setPrefWidth(390);
        TableColumn<ActualEnhancementRow, String> category = new TableColumn<>("类别");
        category.setCellValueFactory(value -> new SimpleStringProperty(value.getValue().category));
        TableColumn<ActualEnhancementRow, String> status = new TableColumn<>("状态");
        status.setCellValueFactory(value -> new SimpleStringProperty(value.getValue().status));
        TableColumn<ActualEnhancementRow, String> methods = new TableColumn<>("实际方法");
        methods.setCellValueFactory(value -> new SimpleStringProperty(String.join(", ", value.getValue().methodNames)));
        methods.setPrefWidth(360);
        table.getColumns().addAll(className, category, status, methods);

        Label summary = new Label("实际 attach 结果：" + actualEnhancementResult.transformedClasses + " 个类，"
                + actualEnhancementResult.transformedMethods + " 个方法，失败 " + actualEnhancementResult.failedClasses + " 个类");
        summary.getStyleClass().add("hint-label");
        VBox content = new VBox(10, summary, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("实际增强结果");
        dialog.setHeaderText("以 Agent attach 完成时写入的结果为准");
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(1080, 680);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.show();
    }

    public record ActualEnhancementMetrics(long transformedClasses, long failedClasses,
                                           long transformedMethods) {}

    private record ActualEnhancementResult(long transformedClasses, long failedClasses,
                                           long transformedMethods, List<ActualEnhancementRow> rows) {}

    private record ActualEnhancementRow(String className, String category, String status, String trigger,
                                        int methodCount, List<String> methodNames, String reason) {}

}
