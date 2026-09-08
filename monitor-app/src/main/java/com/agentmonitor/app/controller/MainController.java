package com.agentmonitor.app.controller;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentmonitor.app.model.JvmMetrics;
import com.agentmonitor.app.model.JvmProcess;
import com.agentmonitor.app.model.MonitoringSession;
import com.agentmonitor.app.model.SessionResource;
import com.agentmonitor.app.model.SessionRetentionPolicy;
import com.agentmonitor.app.config.MonitoringConfigYaml;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.app.report.SessionAnalysisPrompt;
import com.agentmonitor.app.report.model.AnalysisConfig;
import com.agentmonitor.app.report.model.CaptureQuality;
import com.agentmonitor.app.service.JvmMetricsService;
import com.agentmonitor.app.service.JvmService;
import com.agentmonitor.app.service.OfflineTraceReplayLoader;
import com.agentmonitor.app.service.SessionRetentionManager;
import com.agentmonitor.app.service.SessionResourceLoader;
import com.agentmonitor.app.service.TraceServer;
import com.agentmonitor.app.util.AppLog;
import com.agentmonitor.app.util.SessionArtifactOpener;
import com.agentmonitor.app.ui.CallTreePane;
import com.agentmonitor.app.ui.ClassSelectionPane;
import com.agentmonitor.app.ui.JvmMetricsPane;
import com.agentmonitor.app.ui.LogViewerDialog;

import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;

public class MainController {

    private static final long MAX_ENHANCED_CLASSES = 6000;

    private final CallTreePane callTreePane;
    private final ClassSelectionPane classSelectionPane;
    private final Label statusLabel;
    private final ProgressIndicator spinner;
    private final StackPane loadingOverlay;
    private final Label loadingLabel;
    private final Label statusDotLabel;
    private final Label elapsedLabel;
    private final TextArea yamlPreview;
    private final Label enhancementScopeLabel = new Label("增强范围 · 待执行");
    private final Label enhancedClassLabel = new Label("0");
    private final Label enhancedMethodLabel = new Label("0");
    private final Label enhancementStateLabel = new Label("配置范围");
    private final Button actualEnhancementDetailButton = new Button("查看实际结果 →");
    private final Label cpuOverheadLabel = new Label("0");
    private final Label memoryOverheadLabel = new Label("0");
    private final Label riskLevelLabel = new Label("--");
    private final Label heapChipLabel = new Label("Heap --");
    private final Label threadChipLabel = new Label("Threads --");
    private final Label gcChipLabel = new Label("GC --");
    /** Updated from the attached JVM's session resource, not from the monitor App process. */
    private final Label resourceServiceLabel = new Label();
    private final Label resourceEnvironmentLabel = new Label();
    private final Label resourceRuntimeLabel = new Label();
    private final Label agentConnectionLabel = new Label();
    private final JvmMetricsPane jvmMetricsPane;
    private final JvmMetricsService jvmMetricsService;
    private final SessionResourceLoader sessionResourceLoader = new SessionResourceLoader();
    /** Read-only history loader. It never shares a lifecycle with the active Collector. */
    private final OfflineTraceReplayLoader offlineTraceReplayLoader = new OfflineTraceReplayLoader();
    private final AtomicBoolean metricsSampling = new AtomicBoolean(false);
    /** Guards the one-way transition when the selected target JVM has terminated. */
    private final AtomicBoolean targetExitDetected = new AtomicBoolean(false);
    private final AtomicBoolean targetExitNavigationStarted = new AtomicBoolean(false);
    /** The one authoritative configuration; UI controls only patch their owned scope fields. */
    private MonitoringConfig currentConfig;
    private boolean updatingYaml = false;
    private boolean yamlDirty = false;
    /** Keeps the blocking overlay in sync with its two legitimate pre-attach operations. */
    private boolean classIndexLoading = false;
    private boolean yamlApplyBusy = false;
    private Timeline elapsedTimeline;
    private Timeline metricsTimeline;
    private PauseTransition selectionMetricsDelay;
    private long selectionMetricsGeneration;
    private long monitorStartMs;
    /** A reconnect sends READY again; initialize the monitoring UI only for the first one. */
    private boolean collectorReady;
    private final Button startBtn;
    private final Button stopBtn;
    private final Button pauseBtn;
    private final Button refreshBtn;
    private final Button logBtn;
    private final Button importBtn;
    private final Button saveBtn;
    private final Button sessionBtn;
    private final Button replayBtn;
    private final BorderPane root;
    private final StackPane rootWithOverlay;
    /** A target-bound window that must not outlive its monitored JVM. */
    private Dialog<Void> outputDialog;

    private final JvmProcess target;
    private Runnable onSwitchTarget;
    private TraceServer traceServer;
    private volatile MonitoringSession monitoringSession;
    private volatile SessionRetentionPolicy monitoringSessionRetentionPolicy = SessionRetentionPolicy.disabled();
    private volatile String lastSessionStopMessage = "";
    private volatile boolean lastSessionOutputDrained = true;
    private volatile CaptureQuality lastSessionCaptureQuality = CaptureQuality.unavailable();
    private volatile boolean monitoring = false;
    private volatile boolean paused = false;
    private volatile boolean cancelRequested = false;
    private volatile boolean controllerClosed = false;
    private boolean offlineReplayLoading = false;
    private String offlineReplayProgressMessage = "";

    public MainController(JvmProcess target) {
        this.target  = target;
        callTreePane = new CallTreePane();
        classSelectionPane = new ClassSelectionPane(target.getPid());
        jvmMetricsPane = new JvmMetricsPane();
        jvmMetricsService = new JvmMetricsService(target.getPid());
        classSelectionPane.setOnConfigChanged(ignored -> {
            refreshYamlPreview();
            scheduleSelectionSummaryRefresh();
        });
        classSelectionPane.setOnLoadingChanged(loading -> {
            classIndexLoading = loading;
            refreshBlockingOverlay(loading ? "正在扫描目标 JVM 包结构，请稍候..." : "");
            if (loading) {
                setStatus("正在扫描目标 JVM 包结构...");
            } else {
                setStatus("包结构扫描完成");
            }
        });
        classSelectionPane.setOnProgressChanged(message -> {
            if (classIndexLoading || yamlApplyBusy) showProgress(true, message);
            setStatus(message);
        });
        classSelectionPane.setOnActualEnhancementResultChanged(metrics -> {
            enhancementScopeLabel.setText("增强范围 · 实际");
            enhancedClassLabel.setText(formatCount(metrics.transformedClasses()));
            enhancedMethodLabel.setText(formatCount(metrics.transformedMethods()));
            enhancementStateLabel.setText(metrics.failedClasses() == 0 ? "attach 完成"
                    : "失败 " + metrics.failedClasses() + " 类");
            enhancementStateLabel.getStyleClass().removeAll("enhancement-state-pending", "enhancement-state-ready",
                    "enhancement-state-warning");
            enhancementStateLabel.getStyleClass().add(metrics.failedClasses() == 0
                    ? "enhancement-state-ready" : "enhancement-state-warning");
            updateImpactIndicators(metrics.transformedClasses(), metrics.transformedMethods());
            actualEnhancementDetailButton.setDisable(false);
        });
        statusLabel     = new Label("就绪");
        statusDotLabel  = new Label("● 未运行");
        statusDotLabel.getStyleClass().add("status-dot-inactive");
        elapsedLabel    = new Label("");
        elapsedLabel.getStyleClass().add("elapsed-label");
        yamlPreview = new TextArea();
        yamlPreview.getStyleClass().add("yaml-preview");
        // The preview is generated from the typed model. Editing is routed through the dialog
        // below so an invalid half-typed document cannot diverge from the package selector.
        yamlPreview.setEditable(false);
        yamlPreview.setWrapText(false);
        yamlPreview.textProperty().addListener((obs, old, now) -> {
            if (!updatingYaml) yamlDirty = true;
        });
        startBtn     = new Button("Apply Configuration");
        stopBtn      = new Button("Stop Monitoring");
        pauseBtn     = new Button("⏸ 暂停输出");
        refreshBtn   = new Button("⟳ 刷新");
        logBtn       = new Button("▤ 运行日志");
        importBtn    = new Button("⇩ 导入配置");
        saveBtn      = new Button("▣ 保存配置");
        sessionBtn   = new Button("▣ 本次会话");
        sessionBtn.getStyleClass().add("ghost-btn");
        sessionBtn.setDisable(true);
        sessionBtn.setOnAction(e -> showSessionActionsDialog(monitoringSession,
                lastSessionOutputDrained, lastSessionStopMessage,
                lastSessionCaptureQuality,
                new SessionRetentionManager.CleanupResult(List.of(), List.of())));
        replayBtn = new Button("↺ 打开历史会话");
        replayBtn.getStyleClass().add("ghost-btn");
        replayBtn.setOnAction(e -> openHistoricalReplay());

        spinner = new ProgressIndicator(-1);
        spinner.getStyleClass().add("loading-spinner");
        spinner.setMinSize(64, 64);
        spinner.setMaxSize(64, 64);

        loadingLabel = new Label("请稍候...");
        loadingLabel.getStyleClass().add("loading-label");

        VBox spinnerBox = new VBox(12, spinner, loadingLabel);
        spinnerBox.setAlignment(Pos.CENTER);

        loadingOverlay = new StackPane(spinnerBox);
        loadingOverlay.getStyleClass().add("loading-overlay");
        loadingOverlay.setVisible(false);
        loadingOverlay.setMouseTransparent(false);

        root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setTop(buildToolbar());
        root.setCenter(buildMainPanel());
        root.setBottom(buildStatusBar());
        initializeDefaultConfiguration();

        rootWithOverlay = new StackPane(root, loadingOverlay);
        if (classSelectionPane.isLoadingClasses()) {
            showProgress(true, "正在扫描目标 JVM 包结构，请稍候...");
        }
        startMetricsPolling();
        watchTargetProcessExit();
    }

    public void setOnSwitchTarget(Runnable handler) {
        this.onSwitchTarget = handler;
        if (targetExitDetected.get()) {
            Platform.runLater(this::returnToTargetSelectionAfterExit);
        }
    }

    private ToolBar buildToolbar() {
        Label icon = new Label("⌁");
        icon.getStyleClass().add("app-icon");

        Label appTitle = new Label("Java 性能监控工具");
        appTitle.getStyleClass().add("app-title");
        configureResourceLabels();
        configureAgentConnectionLabel();

        refreshBtn.getStyleClass().addAll("ghost-btn", "toolbar-refresh-btn");
        refreshBtn.setOnAction(e -> {
            classSelectionPane.reloadClasses();
        });

        saveBtn.getStyleClass().addAll("ghost-btn", "toolbar-save-btn");
        saveBtn.setOnAction(e -> saveYamlConfig());

        importBtn.getStyleClass().addAll("ghost-btn", "toolbar-import-btn");
        importBtn.setOnAction(e -> importYamlConfig());

        logBtn.getStyleClass().addAll("ghost-btn", "toolbar-log-btn");
        logBtn.setOnAction(e -> showLogDialog());

        Button switchBtn = new Button("切换目标");
        switchBtn.getStyleClass().add("primary-btn");
        switchBtn.setOnAction(e -> {
            if (monitoring) stopMonitoring();
            if (onSwitchTarget != null) onSwitchTarget.run();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox metricChips = buildMetricChips();

        ToolBar toolbar = new ToolBar(icon, appTitle, new Separator(), resourceServiceLabel,
                resourceEnvironmentLabel, resourceRuntimeLabel, agentConnectionLabel, spacer, metricChips, switchBtn);
        toolbar.getStyleClass().add("app-toolbar");
        return toolbar;
    }

    private void configureAgentConnectionLabel() {
        setAgentConnectionState("状态: 未连接", "agent-connection-offline");
    }

    private void setAgentConnectionState(String text, String stateStyle) {
        agentConnectionLabel.getStyleClass().setAll("agent-connection-label", stateStyle);
        agentConnectionLabel.setText(text);
    }

    private void configureResourceLabels() {
        resourceServiceLabel.getStyleClass().setAll("target-label", "resource-service-label");
        resourceServiceLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        resourceServiceLabel.setMaxWidth(310);

        resourceEnvironmentLabel.getStyleClass().setAll("resource-environment-chip");
        resourceEnvironmentLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        resourceEnvironmentLabel.setMaxWidth(150);

        resourceRuntimeLabel.getStyleClass().setAll("target-label", "resource-runtime-label");
        resourceRuntimeLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        resourceRuntimeLabel.setMaxWidth(260);
        resetResourceSummary();
    }

    /** Restores the pre-attach identity so a previous session never leaks into the next one. */
    private void resetResourceSummary() {
        String targetName = displayTargetName(target.getDisplayName());
        resourceServiceLabel.setText("目标: " + (targetName.isBlank() ? "Java 进程" : targetName)
                + " · PID " + target.getPid());
        resourceEnvironmentLabel.setText("");
        resourceEnvironmentLabel.setVisible(false);
        resourceEnvironmentLabel.setManaged(false);
        resourceRuntimeLabel.setText("JVM: 尚未 attach");
        setResourceTooltips("目标 JVM（应用配置后将以 resource.json 的实际身份更新）\nPID: " + target.getPid());
    }

    private void markResourceAwaitingAgent() {
        resourceRuntimeLabel.setText("JVM: 等待 Agent 确认");
        setResourceTooltips("正在等待本次 attach 的 resource.json。\nPID: " + target.getPid());
    }

    /** Reads the collector-written file away from JavaFX's event thread once the Agent is ready. */
    private void loadSessionResource(MonitoringSession session, TraceServer expectedServer) {
        Thread loader = new Thread(() -> {
            SessionResourceLoader.Result result = sessionResourceLoader.load(session);
            if (!result.loaded()) {
                AppLog.warn("[agent-monitor] cannot read session resource " + session.resourcePath()
                        + ": " + result.failureDetail());
                Platform.runLater(() -> {
                    if (controllerClosed || targetExitDetected.get() || monitoringSession != session
                            || traceServer != expectedServer) return;
                    resourceRuntimeLabel.setText("JVM: 身份未上报");
                    setResourceTooltips("Agent 已连接，但本次 resource.json 未能读取。\n" + result.failureDetail());
                });
                return;
            }
            SessionResource loadedResource = result.resource();
            Platform.runLater(() -> {
                if (controllerClosed || targetExitDetected.get() || monitoringSession != session
                        || traceServer != expectedServer) return;
                applyResourceSummary(loadedResource);
            });
        }, "agent-monitor-resource-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void applyResourceSummary(SessionResource resource) {
        String serviceName = firstNonBlank(resource.serviceName(), displayTargetName(target.getDisplayName()), "Java 进程");
        String serviceVersion = resource.serviceVersion();
        String processId = firstNonBlank(resource.processId(), target.getPid());
        resourceServiceLabel.setText("服务: " + serviceName
                + (serviceVersion.isBlank() ? "" : " v" + serviceVersion)
                + " · PID " + processId);

        String environment = resource.environment();
        resourceEnvironmentLabel.setText(environment.isBlank() ? "" : "环境: " + environment);
        resourceEnvironmentLabel.setVisible(!environment.isBlank());
        resourceEnvironmentLabel.setManaged(!environment.isBlank());

        String runtime = resource.runtimeDisplayName();
        resourceRuntimeLabel.setText("JVM: " + (runtime.isBlank() ? "未上报" : runtime));
        setResourceTooltips(resourceTooltipText(resource));
    }

    private void setResourceTooltips(String text) {
        resourceServiceLabel.setTooltip(resourceTooltip(text));
        resourceEnvironmentLabel.setTooltip(resourceTooltip(text));
        resourceRuntimeLabel.setTooltip(resourceTooltip(text));
    }

    private static Tooltip resourceTooltip(String text) {
        Tooltip tooltip = new Tooltip(text);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(520);
        return tooltip;
    }

    private static String resourceTooltipText(SessionResource resource) {
        List<String> details = new ArrayList<>();
        addResourceDetail(details, "服务", resource.serviceName());
        addResourceDetail(details, "版本", resource.serviceVersion());
        addResourceDetail(details, "环境", resource.environment());
        addResourceDetail(details, "实例", resource.value("service.instance.id"));
        addResourceDetail(details, "PID", resource.processId());
        addResourceDetail(details, "命令", resource.value("process.command"));
        addResourceDetail(details, "JVM", resource.runtimeDisplayName());
        addResourceDetail(details, "主机", resource.value("host.name"));
        addResourceDetail(details, "Agent", SessionResource.joinNonBlank(" ",
                resource.value("telemetry.distro.name"), resource.value("telemetry.distro.version")));
        return details.isEmpty() ? "resource.json 未包含可展示的 JVM 身份信息。" : String.join("\n", details);
    }

    private static void addResourceDetail(List<String> details, String label, String value) {
        if (value != null && !value.isBlank()) details.add(label + ": " + value);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private SplitPane buildMainPanel() {
        buildActionBar();

        VBox statusInfo = new VBox(2, statusDotLabel, elapsedLabel);
        statusInfo.setAlignment(Pos.CENTER_LEFT);

        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);

        Button outputBtn = new Button("▤ 查看监控输出");
        outputBtn.getStyleClass().add("ghost-btn");
        outputBtn.setOnAction(e -> showOutputDialog());

        HBox btnBox = new HBox(8, refreshBtn, logBtn, importBtn, saveBtn, outputBtn, replayBtn, sessionBtn);
        btnBox.setAlignment(Pos.CENTER_RIGHT);

        HBox topBar = new HBox(statusInfo, topSpacer, btnBox);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setMinHeight(48);
        topBar.setPrefHeight(48);
        topBar.setMaxHeight(48);
        topBar.getStyleClass().add("center-action-bar");

        VBox.setVgrow(classSelectionPane, Priority.ALWAYS);

        VBox center = new VBox(8, topBar, classSelectionPane);
        center.getStyleClass().add("monitor-panel");
        center.setFillWidth(true);

        StackPane previewPanel = buildPreviewPanel();
        previewPanel.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(previewPanel, Priority.ALWAYS);
        VBox preview = new VBox(8, buildPreviewTopBar(), previewPanel);
        preview.getStyleClass().add("preview-column");
        preview.setFillWidth(true);

        SplitPane split = new SplitPane(center, preview);
        split.setDividerPositions(0.76);
        return split;
    }

    private HBox buildMetricChips() {
        heapChipLabel.getStyleClass().addAll("metric-chip", "metric-chip-heap");
        threadChipLabel.getStyleClass().addAll("metric-chip", "metric-chip-thread");
        gcChipLabel.getStyleClass().addAll("metric-chip", "metric-chip-gc");
        HBox chips = new HBox(6, heapChipLabel, threadChipLabel, gcChipLabel);
        chips.setAlignment(Pos.CENTER_RIGHT);
        return chips;
    }

    private HBox buildPreviewTopBar() {
        ToggleButton configBtn = new ToggleButton("配置");
        ToggleButton metricsBtn = new ToggleButton("◈ JVM 指标");
        configBtn.getStyleClass().add("segment-btn");
        metricsBtn.getStyleClass().add("segment-btn");
        ToggleGroup group = new ToggleGroup();
        configBtn.setToggleGroup(group);
        metricsBtn.setToggleGroup(group);
        configBtn.setSelected(true);
        configBtn.setOnAction(e -> switchPreviewTab(true));
        metricsBtn.setOnAction(e -> switchPreviewTab(false));
        HBox segments = new HBox(4, configBtn, metricsBtn);
        segments.getStyleClass().add("segment-group");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox topBar = new HBox(segments, spacer);
        topBar.setAlignment(Pos.CENTER_LEFT);
        topBar.setMinHeight(48);
        topBar.setPrefHeight(48);
        topBar.setMaxHeight(48);
        topBar.getStyleClass().add("preview-top-bar");
        return topBar;
    }

    private StackPane buildPreviewPanel() {
        Label title = new Label("配置 YAML");
        title.getStyleClass().add("card-title");

        Button editBtn = new Button("大屏编辑");
        editBtn.getStyleClass().add("ghost-btn");
        editBtn.setOnAction(e -> showYamlEditorDialog());

        Button copyBtn = new Button("复制");
        copyBtn.getStyleClass().add("ghost-btn");
        copyBtn.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(yamlPreview.getText());
            Clipboard.getSystemClipboard().setContent(content);
            setStatus("YAML 配置已复制到剪贴板");
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(title, spacer, editBtn, copyBtn);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("preview-panel-head");

        ScrollPane scroll = new ScrollPane(yamlPreview);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.getStyleClass().add("yaml-scroll");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox summary = buildConfigSummaryPanel();

        VBox box = new VBox(10, head, scroll, summary);
        box.getStyleClass().add("preview-panel");
        box.setMinWidth(270);
        box.setPrefWidth(330);
        box.setMaxHeight(Double.MAX_VALUE);
        jvmMetricsPane.setVisible(false);
        jvmMetricsPane.setManaged(false);
        StackPane stack = new StackPane(box, jvmMetricsPane);
        stack.setMaxHeight(Double.MAX_VALUE);
        return stack;
    }

    private void switchPreviewTab(boolean showConfig) {
        if (root == null) return;
        StackPane stack = findPreviewStack();
        if (stack == null || stack.getChildren().size() < 2) return;
        stack.getChildren().get(0).setVisible(showConfig);
        stack.getChildren().get(0).setManaged(showConfig);
        stack.getChildren().get(1).setVisible(!showConfig);
        stack.getChildren().get(1).setManaged(!showConfig);
    }

    private StackPane findPreviewStack() {
        if (!(root.getCenter() instanceof SplitPane split) || split.getItems().size() < 2) return null;
        if (!(split.getItems().get(1) instanceof VBox previewColumn) || previewColumn.getChildren().size() < 2) return null;
        return previewColumn.getChildren().get(1) instanceof StackPane stack ? stack : null;
    }

    private void showYamlEditorDialog() {
        TextArea editor = new TextArea(yamlPreview.getText());
        editor.getStyleClass().add("yaml-preview");
        editor.setWrapText(false);
        editor.setEditable(true);
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("编辑 YAML 配置");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(editor);
        dialog.getDialogPane().setPrefSize(980, 720);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().getStylesheets().addAll(root.getScene() != null
                ? root.getScene().getStylesheets()
                : java.util.List.of());
        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(ok -> {
            applyYamlTextAsync(editor.getText(), "YAML 已更新，左侧包选择已同步");
        });
    }

    private VBox buildConfigSummaryPanel() {
        Label title = new Label("选择概览");
        Label classCaption = new Label("增强类");
        Label methodCaption = new Label("增强方法");
        Label cpuCaption = new Label("CPU 预估增量");
        Label memoryCaption = new Label("内存预估增量");
        Label riskCaption = new Label("运行风险预估");
        title.getStyleClass().add("summary-title");
        enhancementScopeLabel.getStyleClass().add("enhancement-scope-label");
        enhancedClassLabel.getStyleClass().add("summary-metric-primary");
        enhancedMethodLabel.getStyleClass().add("summary-metric-primary");
        enhancementStateLabel.getStyleClass().add("enhancement-state-label");
        actualEnhancementDetailButton.getStyleClass().add("ghost-btn");
        actualEnhancementDetailButton.setTooltip(new Tooltip("展示本次 attach 的实际增强类、方法和失败原因。\n"
                + "尚未 attach 时会说明当前没有实际结果。"));
        actualEnhancementDetailButton.setOnAction(e -> classSelectionPane.showActualEnhancementResult());
        classCaption.getStyleClass().add("summary-caption");
        methodCaption.getStyleClass().add("summary-caption");
        cpuCaption.getStyleClass().add("summary-caption");
        memoryCaption.getStyleClass().add("summary-caption");
        riskCaption.getStyleClass().add("summary-caption");
        cpuOverheadLabel.getStyleClass().add("impact-value");
        memoryOverheadLabel.getStyleClass().add("impact-value");
        riskLevelLabel.getStyleClass().add("summary-risk-neutral");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox header = new HBox(8, title, headerSpacer, enhancementStateLabel);
        header.setAlignment(Pos.CENTER_LEFT);
        VBox classMetric = new VBox(3, classCaption, enhancedClassLabel);
        classMetric.getStyleClass().addAll("summary-count", "summary-stat");
        VBox methodMetric = new VBox(3, methodCaption, enhancedMethodLabel);
        methodMetric.getStyleClass().addAll("summary-count", "summary-stat");
        HBox.setHgrow(classMetric, Priority.ALWAYS);
        HBox.setHgrow(methodMetric, Priority.ALWAYS);
        HBox counts = new HBox(8, classMetric, methodMetric);
        counts.getStyleClass().add("summary-counts-row");
        counts.setAlignment(Pos.CENTER_LEFT);
        VBox cpuImpact = new VBox(2, cpuCaption, cpuOverheadLabel);
        cpuImpact.getStyleClass().add("summary-impact");
        VBox memoryImpact = new VBox(2, memoryCaption, memoryOverheadLabel);
        memoryImpact.getStyleClass().add("summary-impact");
        VBox riskImpact = new VBox(2, riskCaption, riskLevelLabel);
        riskImpact.getStyleClass().add("summary-impact");
        HBox.setHgrow(cpuImpact, Priority.ALWAYS);
        HBox.setHgrow(memoryImpact, Priority.ALWAYS);
        HBox.setHgrow(riskImpact, Priority.ALWAYS);
        HBox impacts = new HBox(6, cpuImpact, memoryImpact, riskImpact);
        impacts.getStyleClass().add("summary-impacts");
        Region detailSpacer = new Region();
        HBox.setHgrow(detailSpacer, Priority.ALWAYS);
        HBox detailRow = new HBox(detailSpacer, actualEnhancementDetailButton);
        detailRow.setAlignment(Pos.CENTER_RIGHT);
        detailRow.getStyleClass().add("summary-detail-row");
        VBox summary = new VBox(8, header, enhancementScopeLabel, counts, impacts, detailRow);
        summary.getStyleClass().add("selection-summary-card");
        return summary;
    }

    private void buildActionBar() {
        startBtn.getStyleClass().addAll("action-btn", "start-btn");
        startBtn.setOnAction(e -> startMonitoring());

        stopBtn.getStyleClass().addAll("action-btn", "stop-btn");
        stopBtn.setDisable(true);
        stopBtn.setVisible(false);
        stopBtn.setManaged(false);
        stopBtn.setOnAction(e -> stopMonitoring());

        pauseBtn.getStyleClass().addAll("action-btn", "pause-btn");
        pauseBtn.setDisable(true);
        pauseBtn.setOnAction(e -> togglePause());
    }

    private HBox buildStatusBar() {
        statusLabel.getStyleClass().add("status-label");
        statusLabel.setMinWidth(0);
        statusLabel.setMaxWidth(220);
        statusLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(10, statusLabel, spacer, startBtn, stopBtn);
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 10, 4, 10));
        return bar;
    }

    private void startMonitoring() {
        final MonitoringConfig config;
        try {
            config = MonitoringConfigYaml.parse(yamlPreview.getText());
            currentConfig = config;
        } catch (IllegalArgumentException error) {
            showAlert(Alert.AlertType.ERROR, "启动失败：YAML 配置无效。\n\n" + error.getMessage());
            setStatus("启动失败: YAML 配置无效");
            return;
        }
        String pkgs = config.packagePrefixesArg();
        ClassSelectionPane.SelectionMetrics metrics = classSelectionPane.metricsForConfiguration(
                config.scope().includePackages(), config.scope().includeClasses(), config.scope().excludeConditions(),
                config.scope().excludeMethods());
        if (metrics.enhancedClasses() > MAX_ENHANCED_CLASSES) {
            showAlert(Alert.AlertType.ERROR, "预计增强的类数量为 " + metrics.enhancedClasses()
                    + "，超过上限 " + MAX_ENHANCED_CLASSES + "。\n请缩小包选择范围或增加排除类规则。");
            setStatus("启动失败: 选择范围过大");
            return;
        }
        if (pkgs.isBlank()) {
            showAlert(Alert.AlertType.WARNING, "启动失败：YAML 中 includePackages 不能为空。\n\n请先在左侧勾选包，或手动在 YAML 中添加：\nincludePackages:\n  - com.example");
            setStatus("启动失败: includePackages 不能为空");
            return;
        }

        monitoringSessionRetentionPolicy = new SessionRetentionPolicy(config.output().retention().maxSessions());
        monitoringSession = MonitoringSession.create(config.output().sessionRootDir());
        resetResourceSummary();
        classSelectionPane.clearActualEnhancementResult();
        refreshSelectionSummary();
        lastSessionStopMessage = "";
        lastSessionOutputDrained = true;
        lastSessionCaptureQuality = CaptureQuality.unavailable();
        collectorReady = false;
        sessionBtn.setDisable(true);
        AppLog.info("[agent-monitor] session started id=" + monitoringSession.sessionId()
                + " directory=" + monitoringSession.sessionDirectory());
        callTreePane.beginMonitoringSession();
        TraceServer monitorServer = new TraceServer(0, toAnalysisConfig(config.analysis()), monitoringSession);
        traceServer = monitorServer;
        monitorServer.setOnRootNode(callTreePane::addRootNode);
        monitorServer.setOnStatusChange(msg -> Platform.runLater(() -> {
            if (targetExitDetected.get() || traceServer != monitorServer) return;
            if (msg.startsWith("Agent 已连接")) {
                setAgentConnectionState("状态: 初始化中", "agent-connection-connecting");
            } else if ("Agent 连接已断开".equals(msg)) {
                setAgentConnectionState("状态: 重连中", "agent-connection-connecting");
            }
            setStatus(msg);
        }));

        int actualPort;
        try {
            setAgentConnectionState("状态: 连接中", "agent-connection-connecting");
            markResourceAwaitingAgent();
            actualPort = monitorServer.start();
        } catch (Exception ex) {
            monitorServer.stop();
            if (traceServer == monitorServer) traceServer = null;
            setAgentConnectionState("状态: 未连接", "agent-connection-offline");
            resetResourceSummary();
            showAlert(Alert.AlertType.ERROR, "启动失败：" + ex.getMessage());
            setStatus("启动失败: " + ex.getMessage());
            return;
        }

        TraceServer readyServer = monitorServer;
        readyServer.setOnReady(() -> Platform.runLater(() -> {
            // A late READY from a stopped/replaced Collector must not revive the prior UI.
            if (targetExitDetected.get() || traceServer != readyServer) return;
            if (collectorReady) {
                setStatus("Agent 已重连，继续监控");
                return;
            }
            collectorReady = true;
            if (monitoringSession != null) {
                classSelectionPane.loadActualEnhancementResult(
                        monitoringSession.agentLogsDirectory().resolve("enhancement-result.json"));
                loadSessionResource(monitoringSession, readyServer);
            }
            showProgress(false);
            pauseBtn.setDisable(false);
            setStatusDot(true);
            setAgentConnectionState("状态: 已连接", "agent-connection-connected");
            monitorStartMs = System.currentTimeMillis();
            elapsedTimeline = new Timeline(new KeyFrame(Duration.seconds(1), ev -> {
                long sec = (System.currentTimeMillis() - monitorStartMs) / 1000;
                elapsedLabel.setText(String.format("⊕ 已运行 %02d:%02d:%02d",
                        sec / 3600, (sec % 3600) / 60, sec % 60));
            }));
            elapsedTimeline.setCycleCount(Timeline.INDEFINITE);
            elapsedTimeline.play();
            setStatus("监控中");
        }));

        cancelRequested = false;
        startBtn.setDisable(true);
        stopBtn.setDisable(false);
        setStopButtonVisible(true);
        pauseBtn.setDisable(true);
        showProgress(true);
        setStatus("正在注入 agent，请稍候...");

        final TraceServer ts = traceServer;
        final MonitoringSession session = monitoringSession;
        Thread t = new Thread(() -> {
            try {
                JvmService.attachAgent(target.getPid(), config, actualPort, session);
                Platform.runLater(() -> {
                    if (targetExitDetected.get() || traceServer != ts) return;
                    if (cancelRequested) {
                        stopMonitoringInternal(ts);
                        return;
                    }
                    monitoring = true;
                    refreshSelectionSummary();
                    stopBtn.setDisable(false);
                    setStatus("插桩中，正在扫描类文件...");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (targetExitDetected.get() || traceServer != ts) return;
                    ts.stop();
                    traceServer = null;
                    setAgentConnectionState("状态: 未连接", "agent-connection-offline");
                    resetResourceSummary();
                    startBtn.setDisable(false);
                    stopBtn.setDisable(true);
                    setStopButtonVisible(false);
                    pauseBtn.setDisable(true);
                    showProgress(false);
                    String detail = ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
                    showAlert(Alert.AlertType.ERROR, "启动失败：" + detail);
                    setStatus("启动失败: " + detail);
                });
            }
        }, "attach-agent");
        t.setDaemon(true);
        t.start();
    }

    private void stopMonitoring() {
        if (!monitoring) {
            if (!cancelRequested) {
                cancelRequested = true;
                stopBtn.setDisable(true);
                setStatus("正在取消，等待 agent 完成扫描...");
            }
            return;
        }
        monitoring = false;
        paused = false;
        TraceServer ts = traceServer;
        stopMonitoringInternal(ts);
    }

    private void stopMonitoringInternal(TraceServer ts) {
        startBtn.setDisable(true);
        stopBtn.setDisable(true);
        pauseBtn.setDisable(true);
        showProgress(true);
        setStatus("正在还原字节码，请稍候...");
        Thread t = new Thread(() -> {
            TraceServer.StopResult result = ts == null
                    ? TraceServer.StopResult.notConfirmed("Collector 不可用，无法确认 Agent 状态")
                    : ts.stopAndAwaitRestore();
            MonitoringSession session = monitoringSession;
            SessionRetentionManager.CleanupResult cleanupResult = result.restored() && session != null
                    ? new SessionRetentionManager().pruneCompletedSessions(session, monitoringSessionRetentionPolicy)
                    : new SessionRetentionManager.CleanupResult(List.of(), List.of());
            for (String failure : cleanupResult.failures()) {
                AppLog.warn("[agent-monitor] session retention cleanup skipped: " + failure);
            }
            Platform.runLater(() -> {
                if (traceServer == ts) traceServer = null;
                stopBtn.setDisable(true);
                setStopButtonVisible(false);
                showProgress(false);
                setStatusDot(false);
                setAgentConnectionState("状态: 未连接", "agent-connection-offline");
                if (result.restored()) {
                    collectorReady = false;
                    refreshSelectionSummary();
                    startBtn.setDisable(false);
                    lastSessionStopMessage = result.message();
                    lastSessionOutputDrained = result.outputDrained();
                    lastSessionCaptureQuality = result.captureQuality();
                    sessionBtn.setDisable(session == null);
                    String cleanupSummary = cleanupResult.deletedCount() == 0 ? ""
                            : "；已清理 " + cleanupResult.deletedCount() + " 个旧会话";
                    if (result.outputDrained()) {
                        setStatus("已停止监控，字节码已还原；" + result.captureQuality().summary() + cleanupSummary);
                    } else {
                        setStatus("已停止监控，字节码已还原；尾部输出未完全 drain；"
                                + result.captureQuality().summary() + cleanupSummary);
                    }
                    showSessionActionsDialog(session, result.outputDrained(), result.message(),
                            result.captureQuality(), cleanupResult);
                } else {
                    startBtn.setDisable(true);
                    String detail = result.message();
                    showAlert(Alert.AlertType.ERROR, "停止监控未能确认字节码已还原：" + detail
                            + "\n\n为避免残留增强影响目标应用，请重启原始服务后再监控。");
                    setStatus("停止未确认，需重启目标服务: " + detail);
                }
            });
        }, "stop-monitoring");
        t.setDaemon(true);
        t.start();
    }

    private void togglePause() {
        if (!monitoring) return;
        paused = !paused;
        if (paused) {
            traceServer.setOnRootNode(node -> {});
            pauseBtn.setText("▶ 恢复输出");
            setStatus("已暂停输出（agent 持续运行）");
        } else {
            traceServer.setOnRootNode(callTreePane::addRootNode);
            pauseBtn.setText("⏸ 暂停输出");
            setStatus("监控中");
        }
    }

    public void onWindowClose() {
        controllerClosed = true;
        closeOutputDialog();
        if (metricsTimeline != null) {
            metricsTimeline.stop();
            metricsTimeline = null;
        }
        jvmMetricsService.close();
        monitoring = false;
        TraceServer currentTraceServer = traceServer;
        traceServer = null;
        if (currentTraceServer != null) {
            TraceServer.StopResult result = currentTraceServer.stopAndAwaitRestore();
            if (!result.restored()) {
                AppLog.warn("[agent-monitor] window close could not confirm bytecode restore: " + result.message());
            }
        }
    }

    /**
     * JMX can be temporarily unavailable while a live JVM is starting or under load, so use the
     * operating-system process lifecycle as the authoritative signal for returning to selection.
     */
    private void watchTargetProcessExit() {
        try {
            long pid = Long.parseLong(target.getPid());
            java.util.Optional<ProcessHandle> process = ProcessHandle.of(pid);
            if (process.isEmpty()) {
                onTargetProcessExited();
                return;
            }
            process.get().onExit().thenRun(this::onTargetProcessExited);
        } catch (NumberFormatException error) {
            AppLog.warn("[agent-monitor] cannot watch target process pid=" + target.getPid());
        }
    }

    private void onTargetProcessExited() {
        if (!targetExitDetected.compareAndSet(false, true)) return;
        AppLog.info("[agent-monitor] target JVM exited pid=" + target.getPid());
        Platform.runLater(this::returnToTargetSelectionAfterExit);
    }

    /** Releases only local state: an exited JVM has no Agent left to restore. */
    private void returnToTargetSelectionAfterExit() {
        if (controllerClosed || !targetExitNavigationStarted.compareAndSet(false, true)) return;

        closeOutputDialog();
        monitoring = false;
        paused = false;
        cancelRequested = true;
        collectorReady = false;
        if (metricsTimeline != null) {
            metricsTimeline.stop();
            metricsTimeline = null;
        }
        if (elapsedTimeline != null) {
            elapsedTimeline.stop();
            elapsedTimeline = null;
        }
        if (selectionMetricsDelay != null) selectionMetricsDelay.stop();
        jvmMetricsService.close();

        TraceServer stoppedServer = traceServer;
        traceServer = null;
        if (stoppedServer != null) {
            Thread cleanup = new Thread(stoppedServer::stop, "stop-collector-after-target-exit");
            cleanup.setDaemon(true);
            cleanup.start();
        }
        setStatusDot(false);
        setAgentConnectionState("状态: 未连接", "agent-connection-offline");
        showProgress(false);
        setStatus("目标 JVM 已退出，请重新选择进程");
        if (onSwitchTarget != null) onSwitchTarget.run();
    }

    private void startMetricsPolling() {
        sampleJvmMetrics();
        metricsTimeline = new Timeline(new KeyFrame(Duration.seconds(5), event -> sampleJvmMetrics()));
        metricsTimeline.setCycleCount(Timeline.INDEFINITE);
        metricsTimeline.play();
    }

    private void sampleJvmMetrics() {
        if (!metricsSampling.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                JvmMetrics metrics = jvmMetricsService.sample();
                Platform.runLater(() -> updateJvmMetrics(metrics));
            } finally {
                metricsSampling.set(false);
            }
        }, "jvm-metrics-sampler");
        t.setDaemon(true);
        t.start();
    }

    private void updateJvmMetrics(JvmMetrics metrics) {
        jvmMetricsPane.update(metrics);
        if (metrics == null || !metrics.available()) {
            heapChipLabel.setText("Heap --");
            threadChipLabel.setText("Threads --");
            gcChipLabel.setText("GC --");
            return;
        }
        heapChipLabel.setText("Heap " + formatBytes(metrics.heapUsed()) + " / "
                + formatBytes(metrics.heapMax() > 0 ? metrics.heapMax() : metrics.heapCommitted()));
        threadChipLabel.setText("Threads " + metrics.threadCount());
        gcChipLabel.setText("GC " + metrics.gcCount() + " / " + metrics.gcTimeMillis() + "ms");
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0MB";
        double mb = bytes / 1024.0 / 1024.0;
        if (mb < 1024) return String.format("%.0fMB", mb);
        return String.format("%.2fGB", mb / 1024.0);
    }

    private void showProgress(boolean visible) {
        showProgress(visible, "请稍候...");
    }

    private void showProgress(boolean visible, String message) {
        if (loadingLabel == null || loadingOverlay == null) return;
        loadingLabel.setText(message == null || message.isBlank() ? "请稍候..." : message);
        loadingOverlay.setVisible(visible);
    }

    private void setStopButtonVisible(boolean visible) {
        stopBtn.setVisible(visible);
        stopBtn.setManaged(visible);
    }

    public StackPane getRoot() { return rootWithOverlay; }

    private void setStatus(String msg) {
        statusLabel.setText(msg);
    }

    /** Establishes one model before the asynchronous class index begins driving the preview. */
    private void initializeDefaultConfiguration() {
        MonitoringConfig defaults = MonitoringConfig.defaults();
        MonitoringConfig.Scope scope = new MonitoringConfig.Scope(List.of("com.example"), List.of(),
                defaults.scope().excludeConditions(), defaults.scope().excludeMethods());
        applyYamlConfigurationToPanels(defaults.withScope(scope));
        setYamlPreview(currentConfig);
    }

    private void refreshYamlPreview() {
        refreshYamlPreview(false);
    }

    private void refreshYamlPreview(boolean force) {
        if (yamlDirty && !force) return;
        MonitoringConfig base = currentConfig == null ? MonitoringConfig.defaults() : currentConfig;
        List<String> includedPackages = classSelectionPane.getIncludedPackages();
        List<String> includedClasses = classSelectionPane.getIncludedClasses();
        if (includedPackages.isEmpty() && includedClasses.isEmpty()) {
            includedPackages = base.scope().includePackages().isEmpty() ? List.of("com.example")
                    : base.scope().includePackages();
        }
        currentConfig = base.withScope(new MonitoringConfig.Scope(includedPackages, includedClasses,
                classSelectionPane.getExcludedClassConditionList(), classSelectionPane.getExcludedMethods()));
        setYamlPreview(currentConfig);
    }

    private void refreshSelectionSummary() {
        applySelectionSummary(metricsForCurrentConfiguration());
    }

    private void scheduleSelectionSummaryRefresh() {
        long generation = ++selectionMetricsGeneration;
        if (selectionMetricsDelay == null) {
            selectionMetricsDelay = new PauseTransition(Duration.millis(120));
        }
        selectionMetricsDelay.stop();
        selectionMetricsDelay.setOnFinished(event -> classSelectionPane.getSelectionMetricsAsync(metrics -> {
            if (generation == selectionMetricsGeneration) applySelectionSummary(metrics);
        }));
        selectionMetricsDelay.playFromStart();
    }

    private void applySelectionSummary(ClassSelectionPane.SelectionMetrics metrics) {
        long classes = metrics.enhancedClasses();
        long methods = metrics.enhancedMethods();
        if (!monitoring) {
            enhancementScopeLabel.setText("增强范围 · 待执行");
            enhancementStateLabel.setText("未 attach");
            enhancementStateLabel.getStyleClass().removeAll("enhancement-state-ready", "enhancement-state-warning",
                    "enhancement-state-pending");
        }
        enhancedClassLabel.setText(formatCount(classes));
        enhancedMethodLabel.setText(formatCount(methods));
        updateImpactIndicators(classes, methods);
        if (classes > MAX_ENHANCED_CLASSES) {
            setStatus("选择范围过大: " + classes + " 个类，请缩小到 " + MAX_ENHANCED_CLASSES + " 以内");
            startBtn.setDisable(true);
        } else if (!monitoring && traceServer == null) {
            startBtn.setDisable(false);
        }
    }

    private static String formatCount(long value) {
        return String.format("%,d", value);
    }

    /**
     * Estimates instrumentation impact from the active enhancement footprint.  It is deliberately
     * presented as a range: actual process consumption remains available in the JVM metrics tab.
     */
    private void updateImpactIndicators(long classes, long methods) {
        boolean attached = monitoring || collectorReady;
        if (!attached) {
            cpuOverheadLabel.setText("0");
            memoryOverheadLabel.setText("0");
            riskLevelLabel.setText("--");
            setImpactRiskStyle("summary-risk-neutral");
            return;
        }

        boolean low = classes < 20 && methods < 200;
        boolean medium = !low && classes < 80 && methods < 800;
        cpuOverheadLabel.setText(low ? "低 · +1%–3%" : medium ? "中 · +3%–8%" : "高 · >8%");
        memoryOverheadLabel.setText(low ? "低 · +10 MB" : medium ? "中 · +10–40 MB" : "高 · >40 MB");
        riskLevelLabel.setText(low ? "低" : medium ? "中" : "高");
        setImpactRiskStyle(low ? "summary-risk-low" : medium ? "summary-risk-mid" : "summary-risk-high");
    }

    private void setImpactRiskStyle(String styleClass) {
        riskLevelLabel.getStyleClass().removeAll("summary-risk-low", "summary-risk-mid", "summary-risk-high",
                "summary-risk-neutral");
        riskLevelLabel.getStyleClass().add(styleClass);
    }

    private ClassSelectionPane.SelectionMetrics metricsForCurrentConfiguration() {
        try {
            MonitoringConfig config = MonitoringConfigYaml.parse(yamlPreview.getText());
            return classSelectionPane.metricsForConfiguration(config.scope().includePackages(),
                    config.scope().includeClasses(), config.scope().excludeConditions(), config.scope().excludeMethods());
        } catch (IllegalArgumentException ignored) {
            // During initial asynchronous loading there may not yet be a generated preview.
            return classSelectionPane.getSelectionMetrics();
        }
    }

    private static AnalysisConfig toAnalysisConfig(MonitoringConfig.Analysis analysis) {
        return new AnalysisConfig(analysis.enabled(), analysis.slowTraceThresholdMs(), analysis.slowSpanThresholdMs(),
                analysis.slowSelfTimeThresholdMs(), analysis.includeErrorTrace(),
                analysis.maxTracesPerRootMethod(), analysis.maxBottlenecksPerTrace(), analysis.maxDepth());
    }

    private static String displayTargetName(String displayName) {
        String value = displayName == null ? "" : displayName.trim();
        int split = value.indexOf("  |  ");
        if (split > 0) value = value.substring(0, split).trim();
        for (String token : value.split("\\s+")) {
            if (token.endsWith(".jar")) {
                int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
                return slash >= 0 ? token.substring(slash + 1) : token;
            }
        }
        for (String token : value.split("\\s+")) {
            if (token.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) {
                return token;
            }
        }
        return value.length() > 64 ? value.substring(0, 61) + "..." : value;
    }

    private void saveYamlConfig() {
        if (!yamlDirty) refreshYamlPreview(true);
        FileChooser chooser = new FileChooser();
        chooser.setTitle("保存配置 YAML");
        chooser.setInitialFileName("agent-monitor.yml");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML", "*.yml", "*.yaml"));
        java.io.File file = chooser.showSaveDialog(root.getScene().getWindow());
        if (file == null) return;
        try {
            Files.writeString(file.toPath(), yamlPreview.getText());
            yamlDirty = false;
            setStatus("配置已保存: " + file.getName());
        } catch (Exception ex) {
            showAlert(Alert.AlertType.ERROR, "保存配置失败：" + ex.getMessage());
        }
    }

    private void importYamlConfig() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导入配置 YAML");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("YAML", "*.yml", "*.yaml"));
        java.io.File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file == null) return;
        setYamlApplyBusy(true, "正在导入配置，请稍候...");
        setStatus("正在导入配置...");
        PauseTransition renderDelay = new PauseTransition(Duration.millis(80));
        renderDelay.setOnFinished(ignored -> startImportYamlConfig(file));
        renderDelay.play();
    }

    private void startImportYamlConfig(java.io.File file) {
        Thread t = new Thread(() -> {
            try {
                String yaml = Files.readString(file.toPath());
                MonitoringConfig config = MonitoringConfigYaml.parse(yaml);
                Platform.runLater(() -> applyYamlConfigurationAsync(config, "配置已导入: " + file.getName()));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setYamlApplyBusy(false, "");
                    showAlert(Alert.AlertType.ERROR, "导入配置失败：" + ex.getMessage());
                });
            }
        }, "import-yaml-config");
        t.setDaemon(true);
        t.start();
    }

    /** Opens a completed capture without starting a Collector or touching the selected JVM. */
    private void openHistoricalReplay() {
        if (traceServer != null || monitoring) {
            showAlert(Alert.AlertType.WARNING, "请先停止当前监控，再打开历史会话。\n\n"
                    + "离线回放只读取文件，不会与正在运行的 Agent 混用。");
            return;
        }
        if (offlineReplayLoading) return;

        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择历史会话目录或 spans 目录");
        if (monitoringSession != null && Files.isDirectory(monitoringSession.sessionRootDirectory())) {
            chooser.setInitialDirectory(monitoringSession.sessionRootDirectory().toFile());
        }
        java.io.File selectedDirectory = chooser.showDialog(root.getScene().getWindow());
        if (selectedDirectory == null) return;

        offlineReplayLoading = true;
        offlineReplayProgressMessage = "正在读取历史 Span 并重建调用链，请稍候...";
        replayBtn.setDisable(true);
        refreshBlockingOverlay(offlineReplayProgressMessage);
        setStatus("正在加载历史会话...");

        Thread loader = new Thread(() -> {
            try {
                OfflineTraceReplayLoader.ReplayResult result = offlineTraceReplayLoader.load(selectedDirectory.toPath());
                Platform.runLater(() -> completeHistoricalReplay(result));
            } catch (Exception error) {
                Platform.runLater(() -> failHistoricalReplay(error));
            }
        }, "agent-monitor-offline-replay");
        loader.setDaemon(true);
        loader.start();
    }

    private void completeHistoricalReplay(OfflineTraceReplayLoader.ReplayResult result) {
        if (controllerClosed) return;
        offlineReplayLoading = false;
        offlineReplayProgressMessage = "";
        replayBtn.setDisable(false);
        refreshBlockingOverlay("");
        callTreePane.showOfflineReplay(result.roots());
        showOutputDialog();
        String quality = result.hasDegradation()
                ? "；跳过 " + result.skippedSpans() + " 个，孤儿 " + result.orphanSpans()
                        + " 个，循环 " + result.cyclicSpans() + " 个"
                : "";
        setStatus("已离线回放 " + result.spanFileCount() + " 个 Span 文件、" + result.acceptedSpans()
                + " 个 Span、" + result.roots().size() + " 条调用链" + quality);
    }

    private void failHistoricalReplay(Exception error) {
        if (controllerClosed) return;
        offlineReplayLoading = false;
        offlineReplayProgressMessage = "";
        replayBtn.setDisable(false);
        refreshBlockingOverlay("");
        String detail = error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName() : error.getMessage();
        showAlert(Alert.AlertType.ERROR, "历史会话加载失败：" + detail);
        setStatus("历史会话加载失败: " + detail);
    }

    private void applyYamlTextAsync(String yaml, String successMessage) {
        setYamlApplyBusy(true, "正在应用 YAML 配置，请稍候...");
        Thread parser = new Thread(() -> {
            try {
                MonitoringConfig config = MonitoringConfigYaml.parse(yaml);
                Platform.runLater(() -> applyYamlConfigurationAsync(config, successMessage));
            } catch (Exception error) {
                Platform.runLater(() -> {
                    setYamlApplyBusy(false, "");
                    showAlert(Alert.AlertType.ERROR, "YAML 配置无效：" + error.getMessage());
                });
            }
        }, "parse-yaml-config");
        parser.setDaemon(true);
        parser.start();
    }

    private void applyYamlConfigurationAsync(MonitoringConfig config, String successMessage) {
        setYamlApplyBusy(true, "正在同步类选择，请稍候...");
        try {
            currentConfig = config;
            yamlDirty = true;
            classSelectionPane.applyConfigurationAsync(config.scope().includePackages(),
                    config.scope().includeClasses(), config.scope().excludeConditions(), config.scope().excludeMethods(), () -> {
                        showProgress(true, "正在写入配置并计算影响...");
                        setYamlPreview(config);
                        setStatus(successMessage);
                        setYamlApplyBusy(false, "");
                    });
        } catch (RuntimeException error) {
            setYamlApplyBusy(false, "");
            showAlert(Alert.AlertType.ERROR, "同步 YAML 配置失败：" + error.getMessage());
        }
    }

    private void setYamlApplyBusy(boolean busy, String message) {
        yamlApplyBusy = busy;
        refreshBlockingOverlay(message);
    }

    private void refreshBlockingOverlay(String message) {
        boolean visible = classIndexLoading || yamlApplyBusy || offlineReplayLoading;
        String displayMessage = offlineReplayLoading ? offlineReplayProgressMessage : message;
        showProgress(visible, visible ? displayMessage : "");
    }

    /** Applies the one validated configuration to both visual selection surfaces. */
    private void applyYamlConfigurationToPanels(MonitoringConfig config) {
        currentConfig = config;
        boolean wasDirty = yamlDirty;
        yamlDirty = true;
        try {
            classSelectionPane.applyConfiguration(config.scope().includePackages(),
                    config.scope().includeClasses(), config.scope().excludeConditions(), config.scope().excludeMethods());
        } finally {
            yamlDirty = wasDirty;
        }
        refreshSelectionSummary();
    }

    private void setYamlPreview(MonitoringConfig config) {
        currentConfig = config;
        updatingYaml = true;
        try {
            yamlPreview.setText(MonitoringConfigYaml.write(config));
            yamlDirty = false;
        } finally {
            updatingYaml = false;
        }
    }

    private void showOutputDialog() {
        if (outputDialog != null && outputDialog.isShowing()) {
            outputDialog.getDialogPane().getScene().getWindow().requestFocus();
            return;
        }

        Dialog<Void> dialog = new Dialog<>();
        outputDialog = dialog;
        dialog.setTitle("监控输出结果");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(callTreePane);
        dialog.getDialogPane().setPrefSize(980, 640);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        if (root.getScene() != null && root.getScene().getWindow() != null) {
            dialog.initOwner(root.getScene().getWindow());
        }
        dialog.getDialogPane().getStylesheets().addAll(root.getScene() != null
                ? root.getScene().getStylesheets()
                : java.util.List.of());
        dialog.setOnHidden(event -> {
            if (outputDialog == dialog) outputDialog = null;
        });
        dialog.show();
    }

    private void closeOutputDialog() {
        Dialog<Void> dialog = outputDialog;
        outputDialog = null;
        if (dialog != null && dialog.isShowing()) dialog.close();
    }

    private void showLogDialog() {
        LogViewerDialog dialog = new LogViewerDialog(root.getScene().getWindow(),
                root.getScene() != null ? root.getScene().getStylesheets() : java.util.List.of(),
                monitoringSession == null ? null : monitoringSession.agentLogsDirectory(), AppLog.file());
        dialog.show();
    }

    /** Offers the three useful handoff actions without implying a failed STOP produced a final report. */
    private void showSessionActionsDialog(MonitoringSession session, boolean outputDrained,
                                          String completionMessage,
                                          CaptureQuality captureQuality,
                                          SessionRetentionManager.CleanupResult cleanupResult) {
        if (session == null) return;
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("本次监控会话");
        dialog.setHeaderText(outputDrained ? "监控已停止，可开始分析本次会话" : "监控已停止，但尾部输出不完整");

        Label description = new Label(outputDrained
                ? "字节码已还原，最终报告已在停止流程中写入。"
                : "字节码已还原，可以开始下一轮监控；但 Agent 的尾部输出未完全 drain，报告可能缺少最后一小段数据。");
        description.setWrapText(true);
        description.getStyleClass().add("hint-label");

        Label location = new Label("会话目录：" + session.sessionDirectory().toAbsolutePath().normalize());
        location.setWrapText(true);
        location.getStyleClass().add("hint-label");

        Label feedback = new Label(sessionActionFeedback(completionMessage, captureQuality, cleanupResult));
        feedback.setWrapText(true);
        feedback.getStyleClass().add("hint-label");

        Button reportButton = new Button("打开最终报告");
        reportButton.getStyleClass().add("ghost-btn");
        reportButton.setOnAction(event -> openSessionArtifact(session.performanceReportPath(), feedback));

        Button directoryButton = new Button("打开会话目录");
        directoryButton.getStyleClass().add("ghost-btn");
        directoryButton.setOnAction(event -> openSessionArtifact(session.sessionDirectory(), feedback));

        Button copyPromptButton = new Button("复制 AI 分析提示词");
        copyPromptButton.getStyleClass().add("ghost-btn");
        copyPromptButton.setOnAction(event -> copySessionAnalysisPrompt(session, feedback));

        HBox actions = new HBox(8, reportButton, directoryButton, copyPromptButton);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox content = new VBox(12, description, location, actions, feedback);
        content.setPrefWidth(620);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStylesheets().addAll(root.getScene() != null
                ? root.getScene().getStylesheets()
                : java.util.List.of());
        dialog.show();
    }

    private void openSessionArtifact(java.nio.file.Path artifact, Label feedback) {
        SessionArtifactOpener.OpenResult result = SessionArtifactOpener.open(artifact);
        String message = result.opened() ? result.message() : "未能打开：" + result.message();
        feedback.setText(message);
        setStatus(message);
    }

    private void copySessionAnalysisPrompt(MonitoringSession session, Label feedback) {
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(SessionAnalysisPrompt.forSession(session));
            if (Clipboard.getSystemClipboard().setContent(content)) {
                feedback.setText("AI 分析提示词已复制；将会话目录或报告文件提供给 AI 即可。");
                setStatus("AI 分析提示词已复制到剪贴板");
            } else {
                feedback.setText("未能写入系统剪贴板，请手动复制会话目录。");
                setStatus("未能写入系统剪贴板");
            }
        } catch (IllegalStateException error) {
            feedback.setText("未能写入系统剪贴板：" + error.getMessage());
            setStatus("未能写入系统剪贴板");
        }
    }

    private static String sessionActionFeedback(String completionMessage, CaptureQuality captureQuality,
                                                SessionRetentionManager.CleanupResult cleanupResult) {
        String detail = completionMessage == null || completionMessage.isBlank()
                ? "" : "停止详情：" + completionMessage;
        String quality = captureQuality == null ? "" : "采集质量：" + captureQuality.summary();
        if (!quality.isBlank()) detail = detail.isBlank() ? quality : detail + "\n" + quality;
        if (cleanupResult == null || cleanupResult.deletedCount() == 0) return detail;
        String cleanup = "已按显式保留规则清理 " + cleanupResult.deletedCount() + " 个旧的已完成会话。";
        return detail.isBlank() ? cleanup : detail + "\n" + cleanup;
    }

    private void setStatusDot(boolean active) {
        statusDotLabel.getStyleClass().removeAll("status-dot-active", "status-dot-inactive");
        statusDotLabel.getStyleClass().add(active ? "status-dot-active" : "status-dot-inactive");
        statusDotLabel.setText(active ? "● 监控中" : "● 未运行");
        if (!active) {
            if (elapsedTimeline != null) { elapsedTimeline.stop(); elapsedTimeline = null; }
            elapsedLabel.setText("");
        }
    }

    private void showAlert(Alert.AlertType type, String msg) {
        Alert alert = new Alert(type);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(msg);
        alert.showAndWait();
    }

}
