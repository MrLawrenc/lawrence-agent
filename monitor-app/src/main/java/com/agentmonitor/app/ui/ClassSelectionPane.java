package com.agentmonitor.app.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.agentmonitor.app.service.JvmService;
import com.agentmonitor.app.service.JvmClassIndexCache;
import com.agentmonitor.app.util.AppLog;
import com.agentmonitor.model.config.PackagePatternMatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.CheckBoxTreeCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class ClassSelectionPane extends SplitPane {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int ACTUAL_RESULT_READ_ATTEMPTS = 15;
    private static final long ACTUAL_RESULT_READ_RETRY_MILLIS = 200;

    private final TreeView<String> packageTree;
    private final CheckBoxTreeItem<String> rootItem = new CheckBoxTreeItem<>("全部包");
    private final ObservableList<ClassRow> allRows = FXCollections.observableArrayList();
    private final ObservableList<ClassRow> visibleRows = FXCollections.observableArrayList();
    private final List<ClassRow> filteredRows = new ArrayList<>();
    private final Map<String, List<ClassRow>> packageToRows = new LinkedHashMap<>();
    private final Map<String, List<ClassRow>> packageToDescendantRows = new LinkedHashMap<>();
    private final List<String> allPackages = new ArrayList<>();
    private final Set<String> selectedPackageSet = new LinkedHashSet<>();
    private final LinkedHashSet<ClassRow> selectedRows = new LinkedHashSet<>();
    private final Set<String> searchPathSet = new LinkedHashSet<>();
    private final Set<String> searchHitSet = new LinkedHashSet<>();
    private final Set<String> extraClassExcludeConditions = new LinkedHashSet<>();
    /** Exact exclusions are maintained incrementally so one checkbox change never rescans all classes. */
    private final Set<String> explicitExcludedClassNames = new LinkedHashSet<>();
    private final Set<String> excludedMethodPatterns = new LinkedHashSet<>();
    // Kept separately from the loaded rows because configuration can arrive before the
    // asynchronous JVM class scan completes.  Reapplying it after loading makes the UI
    // reflect exactly the scope that will be passed to the Agent.
    private List<String> appliedIncludePackages = List.of();
    private List<String> appliedIncludeClasses = List.of();
    private List<String> appliedClassExcludeConditions = List.of();
    private List<String> appliedMethodExcludePatterns = ClassSelectionRules.defaultExcludedMethodPatterns();
    private boolean hasAppliedConfiguration;
    private final List<Node> loadingLockedControls = new ArrayList<>();
    private final TableView<ClassRow> classTable = new TableView<>(visibleRows);
    private final TextField classSearchField = new TextField();
    private final TextField packageSearchField = new TextField();
    private final Label classSummaryLabel = new Label("正在读取目标 JVM 已加载类...");
    private final Label pageLabel = new Label();
    private final Button previousPageButton = new Button("‹ 上一页");
    private final Button nextPageButton = new Button("下一页 ›");
    private final Label packageSummaryLabel = new Label("包选择（共 0 个包）");
    private final String pid;
    private final JvmClassIndexCache classIndexCache;
    private Consumer<Void> onConfigChanged;
    private Consumer<Boolean> onLoadingChanged;
    private Consumer<String> onProgressChanged;
    private Consumer<ActualEnhancementMetrics> onActualEnhancementResultChanged;
    private boolean bulkUpdating = false;
    private boolean treeUpdating = false;
    private boolean loadingClasses = false;
    private static final int CLASS_PAGE_SIZE = 200;
    private int currentPage = 0;
    private long includedSelectedClassCount;
    /** Strong reference while a large YAML scope is reconciled over multiple JavaFX pulses. */
    private Timeline configurationApplyTimeline;
    /** Strong reference while a large package-tree model is committed over multiple JavaFX pulses. */
    private Timeline packageTreeBuildTimeline;
    private ActualEnhancementResult actualEnhancementResult;
    /** Prevents a late reader from publishing a prior monitoring session's result. */
    private long actualEnhancementLoadGeneration;

    public ClassSelectionPane(String pid) {
        this.pid = pid;
        this.classIndexCache = new JvmClassIndexCache(pid);
        getStyleClass().add("class-selection-pane");
        excludedMethodPatterns.addAll(ClassSelectionRules.defaultExcludedMethodPatterns());

        rootItem.setExpanded(false);
        rootItem.setSelected(false);
        packageTree = new TreeView<>(rootItem);
        packageTree.setShowRoot(false);
        packageTree.setCellFactory(tree -> new CheckBoxTreeCell<>() {
            @Override
            public void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("package-tree-search-path", "package-tree-search-hit");
                if (empty || item == null) return;
                String needle = searchNeedle();
                if (!needle.isEmpty()) {
                    String path = treeItemPath(getTreeItem());
                    if (searchPathSet.contains(path)) {
                        getStyleClass().add("package-tree-search-path");
                    }
                    if (searchHitSet.contains(path)) {
                        getStyleClass().add("package-tree-search-hit");
                    }
                }
            }
        });
        packageTree.getStyleClass().add("package-tree");
        packageTree.setMinWidth(260);
        packageTree.setPrefWidth(300);

        VBox left = buildScopePanel();
        VBox right = new VBox(10, buildClassHeader(), classTable);
        right.getStyleClass().add("class-list-panel");
        VBox.setVgrow(classTable, Priority.ALWAYS);

        buildClassTable();
        getItems().addAll(left, right);
        setDividerPositions(0.30);
        loadClasses(false);
    }

    public void setOnConfigChanged(Consumer<Void> onConfigChanged) {
        this.onConfigChanged = onConfigChanged;
    }

    public void setOnLoadingChanged(Consumer<Boolean> onLoadingChanged) {
        this.onLoadingChanged = onLoadingChanged;
    }

    public void setOnProgressChanged(Consumer<String> onProgressChanged) {
        this.onProgressChanged = onProgressChanged;
    }

    public void setOnActualEnhancementResultChanged(
            Consumer<ActualEnhancementMetrics> onActualEnhancementResultChanged) {
        this.onActualEnhancementResultChanged = onActualEnhancementResultChanged;
    }

    private VBox buildScopePanel() {
        packageSearchField.setPromptText("搜索包名，命中节点会自动展开");
        packageSearchField.getStyleClass().add("search-field");
        packageSearchField.textProperty().addListener((obs, old, now) -> {
            if (!treeUpdating) buildPackageTree();
        });
        packageSummaryLabel.getStyleClass().add("tree-summary-label");

        VBox treeWrap = new VBox(6, packageSummaryLabel, packageTree);
        treeWrap.getStyleClass().add("tree-wrap");
        VBox.setVgrow(packageTree, Priority.ALWAYS);
        VBox.setVgrow(treeWrap, Priority.ALWAYS);

        VBox left = new VBox(10,
                section("搜索包"),
                packageSearchField,
                treeWrap);
        left.getStyleClass().add("package-panel");
        return left;
    }

    private Label section(String text) {
        return label(text, "section-header");
    }

    private Label label(String text, String styleClass) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        return label;
    }

    private String treeItemPath(javafx.scene.control.TreeItem<String> item) {
        if (item == null || item == rootItem) return "";
        List<String> parts = new ArrayList<>();
        javafx.scene.control.TreeItem<String> cursor = item;
        while (cursor != null && cursor != rootItem) {
            parts.add(0, cursor.getValue());
            cursor = cursor.getParent();
        }
        return String.join(".", parts);
    }

    private VBox buildClassHeader() {
        Label title = new Label("Matching Results");
        title.getStyleClass().add("card-title");
        Label subtitle = new Label("All classes selected by default. Unchecking means exclusion.");
        subtitle.getStyleClass().add("hint-label");
        classSearchField.setPromptText("搜索类名 / 包名");
        classSearchField.getStyleClass().add("search-field");
        classSearchField.textProperty().addListener((obs, old, now) -> refreshVisibleRows());

        Button selectAll = new Button("本页包含");
        selectAll.getStyleClass().add("ghost-btn");
        selectAll.setOnAction(e -> setVisibleRowsIncluded(true));

        Button invert = new Button("本页反选");
        invert.getStyleClass().add("ghost-btn");
        invert.setOnAction(e -> invertVisibleRowsIncluded());

        Button classExclude = new Button("排除类");
        classExclude.getStyleClass().add("ghost-btn");
        classExclude.setOnAction(e -> showClassExcludeDialog());

        Button methodExclude = new Button("排除方法");
        methodExclude.getStyleClass().add("ghost-btn");
        methodExclude.setOnAction(e -> showMethodExcludeDialog());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox tools = new HBox(8, classSearchField, spacer, classExclude, methodExclude, selectAll, invert);
        tools.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(classSearchField, Priority.ALWAYS);
        previousPageButton.getStyleClass().add("class-page-btn");
        nextPageButton.getStyleClass().add("class-page-btn");
        previousPageButton.setOnAction(e -> changePage(-1));
        nextPageButton.setOnAction(e -> changePage(1));
        pageLabel.getStyleClass().add("class-page-label");
        HBox pageControls = new HBox(6, previousPageButton, pageLabel, nextPageButton);
        pageControls.setAlignment(Pos.CENTER_RIGHT);
        loadingLockedControls.addAll(List.of(classSearchField, classExclude, methodExclude, selectAll, invert,
                previousPageButton, nextPageButton));

        classSummaryLabel.getStyleClass().add("hint-label");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox footer = new HBox(8, classSummaryLabel, footerSpacer, pageControls);
        footer.setAlignment(Pos.CENTER_LEFT);
        return new VBox(6, title, subtitle, tools, footer);
    }

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
                if (generation != actualEnhancementLoadGeneration) return;
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

    @SuppressWarnings("unchecked")
    private void buildClassTable() {
        classTable.getStyleClass().add("class-table");
        classTable.setEditable(true);
        classTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<ClassRow, Boolean> includedCol = new TableColumn<>("");
        includedCol.setCellValueFactory(cell -> cell.getValue().includedProperty());
        includedCol.setCellFactory(CheckBoxTableCell.forTableColumn(includedCol));
        includedCol.setEditable(true);
        includedCol.setPrefWidth(48);
        includedCol.setMaxWidth(56);

        TableColumn<ClassRow, String> nameCol = new TableColumn<>("类名");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("simpleName"));
        nameCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                getStyleClass().remove("class-name-cell");
                if (!empty) getStyleClass().add("class-name-cell");
            }
        });
        nameCol.setPrefWidth(210);

        TableColumn<ClassRow, String> pkgCol = new TableColumn<>("包名");
        pkgCol.setCellValueFactory(new PropertyValueFactory<>("packageName"));
        pkgCol.setPrefWidth(280);

        TableColumn<ClassRow, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeCol.setPrefWidth(110);

        TableColumn<ClassRow, Integer> methodCountCol = new TableColumn<>("方法数");
        methodCountCol.setCellValueFactory(new PropertyValueFactory<>("methodCount"));
        methodCountCol.setPrefWidth(78);

        classTable.getColumns().addAll(includedCol, nameCol, pkgCol, typeCol, methodCountCol);
    }

    private void loadClasses(boolean forceRefresh) {
        setLoadingClasses(true);
        classSummaryLabel.setText(forceRefresh ? "正在重新扫描目标 JVM..." : "正在读取目标 JVM 已加载类...");
        packageSummaryLabel.setText("包选择（扫描中...）");
        rootItem.getChildren().clear();
        allRows.clear();
        visibleRows.clear();
        filteredRows.clear();
        packageToRows.clear();
        packageToDescendantRows.clear();
        allPackages.clear();
        selectedPackageSet.clear();
        selectedRows.clear();
        new Thread(() -> {
            try {
                AppLog.info("[ClassSelectionPane] load classes started pid=" + pid + " forceRefresh=" + forceRefresh);
                List<String> classes = forceRefresh ? List.of() : classIndexCache.readClasses();
                Map<String, List<String>> methods = forceRefresh ? Map.of() : classIndexCache.readMethods();
                if (classes.isEmpty()) {
                    classes = JvmService.listLoadedClasses(pid);
                    classIndexCache.writeClasses(classes);
                }
                if (methods.isEmpty()) {
                    methods = JvmService.listClassMethods(pid);
                    classIndexCache.writeMethods(methods);
                }
                List<String> finalClasses = classes;
                Map<String, List<String>> finalMethods = methods;
                AppLog.info("[ClassSelectionPane] load classes finished pid=" + pid + " count=" + finalClasses.size());
                Platform.runLater(() -> applyClasses(finalClasses, finalMethods));
            } catch (Exception e) {
                AppLog.error("[ClassSelectionPane] load classes failed pid=" + pid, e);
                Platform.runLater(() -> {
                    classSummaryLabel.setText("读取 JVM 类失败，请查看日志: " + AppLog.file().toAbsolutePath());
                    packageSummaryLabel.setText("包选择（读取失败）");
                    setLoadingClasses(false);
                });
            }
        }, "load-jvm-classes").start();
    }

    /** Rebuilds the target JVM's class index; the controller displays a blocking progress overlay. */
    public void reloadClasses() {
        if (loadingClasses) return;
        loadClasses(true);
    }

    private void applyClasses(List<String> classes, Map<String, List<String>> methods) {
        List<ClassRow> rows = classes.stream()
                .distinct()
                .sorted()
                .map(className -> new ClassRow(className, methods.getOrDefault(className, List.of())))
                .collect(Collectors.toCollection(ArrayList::new));
        rows.forEach(row -> row.includedProperty().addListener((obs, old, now) -> {
            if (!bulkUpdating) {
                if (selectedRows.contains(row)) {
                    includedSelectedClassCount += now ? 1 : -1;
                    if (appliedIncludeClasses.contains(row.getClassName()) && !isRowUnderIncludedPackage(row)) {
                        if (!now) {
                            appliedIncludeClasses = appliedIncludeClasses.stream()
                                    .filter(className -> !className.equals(row.getClassName()))
                                    .toList();
                        }
                        explicitExcludedClassNames.remove(row.getClassName());
                    } else if (now) {
                        explicitExcludedClassNames.remove(row.getClassName());
                    } else {
                        explicitExcludedClassNames.add(row.getClassName());
                    }
                }
                refreshSummary();
                fireConfigChanged();
            }
        }));
        allRows.setAll(rows);
        buildPackageIndex(rows);
        // An import/default can happen while the asynchronous scan is still running.  The
        // stored configuration must be applied only after rows exist, otherwise wildcard
        // exclusions would be present in YAML but still look selected in the class table.
        if (hasAppliedConfiguration) {
            applyConfiguration(appliedIncludePackages, appliedIncludeClasses, appliedClassExcludeConditions,
                    appliedMethodExcludePatterns);
        } else if (!selectedPackageSet.isEmpty()) {
            selectedPackageSet.addAll(expandIncludedPackages(new ArrayList<>(selectedPackageSet)));
        }
        buildPackageTree();
        refreshVisibleRows();
        setLoadingClasses(false);
        fireConfigChanged();
    }

    private void setLoadingClasses(boolean loading) {
        loadingClasses = loading;
        packageSearchField.setDisable(loading);
        packageTree.setDisable(loading);
        classTable.setDisable(loading);
        loadingLockedControls.forEach(control -> control.setDisable(loading));
        getStyleClass().removeAll("class-selection-loading");
        if (loading) {
            getStyleClass().add("class-selection-loading");
        }
        if (onLoadingChanged != null) {
            onLoadingChanged.accept(loading);
        }
    }

    public boolean isLoadingClasses() {
        return loadingClasses;
    }

    private void buildPackageIndex(List<ClassRow> rows) {
        packageToRows.clear();
        packageToDescendantRows.clear();
        allPackages.clear();
        for (ClassRow row : rows) {
            String packageName = row.getPackageName();
            packageToRows.computeIfAbsent(packageName, key -> new ArrayList<>()).add(row);
            String[] parts = packageName.split("\\.");
            String path = "";
            for (String part : parts) {
                if (part.isBlank()) continue;
                path = path.isEmpty() ? part : path + "." + part;
                packageToDescendantRows.computeIfAbsent(path, key -> new ArrayList<>()).add(row);
            }
        }
        allPackages.addAll(packageToRows.keySet().stream().sorted().toList());
    }

    private void buildPackageTree() {
        buildPackageTree(currentSelectedPackagePath());
    }

    private void buildPackageTree(String restorePath) {
        rootItem.getChildren().clear();
        searchPathSet.clear();
        searchHitSet.clear();
        Map<String, CheckBoxTreeItem<String>> nodes = new LinkedHashMap<>();
        String needle = searchNeedle();
        CheckBoxTreeItem<String> firstHit = null;
        CheckBoxTreeItem<String> restoreItem = null;
        for (String pkg : allPackages) {
            if (pkg.isBlank()) continue;
            if (!needle.isEmpty() && !packageMatches(pkg, needle)) continue;
            String[] parts = pkg.split("\\.");
            String path = "";
            CheckBoxTreeItem<String> parent = rootItem;
            for (String part : parts) {
                path = path.isEmpty() ? part : path + "." + part;
                if (!needle.isEmpty()) {
                    searchPathSet.add(path);
                    if (pathMatchesNeedle(path, needle)) {
                        searchHitSet.add(path);
                    }
                }
                CheckBoxTreeItem<String> item = nodes.get(path);
                if (item == null) {
                    item = new CheckBoxTreeItem<>(part);
                    item.setIndependent(true);
                    item.setSelected(isPackageSelected(path));
                    item.setExpanded(!needle.isEmpty());
                    nodes.put(path, item);
                    parent.getChildren().add(item);
                    final CheckBoxTreeItem<String> treeItem = item;
                    item.selectedProperty().addListener((obs, old, selected) -> {
                        if (treeUpdating) return;
                        handlePackageSelection(treeItemPath(treeItem), selected);
                    });
                }
                if (!needle.isEmpty()) {
                    item.setExpanded(true);
                    if (firstHit == null && searchHitSet.contains(path)) {
                        firstHit = item;
                    }
                }
                if (restorePath != null && restorePath.equals(path)) {
                    restoreItem = item;
                }
                parent = item;
            }
        }
        sortTreeChildrenRecursive(rootItem);
        rootItem.setExpanded(true);
        packageTree.refresh();
        CheckBoxTreeItem<String> target = restoreItem != null ? restoreItem : firstHit;
        if (target != null) {
            packageTree.getSelectionModel().select(target);
            Platform.runLater(() -> {
                int row = packageTree.getRow(target);
                if (row >= 0) packageTree.scrollTo(Math.max(0, row - 4));
            });
        }
        refreshSummary();
    }

    /** Computes the package hierarchy off the JavaFX thread, then commits it without blocking rendering. */
    private void buildPackageTreeAsync(String restorePath, Runnable onComplete) {
        if (packageTreeBuildTimeline != null) packageTreeBuildTimeline.stop();
        List<String> packages = List.copyOf(allPackages);
        Set<String> selectedPackages = Set.copyOf(selectedPackageSet);
        String needle = searchNeedle();
        reportProgress("正在分析包层级...");
        Thread modelBuilder = new Thread(() -> {
            PackageTreeModel model = packageTreeModel(packages, selectedPackages, needle);
            Platform.runLater(() -> commitPackageTreeModel(model, needle, restorePath, onComplete));
        }, "build-package-tree-model");
        modelBuilder.setDaemon(true);
        modelBuilder.start();
    }

    private static PackageTreeModel packageTreeModel(List<String> packages, Set<String> selectedPackages,
                                                      String needle) {
        Map<String, PackageTreeNode> nodes = new LinkedHashMap<>();
        Set<String> paths = new LinkedHashSet<>();
        Set<String> hits = new LinkedHashSet<>();
        for (String pkg : packages) {
            if (pkg.isBlank() || (!needle.isEmpty() && !pathMatchesNeedleStatic(pkg, needle))) continue;
            String path = "";
            String parentPath = "";
            for (String part : pkg.split("\\.")) {
                path = path.isEmpty() ? part : path + "." + part;
                if (!needle.isEmpty()) {
                    paths.add(path);
                    if (pathMatchesNeedleStatic(path, needle)) hits.add(path);
                }
                nodes.putIfAbsent(path, new PackageTreeNode(path, parentPath, part,
                        selectedPackages.contains(path)));
                parentPath = path;
            }
        }
        return new PackageTreeModel(List.copyOf(nodes.values()), paths, hits);
    }

    private void commitPackageTreeModel(PackageTreeModel model, String needle, String restorePath,
                                        Runnable onComplete) {
        rootItem.getChildren().clear();
        searchPathSet.clear();
        searchPathSet.addAll(model.searchPaths);
        searchHitSet.clear();
        searchHitSet.addAll(model.searchHits);
        Map<String, CheckBoxTreeItem<String>> nodes = new LinkedHashMap<>();
        final int[] nextIndex = {0};
        final CheckBoxTreeItem<String>[] firstHit = new CheckBoxTreeItem[] {null};
        final CheckBoxTreeItem<String>[] restoreItem = new CheckBoxTreeItem[] {null};
        packageTreeBuildTimeline = new Timeline(new KeyFrame(javafx.util.Duration.millis(16), event -> {
            int end = Math.min(nextIndex[0] + 300, model.nodes.size());
            reportProgress("正在构建包树（" + end + " / " + model.nodes.size() + "）...");
            for (int index = nextIndex[0]; index < end; index++) {
                PackageTreeNode node = model.nodes.get(index);
                CheckBoxTreeItem<String> parent = node.parentPath.isEmpty() ? rootItem : nodes.get(node.parentPath);
                if (parent == null) continue;
                CheckBoxTreeItem<String> item = new CheckBoxTreeItem<>(node.part);
                item.setIndependent(true);
                item.setSelected(node.selected);
                item.setExpanded(!needle.isEmpty());
                parent.getChildren().add(item);
                nodes.put(node.path, item);
                item.selectedProperty().addListener((obs, old, selected) -> {
                    if (!treeUpdating) handlePackageSelection(treeItemPath(item), selected);
                });
                if (firstHit[0] == null && model.searchHits.contains(node.path)) firstHit[0] = item;
                if (restorePath != null && restorePath.equals(node.path)) restoreItem[0] = item;
            }
            nextIndex[0] = end;
            if (end < model.nodes.size()) return;
            packageTreeBuildTimeline.stop();
            packageTreeBuildTimeline = null;
            rootItem.setExpanded(true);
            CheckBoxTreeItem<String> target = restoreItem[0] != null ? restoreItem[0] : firstHit[0];
            if (target != null) {
                packageTree.getSelectionModel().select(target);
                Platform.runLater(() -> {
                    int row = packageTree.getRow(target);
                    if (row >= 0) packageTree.scrollTo(Math.max(0, row - 4));
                });
            }
            reportProgress("正在刷新选择摘要...");
            refreshSummary();
            if (onComplete != null) onComplete.run();
        }));
        packageTreeBuildTimeline.setCycleCount(Timeline.INDEFINITE);
        packageTreeBuildTimeline.play();
    }

    private String currentSelectedPackagePath() {
        return treeItemPath(packageTree.getSelectionModel().getSelectedItem());
    }

    private boolean packageMatches(String packageName, String needle) {
        return pathMatchesNeedle(packageName, needle);
    }

    private String searchNeedle() {
        return packageSearchField.getText() == null
                ? ""
                : packageSearchField.getText().trim().toLowerCase(Locale.ROOT);
    }

    private boolean pathMatchesNeedle(String packagePath, String needle) {
        return pathMatchesNeedleStatic(packagePath, needle);
    }

    private static boolean pathMatchesNeedleStatic(String packagePath, String needle) {
        if (needle.isBlank()) return true;
        String path = packagePath.toLowerCase(Locale.ROOT);
        if (needle.contains(".")) {
            return path.equals(needle) || path.startsWith(needle + ".") || path.contains("." + needle);
        }
        for (String segment : path.split("\\.")) {
            if (segment.equals(needle) || segment.startsWith(needle)) return true;
            if (needle.length() >= 3 && segment.contains(needle)) return true;
        }
        return false;
    }

    private boolean isPackageSelected(String packageName) {
        return selectedPackageSet.contains(packageName);
    }

    private void sortTreeChildren(CheckBoxTreeItem<String> parent) {
        parent.getChildren().sort(Comparator.comparing(item -> item.getValue().toString()));
    }

    private void sortTreeChildrenRecursive(CheckBoxTreeItem<String> parent) {
        sortTreeChildren(parent);
        for (TreeItem<String> child : parent.getChildren()) {
            if (child instanceof CheckBoxTreeItem<String> checkBoxChild) {
                sortTreeChildrenRecursive(checkBoxChild);
            }
        }
    }

    private void refreshVisibleRows() {
        refreshVisibleRowsFromSelection();
    }

    private void handlePackageSelection(String packagePath, boolean selected) {
        treeUpdating = true;
        try {
            applyPackageSelection(packagePath, selected);
            rebuildSelectionFromPackageState();
            rebuildExplicitClassExclusions();
            buildPackageTree(packagePath);
            refreshVisibleRowsFromSelection();
            fireConfigChanged();
        } finally {
            treeUpdating = false;
        }
    }

    private void applyPackageSelection(String packagePath, boolean selected) {
        if (packagePath == null || packagePath.isBlank()) return;
        Set<String> affected = packageToDescendantRows.keySet().stream()
                .filter(path -> path.equals(packagePath) || path.startsWith(packagePath + "."))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (packageToDescendantRows.containsKey(packagePath)) affected.add(packagePath);
        if (affected.isEmpty()) affected.add(packagePath);
        if (selected) {
            selectedPackageSet.addAll(affected);
        } else {
            selectedPackageSet.removeAll(affected);
        }
    }

    private void setVisibleRowsIncluded(boolean included) {
        bulkUpdating = true;
        try {
            for (ClassRow row : visibleRows) {
                if (row.isIncluded() == included) continue;
                row.includedProperty().set(included);
                includedSelectedClassCount += included ? 1 : -1;
                if (included) explicitExcludedClassNames.remove(row.getClassName());
                else explicitExcludedClassNames.add(row.getClassName());
            }
        } finally {
            bulkUpdating = false;
        }
        refreshSummary();
        fireConfigChanged();
    }

    private void invertVisibleRowsIncluded() {
        bulkUpdating = true;
        try {
            for (ClassRow row : visibleRows) {
                boolean included = !row.isIncluded();
                row.includedProperty().set(included);
                includedSelectedClassCount += included ? 1 : -1;
                if (included) explicitExcludedClassNames.remove(row.getClassName());
                else explicitExcludedClassNames.add(row.getClassName());
            }
        } finally {
            bulkUpdating = false;
        }
        refreshSummary();
        fireConfigChanged();
    }

    private void rebuildSelectionFromPackageState() {
        selectedRows.clear();
        selectedRows.addAll(allRows.stream()
                .filter(this::isRowSelectedByPackageState)
                .toList());
        includedSelectedClassCount = selectedRows.stream().filter(ClassRow::isIncluded).count();
    }

    private void refreshVisibleRowsFromSelection() {
        String classNeedle = classSearchField.getText() == null ? "" : classSearchField.getText().trim().toLowerCase();
        filteredRows.clear();
        filteredRows.addAll(selectedRows.stream()
                .filter(row -> classNeedle.isEmpty()
                        || row.getClassName().toLowerCase().contains(classNeedle)
                        || row.getPackageName().toLowerCase().contains(classNeedle))
                .toList());
        currentPage = 0;
        refreshCurrentPage();
        refreshSummary();
    }

    private void changePage(int delta) {
        int pageCount = pageCount();
        int target = Math.max(0, Math.min(currentPage + delta, pageCount - 1));
        if (target == currentPage) return;
        currentPage = target;
        refreshCurrentPage();
    }

    private void refreshCurrentPage() {
        int pageCount = pageCount();
        currentPage = Math.max(0, Math.min(currentPage, pageCount - 1));
        int start = currentPage * CLASS_PAGE_SIZE;
        int end = Math.min(start + CLASS_PAGE_SIZE, filteredRows.size());
        visibleRows.setAll(start >= end ? List.of() : filteredRows.subList(start, end));
        pageLabel.setText(filteredRows.isEmpty() ? "无匹配类" : "第 " + (currentPage + 1) + " / " + pageCount + " 页");
        previousPageButton.setDisable(currentPage == 0 || filteredRows.isEmpty());
        nextPageButton.setDisable(currentPage >= pageCount - 1 || filteredRows.isEmpty());
    }

    private int pageCount() {
        return Math.max(1, (filteredRows.size() + CLASS_PAGE_SIZE - 1) / CLASS_PAGE_SIZE);
    }

    private boolean isRowSelectedByPackageState(ClassRow row) {
        boolean selected = false;
        String path = "";
        for (String part : row.getPackageName().split("\\.")) {
            if (part.isBlank()) continue;
            path = path.isEmpty() ? part : path + "." + part;
            if (packageToDescendantRows.containsKey(path)) {
                selected = selectedPackageSet.contains(path);
            }
        }
        return selected;
    }

    private boolean isRowUnderIncludedPackage(ClassRow row) {
        for (String include : effectiveIncludedPackages()) {
            if (row.getPackageName().equals(include) || row.getPackageName().startsWith(include + ".")) {
                return true;
            }
        }
        return false;
    }

    private void refreshSummary() {
        long packageCount = packageToRows.size();
        long selectedPackageCount = selectedPackageSet.size();
        classSummaryLabel.setText("已选类 " + includedSelectedClassCount + " 个");
        packageSummaryLabel.setText("包选择（共 " + packageCount + " 个包，已选 " + selectedPackageCount + " 个）");
    }

    public SelectionMetrics getSelectionMetrics() {
        long enhancedClasses = selectedRows.stream().filter(ClassRow::isIncluded).count();
        long enhancedMethods = selectedRows.stream()
                .filter(ClassRow::isIncluded)
                .mapToLong(row -> row.getMonitorableMethodCount(excludedMethodPatterns))
                .sum();
        return new SelectionMetrics(enhancedClasses, enhancedMethods);
    }

    /** Calculates the preview footprint off the JavaFX thread after taking a lightweight UI snapshot. */
    public void getSelectionMetricsAsync(Consumer<SelectionMetrics> onComplete) {
        List<SelectionMetricRow> snapshot = selectedRows.stream()
                .map(row -> new SelectionMetricRow(row.getClassName(), row.isIncluded(), row.getMethodNames()))
                .toList();
        Set<String> methodExcludes = Set.copyOf(excludedMethodPatterns);
        Thread calculator = new Thread(() -> {
            long classes = 0;
            long methods = 0;
            for (SelectionMetricRow row : snapshot) {
                if (!row.included) continue;
                classes++;
                methods += row.methodNames.stream().filter(method -> methodExcludes.stream()
                        .noneMatch(pattern -> ClassRow.methodPatternMatches(pattern, row.className, method))).count();
            }
            SelectionMetrics metrics = new SelectionMetrics(classes, methods);
            Platform.runLater(() -> {
                if (onComplete != null) onComplete.accept(metrics);
            });
        }, "calculate-selection-metrics");
        calculator.setDaemon(true);
        calculator.start();
    }

    /**
     * Calculates the actual enhancement footprint for a configuration that may
     * have been edited directly in the YAML editor. This deliberately does not
     * read the current checkbox state: the YAML is the source of truth at
     * attach time.
     */
    public SelectionMetrics metricsForConfiguration(List<String> includePackages, List<String> includeClasses,
                                                     List<String> excludedClassConditions,
                                                     List<String> excludedMethods) {
        List<String> includes = includePackages == null ? List.of() : includePackages.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        Set<String> classIncludes = includeClasses == null ? Set.of() : includeClasses.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<String> classExcludes = excludedClassConditions == null ? List.of() : excludedClassConditions.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
        Set<String> methodExcludes = new LinkedHashSet<>();
        if (excludedMethods != null) {
            excludedMethods.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(methodExcludes::add);
        }

        long enhancedClasses = 0;
        long enhancedMethods = 0;
        for (ClassRow row : allRows) {
            boolean included = includes.stream().anyMatch(include ->
                    row.getPackageName().equals(include) || row.getPackageName().startsWith(include + "."))
                    || classIncludes.contains(row.getClassName());
            if (!included || classExcludes.stream().anyMatch(condition -> matchesClassExclude(condition, row))) {
                continue;
            }
            enhancedClasses++;
            enhancedMethods += row.getMonitorableMethodCount(methodExcludes);
        }
        return new SelectionMetrics(enhancedClasses, enhancedMethods);
    }

    public String getPackagePrefixes() {
        return String.join("|", effectiveIncludedPackages());
    }

    public String getExcludedClassConditions() {
        return getExcludedClassConditionList().stream()
                .collect(Collectors.joining("|"));
    }

    public List<String> getExcludedClassConditionList() {
        List<String> conditions = new ArrayList<>(extraClassExcludeConditions);
        conditions.addAll(explicitExcludedClassNames.stream().map(className -> "cls:" + className).toList());
        return conditions.stream().distinct().toList();
    }

    public List<String> getIncludedPackages() {
        return effectiveIncludedPackages();
    }

    public List<String> getIncludedClasses() {
        return appliedIncludeClasses;
    }

    public List<String> getExcludedClasses() {
        return excludedClassRows().stream()
                .map(ClassRow::getClassName)
                .toList();
    }

    public List<String> getExcludedMethods() {
        return excludedMethodPatterns.stream()
                .filter(pattern -> !pattern.isBlank())
                .toList();
    }

    public void applyConfiguration(List<String> includePackages, List<String> includeClasses,
                                   List<String> excludedClassConditions,
                                   List<String> excludedMethods) {
        appliedIncludePackages = normalizedValues(includePackages);
        appliedIncludeClasses = normalizedValues(includeClasses);
        appliedClassExcludeConditions = normalizedValues(excludedClassConditions);
        appliedMethodExcludePatterns = normalizedValues(excludedMethods);
        hasAppliedConfiguration = true;
        bulkUpdating = true;
        treeUpdating = true;
        try {
            packageSearchField.clear();
            selectedPackageSet.clear();
            selectedPackageSet.addAll(expandIncludedPackages(appliedIncludePackages));
            Set<String> exactIncludedClasses = new LinkedHashSet<>(appliedIncludeClasses);

            List<String> classConditions = appliedClassExcludeConditions;
            Set<String> loadedClassNames = allRows.stream()
                    .map(ClassRow::getClassName)
                    .collect(Collectors.toSet());
            Set<String> exactExcludedClasses = new LinkedHashSet<>();
            List<String> wildcardClassConditions = new ArrayList<>();
            List<String> packageExcludePatterns = new ArrayList<>();
            extraClassExcludeConditions.clear();
            for (String condition : classConditions) {
                if (condition.startsWith("pkg:")) {
                    String pkg = condition.substring(4).trim();
                    if (!pkg.isEmpty()) {
                        packageExcludePatterns.add(pkg);
                        extraClassExcludeConditions.add(condition);
                    }
                } else if (condition.startsWith("cls:")) {
                    String className = condition.substring(4).trim();
                    if (className.isEmpty()) continue;
                    if (className.contains("*")) {
                        wildcardClassConditions.add(condition);
                        extraClassExcludeConditions.add(condition);
                    } else {
                        exactExcludedClasses.add(className);
                        if (!loadedClassNames.contains(className)) {
                            extraClassExcludeConditions.add(condition);
                        }
                    }
                }
            }
            for (ClassRow row : allRows) {
                boolean excluded = exactExcludedClasses.contains(row.getClassName())
                        || packageExcludePatterns.stream().anyMatch(pkg ->
                                PackagePatternMatcher.matches(pkg, row.getPackageName()))
                        || wildcardClassConditions.stream().anyMatch(condition ->
                                matchesClassExclude(condition, row));
                boolean included = (isRowSelectedByPackageState(row)
                        || exactIncludedClasses.contains(row.getClassName())) && !excluded;
                if (row.isIncluded() != included) {
                    row.includedProperty().set(included);
                }
            }

            excludedMethodPatterns.clear();
            excludedMethodPatterns.addAll(appliedMethodExcludePatterns);

            selectedRows.clear();
            selectedRows.addAll(allRows.stream()
                    .filter(row -> isRowSelectedByPackageState(row)
                            || exactIncludedClasses.contains(row.getClassName()))
                    .toList());
            rebuildExplicitClassExclusions();
            includedSelectedClassCount = selectedRows.stream().filter(ClassRow::isIncluded).count();
            buildPackageTree();
            refreshVisibleRowsFromSelection();
        } finally {
            treeUpdating = false;
            bulkUpdating = false;
        }
        fireConfigChanged();
    }

    /** Applies a large YAML scope in batches so JavaFX can keep rendering the loading overlay. */
    public void applyConfigurationAsync(List<String> includePackages, List<String> includeClasses,
                                        List<String> excludedClassConditions,
                                        List<String> excludedMethods, Runnable onComplete) {
        if (configurationApplyTimeline != null) configurationApplyTimeline.stop();
        reportProgress("正在准备 YAML 配置...");
        appliedIncludePackages = normalizedValues(includePackages);
        appliedIncludeClasses = normalizedValues(includeClasses);
        appliedClassExcludeConditions = normalizedValues(excludedClassConditions);
        appliedMethodExcludePatterns = normalizedValues(excludedMethods);
        hasAppliedConfiguration = true;
        bulkUpdating = true;
        treeUpdating = true;
        packageSearchField.clear();
        selectedPackageSet.clear();
        selectedPackageSet.addAll(expandIncludedPackages(appliedIncludePackages));
        Set<String> exactIncludedClasses = new LinkedHashSet<>(appliedIncludeClasses);

        Set<String> loadedClassNames = allRows.stream().map(ClassRow::getClassName).collect(Collectors.toSet());
        Set<String> exactExcludedClasses = new LinkedHashSet<>();
        List<String> wildcardClassConditions = new ArrayList<>();
        List<String> packageExcludePatterns = new ArrayList<>();
        extraClassExcludeConditions.clear();
        for (String condition : appliedClassExcludeConditions) {
            if (condition.startsWith("pkg:")) {
                String pkg = condition.substring(4).trim();
                if (!pkg.isEmpty()) {
                    packageExcludePatterns.add(pkg);
                    extraClassExcludeConditions.add(condition);
                }
            } else if (condition.startsWith("cls:")) {
                String className = condition.substring(4).trim();
                if (className.isEmpty()) continue;
                if (className.contains("*")) {
                    wildcardClassConditions.add(condition);
                    extraClassExcludeConditions.add(condition);
                } else {
                    exactExcludedClasses.add(className);
                    if (!loadedClassNames.contains(className)) extraClassExcludeConditions.add(condition);
                }
            }
        }
        excludedMethodPatterns.clear();
        excludedMethodPatterns.addAll(appliedMethodExcludePatterns);

        List<ClassRow> rows = List.copyOf(allRows);
        List<ClassIdentity> identities = rows.stream()
                .map(row -> new ClassIdentity(row.getClassName(), row.getPackageName(), row.getSimpleName()))
                .toList();
        Thread matcher = new Thread(() -> {
            reportProgress("正在匹配类筛选规则（共 " + identities.size() + " 个类）...");
            boolean[] included = new boolean[identities.size()];
            for (int index = 0; index < identities.size(); index++) {
                ClassIdentity identity = identities.get(index);
                boolean excluded = exactExcludedClasses.contains(identity.className)
                        || packageExcludePatterns.stream().anyMatch(pkg ->
                                PackagePatternMatcher.matches(pkg, identity.packageName))
                        || wildcardClassConditions.stream().anyMatch(condition ->
                                matchesClassExclude(condition, identity));
                boolean inIncludedPackage = appliedIncludePackages.stream().anyMatch(include ->
                        identity.packageName.equals(include) || identity.packageName.startsWith(include + "."));
                included[index] = (inIncludedPackage || exactIncludedClasses.contains(identity.className)) && !excluded;
            }
            Platform.runLater(() -> applyIncludedRowsInBatches(rows, included, exactIncludedClasses, onComplete));
        }, "match-yaml-class-scope");
        matcher.setDaemon(true);
        matcher.start();
    }

    private void applyIncludedRowsInBatches(List<ClassRow> rows, boolean[] included,
                                            Set<String> exactIncludedClasses, Runnable onComplete) {
        final int[] nextIndex = {0};
        configurationApplyTimeline = new Timeline(new KeyFrame(javafx.util.Duration.millis(16), event -> {
            int end = Math.min(nextIndex[0] + 300, rows.size());
            reportProgress("正在更新类选择（" + end + " / " + rows.size() + "）...");
            for (int index = nextIndex[0]; index < end; index++) {
                ClassRow row = rows.get(index);
                if (row.isIncluded() != included[index]) row.includedProperty().set(included[index]);
            }
            nextIndex[0] = end;
            if (end < rows.size()) return;
            configurationApplyTimeline.stop();
            configurationApplyTimeline = null;
            selectedRows.clear();
            selectedRows.addAll(allRows.stream().filter(row -> isRowSelectedByPackageState(row)
                    || exactIncludedClasses.contains(row.getClassName())).toList());
            rebuildExplicitClassExclusions();
            includedSelectedClassCount = selectedRows.stream().filter(ClassRow::isIncluded).count();
            reportProgress("正在生成包树...");
            buildPackageTreeAsync(currentSelectedPackagePath(), () -> {
                refreshVisibleRowsFromSelection();
                treeUpdating = false;
                bulkUpdating = false;
                fireConfigChanged();
                if (onComplete != null) onComplete.run();
            });
        }));
        configurationApplyTimeline.setCycleCount(Timeline.INDEFINITE);
        configurationApplyTimeline.play();
    }

    private static List<String> normalizedValues(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }

    private void reportProgress(String message) {
        if (onProgressChanged == null || message == null || message.isBlank()) return;
        if (Platform.isFxApplicationThread()) {
            onProgressChanged.accept(message);
        } else {
            Platform.runLater(() -> onProgressChanged.accept(message));
        }
    }

    private Set<String> expandIncludedPackages(List<String> includes) {
        List<String> prefixes = includes.stream()
                .map(value -> value == null ? "" : value.trim())
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
        Set<String> expanded = new LinkedHashSet<>(prefixes);
        if (prefixes.isEmpty()) return expanded;
        for (String packagePath : packageToDescendantRows.keySet()) {
            for (String prefix : prefixes) {
                if (packagePath.equals(prefix) || packagePath.startsWith(prefix + ".")) {
                    expanded.add(packagePath);
                    break;
                }
            }
        }
        return expanded;
    }

    private List<String> effectiveIncludedPackages() {
        List<String> includes = new ArrayList<>();
        for (String pkg : selectedPackageSet.stream().sorted().toList()) {
            boolean coveredByParent = includes.stream()
                    .anyMatch(parent -> pkg.equals(parent) || pkg.startsWith(parent + "."));
            if (!coveredByParent) includes.add(pkg);
        }
        return includes;
    }

    private List<ClassRow> excludedClassRows() {
        return allRows.stream()
                .filter(this::isRowUnderIncludedPackage)
                .filter(row -> !isRowSelectedByPackageState(row) || !row.isIncluded())
                .toList();
    }

    private void showClassExcludeDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("排除类配置");
        dialog.setHeaderText("优先从已缓存的类索引中勾选；手动规则仅作为兜底。");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        VBox presets = new VBox(8);
        presets.getStyleClass().add("method-rule-presets");
        List<ClassSelectionRules.ClassRulePreset> presetRules = ClassSelectionRules.classRulePresets();
        List<CheckBox> checkBoxes = new ArrayList<>();
        for (ClassSelectionRules.ClassRulePreset preset : presetRules) {
            CheckBox box = new CheckBox(preset.label() + "  (" + String.join(", ", preset.conditions()) + ")");
            box.setSelected(extraClassExcludeConditions.containsAll(preset.conditions()));
            box.getStyleClass().add("method-rule-checkbox");
            checkBoxes.add(box);
            presets.getChildren().add(box);
        }

        TextField classSearch = new TextField();
        classSearch.setPromptText("搜索类名 / 包名 / 类型");
        classSearch.getStyleClass().add("search-field");

        ObservableList<ClassExcludeRow> allClassRows = FXCollections.observableArrayList(buildClassExcludeRows());
        ObservableList<ClassExcludeRow> visibleClassRows = FXCollections.observableArrayList(allClassRows);
        TableView<ClassExcludeRow> excludeTable = new TableView<>(visibleClassRows);
        excludeTable.getStyleClass().add("class-table");
        excludeTable.setEditable(true);
        excludeTable.setPrefHeight(320);

        TableColumn<ClassExcludeRow, Boolean> excludeCol = new TableColumn<>("");
        excludeCol.setCellValueFactory(cell -> cell.getValue().excludedProperty());
        excludeCol.setCellFactory(CheckBoxTableCell.forTableColumn(excludeCol));
        excludeCol.setEditable(true);
        excludeCol.setPrefWidth(46);
        excludeCol.setMaxWidth(52);

        TableColumn<ClassExcludeRow, String> classCol = new TableColumn<>("类名");
        classCol.setCellValueFactory(new PropertyValueFactory<>("simpleName"));
        classCol.setPrefWidth(220);

        TableColumn<ClassExcludeRow, String> pkgCol = new TableColumn<>("包名");
        pkgCol.setCellValueFactory(new PropertyValueFactory<>("packageName"));
        pkgCol.setPrefWidth(360);

        TableColumn<ClassExcludeRow, String> typeCol = new TableColumn<>("类型");
        typeCol.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeCol.setPrefWidth(120);

        TableColumn<ClassExcludeRow, Integer> methodCol = new TableColumn<>("方法数");
        methodCol.setCellValueFactory(new PropertyValueFactory<>("methodCount"));
        methodCol.setPrefWidth(78);

        excludeTable.getColumns().addAll(excludeCol, classCol, pkgCol, typeCol, methodCol);
        classSearch.textProperty().addListener((obs, old, value) -> {
            String needle = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            visibleClassRows.setAll(allClassRows.stream()
                    .filter(row -> needle.isEmpty()
                            || row.getClassName().toLowerCase(Locale.ROOT).contains(needle)
                            || row.getPackageName().toLowerCase(Locale.ROOT).contains(needle)
                            || row.getType().toLowerCase(Locale.ROOT).contains(needle))
                    .toList());
        });

        Button excludeVisible = new Button("排除当前结果");
        excludeVisible.getStyleClass().add("ghost-btn");
        excludeVisible.setOnAction(e -> visibleClassRows.forEach(row -> row.excludedProperty().set(true)));
        Button includeVisible = new Button("取消当前结果");
        includeVisible.getStyleClass().add("ghost-btn");
        includeVisible.setOnAction(e -> visibleClassRows.forEach(row -> row.excludedProperty().set(false)));
        Region classSpacer = new Region();
        HBox.setHgrow(classSpacer, Priority.ALWAYS);
        HBox classTools = new HBox(8, classSearch, classSpacer, excludeVisible, includeVisible);
        classTools.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(classSearch, Priority.ALWAYS);

        TextArea customRules = new TextArea(customClassExcludeConditions(presetRules));
        customRules.setPromptText("每行一个规则，例如：\ncls:*DTO\ncls:com.example.UserDTO\npkg:com.example.internal");
        customRules.setPrefRowCount(4);
        customRules.setWrapText(false);
        customRules.getStyleClass().add("method-rule-textarea");

        Label hint = new Label("规则支持 cls:完整类名、cls:*DTO、pkg:包名前缀；保存后写入 YAML 并在开始监控时生效。");
        hint.getStyleClass().add("hint-label");
        VBox content = new VBox(12,
                section("快捷排除"),
                presets,
                section("从类索引选择"),
                classTools,
                excludeTable,
                section("手动兜底规则"),
                customRules,
                hint);
        content.setPadding(new Insets(4));
        content.setPrefWidth(860);
        dialog.getDialogPane().setPrefSize(940, 740);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getStylesheets().addAll(
                getScene() != null ? getScene().getStylesheets() : List.of());

        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> {
            extraClassExcludeConditions.clear();
            for (int i = 0; i < presetRules.size(); i++) {
                if (checkBoxes.get(i).isSelected()) {
                    extraClassExcludeConditions.addAll(presetRules.get(i).conditions());
                }
            }
            for (String line : customRules.getText().split("\\R")) {
                String condition = ClassSelectionRules.normalizeClassExcludeCondition(line.trim());
                if (!condition.isEmpty() && !condition.startsWith("#")) {
                    extraClassExcludeConditions.add(condition);
                }
            }

            bulkUpdating = true;
            try {
                Set<String> exactExcluded = allClassRows.stream()
                        .filter(ClassExcludeRow::isExcluded)
                        .map(row -> "cls:" + row.getClassName())
                        .collect(Collectors.toSet());
                for (ClassRow row : allRows) {
                    boolean excludedByExact = exactExcluded.contains("cls:" + row.getClassName());
                    boolean excludedByRule = extraClassExcludeConditions.stream()
                            .anyMatch(condition -> matchesClassExclude(condition, row));
                    row.includedProperty().set(!(excludedByExact || excludedByRule));
                }
            } finally {
                bulkUpdating = false;
            }
            refreshVisibleRowsFromSelection();
            fireConfigChanged();
        });
    }

    private List<ClassExcludeRow> buildClassExcludeRows() {
        return allRows.stream()
                .filter(this::isRowUnderIncludedPackage)
                .sorted(Comparator.comparing(ClassRow::getClassName))
                .map(row -> new ClassExcludeRow(row, !row.isIncluded()))
                .toList();
    }

    private String customClassExcludeConditions(List<ClassSelectionRules.ClassRulePreset> presets) {
        Set<String> presetConditions = presets.stream()
                .flatMap(preset -> preset.conditions().stream())
                .collect(Collectors.toSet());
        return extraClassExcludeConditions.stream()
                .filter(condition -> !presetConditions.contains(condition))
                .collect(Collectors.joining("\n"));
    }

    private static boolean matchesClassExclude(String condition, ClassRow row) {
        return ClassSelectionRules.matchesClassExclude(condition, row.getClassName(), row.getSimpleName(),
                row.getPackageName());
    }

    private static boolean matchesClassExclude(String condition, ClassIdentity identity) {
        return ClassSelectionRules.matchesClassExclude(condition, identity.className, identity.simpleName,
                identity.packageName);
    }

    public static class ClassExcludeRow {
        private final ClassRow classRow;
        private final BooleanProperty excluded = new SimpleBooleanProperty(false);

        ClassExcludeRow(ClassRow classRow, boolean excluded) {
            this.classRow = classRow;
            this.excluded.set(excluded);
        }

        public String getClassName() { return classRow.getClassName(); }
        public String getSimpleName() { return classRow.getSimpleName(); }
        public String getPackageName() { return classRow.getPackageName(); }
        public String getType() { return classRow.getType(); }
        public int getMethodCount() { return classRow.getMethodCount(); }
        public boolean isExcluded() { return excluded.get(); }
        public BooleanProperty excludedProperty() { return excluded; }
    }

    private void showMethodExcludeDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("排除方法配置");
        dialog.setHeaderText("优先从已缓存的方法索引中勾选；手动输入仅作为兜底。");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        VBox presets = new VBox(8);
        presets.getStyleClass().add("method-rule-presets");
        List<ClassSelectionRules.MethodRulePreset> presetRules = ClassSelectionRules.methodRulePresets();
        List<CheckBox> checkBoxes = new ArrayList<>();
        for (ClassSelectionRules.MethodRulePreset preset : presetRules) {
            CheckBox box = new CheckBox(preset.label() + "  (" + String.join(", ", preset.patterns()) + ")");
            box.setSelected(excludedMethodPatterns.containsAll(preset.patterns()));
            box.getStyleClass().add("method-rule-checkbox");
            checkBoxes.add(box);
            presets.getChildren().add(box);
        }

        TextField methodSearch = new TextField();
        methodSearch.setPromptText("搜索方法 / 类名 / 包名");
        methodSearch.getStyleClass().add("search-field");

        ObservableList<MethodRow> allMethodRows = FXCollections.observableArrayList(buildIndexedMethodRows());
        ObservableList<MethodRow> visibleMethodRows = FXCollections.observableArrayList(allMethodRows);
        TableView<MethodRow> methodTable = new TableView<>(visibleMethodRows);
        methodTable.getStyleClass().add("class-table");
        methodTable.setEditable(true);
        methodTable.setPrefHeight(300);

        TableColumn<MethodRow, Boolean> excludeCol = new TableColumn<>("");
        excludeCol.setCellValueFactory(cell -> cell.getValue().excludedProperty());
        excludeCol.setCellFactory(CheckBoxTableCell.forTableColumn(excludeCol));
        excludeCol.setEditable(true);
        excludeCol.setPrefWidth(46);
        excludeCol.setMaxWidth(52);

        TableColumn<MethodRow, String> methodCol = new TableColumn<>("方法");
        methodCol.setCellValueFactory(new PropertyValueFactory<>("methodName"));
        methodCol.setPrefWidth(170);

        TableColumn<MethodRow, String> classCol = new TableColumn<>("类");
        classCol.setCellValueFactory(new PropertyValueFactory<>("simpleClassName"));
        classCol.setPrefWidth(190);

        TableColumn<MethodRow, String> packageCol = new TableColumn<>("包名");
        packageCol.setCellValueFactory(new PropertyValueFactory<>("packageName"));
        packageCol.setPrefWidth(300);

        methodTable.getColumns().addAll(excludeCol, methodCol, classCol, packageCol);
        methodSearch.textProperty().addListener((obs, old, value) -> {
            String needle = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            visibleMethodRows.setAll(allMethodRows.stream()
                    .filter(row -> needle.isEmpty()
                            || row.getMethodName().toLowerCase(Locale.ROOT).contains(needle)
                            || row.getClassName().toLowerCase(Locale.ROOT).contains(needle)
                            || row.getPackageName().toLowerCase(Locale.ROOT).contains(needle))
                    .toList());
        });

        Button selectVisible = new Button("排除当前结果");
        selectVisible.getStyleClass().add("ghost-btn");
        selectVisible.setOnAction(e -> visibleMethodRows.forEach(row -> row.excludedProperty().set(true)));
        Button clearVisible = new Button("取消当前结果");
        clearVisible.getStyleClass().add("ghost-btn");
        clearVisible.setOnAction(e -> visibleMethodRows.forEach(row -> row.excludedProperty().set(false)));
        Region methodSpacer = new Region();
        HBox.setHgrow(methodSpacer, Priority.ALWAYS);
        HBox methodTools = new HBox(8, methodSearch, methodSpacer, selectVisible, clearVisible);
        methodTools.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(methodSearch, Priority.ALWAYS);

        TextArea customRules = new TextArea(customMethodPatterns(presetRules));
        customRules.setPromptText("每行一个规则，例如：\nfind*\n*.internal*\ncom.example.UserService.refreshCache");
        customRules.setPrefRowCount(4);
        customRules.setWrapText(false);
        customRules.getStyleClass().add("method-rule-textarea");

        Label hint = new Label("规则支持方法名通配符（get*）、类名+方法名（com.xxx.Service.find*），保存后写入 YAML 并在开始监控时生效。");
        hint.getStyleClass().add("hint-label");
        VBox content = new VBox(12,
                section("常用排除"),
                presets,
                section("从方法索引选择"),
                methodTools,
                methodTable,
                section("手动兜底规则"),
                customRules,
                hint);
        content.setPadding(new Insets(4));
        content.setPrefWidth(820);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(900, 720);
        dialog.getDialogPane().getStylesheets().addAll(
                getScene() != null ? getScene().getStylesheets() : List.of());

        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> {
            excludedMethodPatterns.clear();
            for (int i = 0; i < presetRules.size(); i++) {
                if (checkBoxes.get(i).isSelected()) {
                    excludedMethodPatterns.addAll(presetRules.get(i).patterns());
                }
            }
            allMethodRows.stream()
                    .filter(MethodRow::isExcluded)
                    .map(MethodRow::getExactPattern)
                    .forEach(excludedMethodPatterns::add);
            for (String line : customRules.getText().split("\\R")) {
                String pattern = line.trim();
                if (!pattern.isEmpty() && !pattern.startsWith("#")) {
                    excludedMethodPatterns.add(pattern);
                }
            }
            refreshSummary();
            fireConfigChanged();
        });
    }

    private List<MethodRow> buildIndexedMethodRows() {
        return allRows.stream()
                .filter(this::isRowUnderIncludedPackage)
                .sorted(Comparator.comparing(ClassRow::getClassName))
                .flatMap(row -> row.getMethodNames().stream()
                        .sorted()
                        .map(method -> new MethodRow(row.getClassName(), method,
                                excludedMethodPatterns.contains(row.getClassName() + "." + method))))
                .toList();
    }

    private String customMethodPatterns(List<ClassSelectionRules.MethodRulePreset> presets) {
        Set<String> presetPatterns = presets.stream()
                .flatMap(preset -> preset.patterns().stream())
                .collect(Collectors.toSet());
        Set<String> indexedPatterns = indexedExactMethodPatterns();
        return excludedMethodPatterns.stream()
                .filter(pattern -> !presetPatterns.contains(pattern))
                .filter(pattern -> !indexedPatterns.contains(pattern))
                .collect(Collectors.joining("\n"));
    }

    private Set<String> indexedExactMethodPatterns() {
        return allRows.stream()
                .flatMap(row -> row.getMethodNames().stream()
                        .map(method -> row.getClassName() + "." + method))
                .collect(Collectors.toSet());
    }

    public static class MethodRow {
        private final String className;
        private final String methodName;
        private final String simpleClassName;
        private final String packageName;
        private final BooleanProperty excluded = new SimpleBooleanProperty(false);

        MethodRow(String className, String methodName, boolean excluded) {
            this.className = className;
            this.methodName = methodName;
            int dot = className.lastIndexOf('.');
            this.simpleClassName = dot >= 0 ? className.substring(dot + 1) : className;
            this.packageName = dot >= 0 ? className.substring(0, dot) : "";
            this.excluded.set(excluded);
        }

        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
        public String getSimpleClassName() { return simpleClassName; }
        public String getPackageName() { return packageName; }
        public String getExactPattern() { return className + "." + methodName; }
        public boolean isExcluded() { return excluded.get(); }
        public BooleanProperty excludedProperty() { return excluded; }
    }

    private void fireConfigChanged() {
        // Preserve the effective state for a later class-index reload.  This also means a
        // package/class/method action and an imported YAML have the same source of truth.
        appliedIncludePackages = normalizedValues(effectiveIncludedPackages());
        // A class-only YAML scope has no selected package-tree node. Keep its exact whitelist
        // while package and method controls are edited elsewhere in the panel.
        appliedIncludeClasses = normalizedValues(appliedIncludeClasses);
        appliedClassExcludeConditions = normalizedValues(getExcludedClassConditionList());
        appliedMethodExcludePatterns = normalizedValues(getExcludedMethods());
        hasAppliedConfiguration = true;
        if (onConfigChanged != null) onConfigChanged.accept(null);
    }

    private void rebuildExplicitClassExclusions() {
        explicitExcludedClassNames.clear();
        for (ClassRow row : excludedClassRows()) {
            if (extraClassExcludeConditions.stream().noneMatch(condition -> matchesClassExclude(condition, row))) {
                explicitExcludedClassNames.add(row.getClassName());
            }
        }
    }

    /**
     * A rule such as {@code cls:*DTO} cannot express a one-class inclusion override.  If a user
     * explicitly reselects one matching loaded class, turn that rule into exact exclusions for
     * the remaining selected rows.  The regenerated YAML then says precisely what the table
     * shows instead of silently keeping the old wildcard rule.
     */
    private void reconcileRuleOverridesFromClassSelection() {
        List<String> overriddenRules = extraClassExcludeConditions.stream()
                .filter(condition -> allRows.stream().anyMatch(row -> isRowUnderIncludedPackage(row)
                        && row.isIncluded() && matchesClassExclude(condition, row)))
                .toList();
        for (String condition : overriddenRules) {
            extraClassExcludeConditions.remove(condition);
            allRows.stream()
                    .filter(this::isRowUnderIncludedPackage)
                    .filter(row -> !row.isIncluded())
                    .filter(row -> matchesClassExclude(condition, row))
                    .map(row -> "cls:" + row.getClassName())
                    .forEach(extraClassExcludeConditions::add);
        }
    }

    public record SelectionMetrics(long enhancedClasses, long enhancedMethods) {}

    private record ClassIdentity(String className, String packageName, String simpleName) {}

    private record SelectionMetricRow(String className, boolean included, List<String> methodNames) {}

    private record PackageTreeNode(String path, String parentPath, String part, boolean selected) {}

    private record PackageTreeModel(List<PackageTreeNode> nodes, Set<String> searchPaths,
                                    Set<String> searchHits) {}

    public record ActualEnhancementMetrics(long transformedClasses, long failedClasses,
                                           long transformedMethods) {}

    private record ActualEnhancementResult(long transformedClasses, long failedClasses,
                                           long transformedMethods, List<ActualEnhancementRow> rows) {}

    private record ActualEnhancementRow(String className, String category, String status, String trigger,
                                        int methodCount, List<String> methodNames, String reason) {}

    public static class ClassRow {
        private final StringProperty className = new SimpleStringProperty();
        private final StringProperty simpleName = new SimpleStringProperty();
        private final StringProperty packageName = new SimpleStringProperty();
        private final StringProperty type = new SimpleStringProperty();
        private final BooleanProperty included = new SimpleBooleanProperty(true);
        private final List<String> methodNames;

        ClassRow(String className, List<String> methodNames) {
            this.className.set(className);
            this.methodNames = methodNames == null ? List.of() : List.copyOf(methodNames);
            int dot = className.lastIndexOf('.');
            this.simpleName.set(dot >= 0 ? className.substring(dot + 1) : className);
            this.packageName.set(dot >= 0 ? className.substring(0, dot) : "");
            this.type.set(guessType(this.simpleName.get()));
        }

        public String getClassName() { return className.get(); }
        public String getSimpleName() { return simpleName.get(); }
        public String getPackageName() { return packageName.get(); }
        public String getType() { return type.get(); }
        public int getMethodCount() { return methodNames.size(); }
        public List<String> getMethodNames() { return methodNames; }
        public int getMonitorableMethodCount(Set<String> excludedPatterns) {
            if (methodNames.isEmpty()) return 0;
            return (int) methodNames.stream()
                    .filter(method -> excludedPatterns.stream().noneMatch(pattern ->
                            methodPatternMatches(pattern, className.get(), method)))
                    .count();
        }
        public String getCallCount() { return "-"; }
        public String getAverageDuration() { return "-"; }
        public String getStatus() { return isIncluded() ? "已包含" : "已排除"; }
        public boolean isIncluded() { return included.get(); }
        public BooleanProperty includedProperty() { return included; }

        private static String guessType(String name) {
            if (name.endsWith("Controller")) return "Controller";
            if (name.endsWith("Service") || name.endsWith("ServiceImpl")) return "Service";
            if (name.endsWith("Mapper")) return "Mapper";
            if (name.endsWith("Repository")) return "Repository";
            if (name.endsWith("Listener")) return "Listener";
            if (name.endsWith("Config")) return "Config";
            if (name.endsWith("DTO") || name.endsWith("VO")) return "DTO/VO";
            return "Class";
        }

        private static boolean methodPatternMatches(String pattern, String className, String methodName) {
            if (pattern == null || pattern.isBlank()) return false;
            String value = pattern.trim();
            if (value.matches("[A-Za-z_$][\\w$]*\\.\\*")
                    && methodName.matches(ClassSelectionRules.wildcardToRegex(
                    value.substring(0, value.length() - 2) + "*"))) {
                return true;
            }
            String target = value.contains(".") ? className + "." + methodName : methodName;
            return target.matches(ClassSelectionRules.wildcardToRegex(value));
        }

    }
}
