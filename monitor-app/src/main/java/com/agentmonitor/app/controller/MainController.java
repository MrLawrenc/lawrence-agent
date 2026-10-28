package com.agentmonitor.app.controller;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.agentmonitor.model.jvm.JvmMetrics;
import com.agentmonitor.model.jvm.JvmProcess;
import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.model.SessionResource;
import com.agentmonitor.core.config.MonitoringConfigYaml;
import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.core.report.SessionAnalysisPrompt;
import com.agentmonitor.model.report.CaptureQuality;
import com.agentmonitor.core.service.JvmMetricsService;
import com.agentmonitor.core.service.JvmService;
import com.agentmonitor.core.service.OfflineTraceReplayLoader;
import com.agentmonitor.core.service.SessionRetentionManager;
import com.agentmonitor.core.service.SessionResourceLoader;
import com.agentmonitor.core.service.TraceServer;
import com.agentmonitor.core.service.MonitoringRuntime;
import com.agentmonitor.core.util.AppLog;
import com.agentmonitor.app.util.SessionArtifactOpener;
import com.agentmonitor.app.ui.CallTreePane;
import com.agentmonitor.app.ui.ClassSelectionPane;
import com.agentmonitor.app.ui.JvmMetricsPane;
import com.agentmonitor.app.ui.LogViewerDialog;
import com.agentmonitor.app.ui.UiIcons;
import com.agentmonitor.app.ui.WorkbenchPane;
import com.agentmonitor.app.ui.DiagnosticsPane;

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
    private final Label enhancementScopeLabel = new Label("全局范围 · 规则匹配，尚未增强");
    private final Label enhancedClassLabel = new Label("0");
    private final Label enhancedMethodLabel = new Label("0");
    private final Label enhancementStateLabel = new Label("配置范围");
    private final Button actualEnhancementDetailButton = new Button("查看增强结果");
    private final Label impactStatusLabel = new Label("性能影响尚未实测");
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
    /** Pages share the selected target and are disposed with its controller. */
    private WorkbenchPane workbench;
    private DiagnosticsPane diagnosticsPane;

    private final JvmProcess target;
    private Runnable onSwitchTarget;
    private volatile MonitoringRuntime traceServer;
    private final java.util.Map<MonitoringRuntime, Thread> shutdownHooks = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile MonitoringSession monitoringSession;
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
                scheduleSelectionSummaryRefresh();
            }
        });
        classSelectionPane.setOnProgressChanged(message -> {
            if (classIndexLoading || yamlApplyBusy) showProgress(true, message);
            setStatus(message);
        });
        classSelectionPane.setOnActualEnhancementResultChanged(metrics -> {
            enhancementScopeLabel.setText("本次挂载 · 实际增强结果");
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
        statusDotLabel  = new Label("未运行");
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
        startBtn     = new Button("开始监控");
        stopBtn      = new Button("停止监控");
        pauseBtn     = new Button("暂停输出");
        refreshBtn   = new Button("刷新范围");
        logBtn       = new Button("运行日志");
        importBtn    = new Button("导入配置");
        saveBtn      = new Button("保存配置");
        sessionBtn   = new Button("本次会话");
        sessionBtn.getStyleClass().add("ghost-btn");
        sessionBtn.setDisable(true);
        sessionBtn.setOnAction(e -> showSessionResults(monitoringSession,
                lastSessionOutputDrained, lastSessionStopMessage,
                lastSessionCaptureQuality,
                new SessionRetentionManager.CleanupResult(List.of(), List.of())));
        replayBtn = new Button("历史会话");
        replayBtn.getStyleClass().add("ghost-btn");
        replayBtn.setOnAction(e -> openHistoricalReplay());

        spinner = new ProgressIndicator(-1);
        spinner.getStyleClass().add("loading-spinner");
        spinner.setMinSize(26, 26);
        spinner.setMaxSize(26, 26);

        loadingLabel = new Label("请稍候...");
        loadingLabel.getStyleClass().add("loading-label");

        loadingLabel.setWrapText(true);
        loadingLabel.setMaxWidth(340);
        HBox spinnerBox = new HBox(14, spinner, loadingLabel);
        spinnerBox.getStyleClass().add("loading-panel");
        spinnerBox.setMinWidth(280);
        spinnerBox.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        spinnerBox.setAlignment(Pos.CENTER);

        loadingOverlay = new StackPane(spinnerBox);
        loadingOverlay.getStyleClass().add("loading-overlay");
        loadingOverlay.setVisible(false);
        loadingOverlay.setMouseTransparent(false);

        root = new BorderPane();
        root.getStyleClass().add("root-pane");
        buildActionBar();
        root.setTop(buildToolbar());
        root.setCenter(buildMainPanel());
        root.setBottom(buildStatusBar());
        initializeDefaultConfiguration();

        workbench.setOverlay(loadingOverlay);
        rootWithOverlay = new StackPane(root);
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
        StackPane icon = UiIcons.icon("monitor");
        icon.getStyleClass().add("brand-icon");

        Label appTitle = new Label("Agent Monitor");
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
        switchBtn.setId("capture-select-target");
        switchBtn.getStyleClass().add("ghost-btn");
        UiIcons.decorate(switchBtn, "switch");
        switchBtn.setOnAction(e -> {
            onWindowClose();
            if (onSwitchTarget != null) onSwitchTarget.run();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox identity = new VBox(3, resourceServiceLabel, resourceRuntimeLabel);
        identity.setMinWidth(0);
        UiIcons.decorate(refreshBtn, "refresh");
        UiIcons.decorate(logBtn, "file");
        UiIcons.decorate(importBtn, "download");
        UiIcons.decorate(saveBtn, "save");
        UiIcons.decorate(replayBtn, "folder");
        UiIcons.decorate(sessionBtn, "folder");

        elapsedLabel.managedProperty().bind(elapsedLabel.textProperty().isNotEmpty());
        elapsedLabel.visibleProperty().bind(elapsedLabel.managedProperty());
        VBox captureState = new VBox(3, statusDotLabel, elapsedLabel);
        captureState.setAlignment(Pos.CENTER_RIGHT);
        startBtn.setId("capture-start"); stopBtn.setId("capture-stop");
        switchBtn.disableProperty().bind(startBtn.disabledProperty().and(stopBtn.visibleProperty()));
        ToolBar toolbar = new ToolBar(icon, appTitle, new Separator(), identity,
                resourceEnvironmentLabel, spacer, switchBtn, new Separator(), captureState, startBtn, stopBtn);
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
        setResourceTooltips("目标 JVM（应用配置后将以 resource.json 的实际身份更新）\nPID: " + target.getPid()+"\n"+target.getDisplayName());
    }

    private void markResourceAwaitingAgent() {
        resourceRuntimeLabel.setText("JVM: 等待 Agent 确认");
        setResourceTooltips("正在等待本次 attach 的 resource.json。\nPID: " + target.getPid());
    }

    /** Reads the collector-written file away from JavaFX's event thread once the Agent is ready. */
    private void loadSessionResource(MonitoringSession session, MonitoringRuntime expectedServer) {
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

    private WorkbenchPane buildMainPanel() {
        workbench = new WorkbenchPane();
        workbench.addPage("scope", "采集配置", "选择业务包与类，明确本次要观察的范围。", "scope", () -> {
            Button advanced = new Button("高级配置");
            UiIcons.decorate(advanced, "edit");
            advanced.setOnAction(event -> showAdvancedConfiguration());
            workbench.setPageActions("scope", WorkbenchPane.actions(refreshBtn, importBtn, saveBtn, advanced));
            return classSelectionPane;
        });
        workbench.addPage("calls", "调用链", "搜索方法或线程，选择调用查看参数、SQL 与异常。", "trace", () -> callTreePane);
        workbench.addPage("overview", "JVM 概览", "目标进程的实时指标，与本次采集范围分别呈现。", "monitor", () -> {
            VBox summary = buildConfigSummaryPanel();
            summary.setMinWidth(260); summary.setPrefWidth(340); summary.setMaxHeight(Region.USE_PREF_SIZE);
            HBox overview = new HBox(24, jvmMetricsPane, summary);
            HBox.setHgrow(jvmMetricsPane, Priority.ALWAYS);
            overview.getStyleClass().add("overview-layout");
            ScrollPane scroll = new ScrollPane(overview); scroll.setFitToWidth(true);
            return scroll;
        });
        workbench.addPage("diagnostics", "诊断分析", "从指标、线程和依赖证据逐步定位问题。", "search", () -> {
            diagnosticsPane = new DiagnosticsPane(target.getPid(), () -> monitoringSession, () -> traceServer);
            return diagnosticsPane;
        });
        workbench.addPage("sessions", "会话与报告", "查看采集结果，或打开已有会话进行离线分析。", "folder", this::buildSessionEmptyState);
        workbench.addPage("advanced", "高级配置", "编辑完整 YAML；校验通过后才应用到下一次采集。", "edit", this::buildAdvancedEditor);
        workbench.addUtility(replayBtn, "folder");
        workbench.addUtility(logBtn, "file");
        workbench.selectPage("scope");
        return workbench;
    }

    private VBox buildSessionEmptyState() {
        Label title = new Label("采集完成后，在这里查看报告"); title.getStyleClass().add("empty-state-title");
        Label hint = new Label("停止采集并确认字节码还原后，可打开报告、会话目录或复制 AI 分析提示词。");
        hint.setWrapText(true); hint.setMaxWidth(520); hint.getStyleClass().add("empty-state-desc");
        Button history = new Button("打开历史会话"); history.setOnAction(event -> openHistoricalReplay());
        UiIcons.decorate(history, "folder");
        VBox empty = new VBox(16, UiIcons.icon("folder"), title, hint, history);
        empty.setAlignment(Pos.CENTER); empty.getStyleClass().add("workbench-empty");
        return empty;
    }

    private VBox buildAdvancedEditor() {
        var editor = new com.agentmonitor.app.ui.AdvancedConfigPane(() -> currentConfig, monitoring);
        Button apply = new Button("应用配置"); apply.getStyleClass().add("primary-btn");
        apply.setId("advanced-config-apply");
        apply.disableProperty().bind(editor.dirtyProperty().not());
        apply.setOnAction(event -> editor.validateConfiguration().ifPresent(config -> {
            if (applyYamlConfigurationAsync(config,
                monitoring ? "高级配置已保存，下一次采集生效" : "高级配置已应用，采集范围已同步")) editor.markApplied(config);
        }));
        Button back = new Button("返回采集范围"); back.setOnAction(event -> workbench.selectPage("scope"));
        VBox content = new VBox(12, editor, WorkbenchPane.actions(apply, back));
        VBox.setVgrow(editor, Priority.ALWAYS);
        return content;
    }

    private HBox buildMetricChips() {
        heapChipLabel.getStyleClass().addAll("metric-chip", "metric-chip-heap");
        threadChipLabel.getStyleClass().addAll("metric-chip", "metric-chip-thread");
        gcChipLabel.getStyleClass().addAll("metric-chip", "metric-chip-gc");
        HBox chips = new HBox(6, heapChipLabel, threadChipLabel, gcChipLabel);
        chips.setAlignment(Pos.CENTER_RIGHT);
        return chips;
    }

    private void showAdvancedConfiguration() {
        workbench.selectPage("advanced");
    }

    private VBox buildConfigSummaryPanel() {
        Label title = new Label("采集范围概览");
        Label classCaption = new Label("类");
        Label methodCaption = new Label("方法");
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
        impactStatusLabel.getStyleClass().add("summary-measurement-status");impactStatusLabel.setWrapText(true);
        impactStatusLabel.setTooltip(new Tooltip("类数和方法数不能换算成 CPU 或内存开销。\n真实开销需要在相同业务负载下对照测量；JVM 指标页显示进程总量。"));
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
        Region detailSpacer = new Region();
        HBox.setHgrow(detailSpacer, Priority.ALWAYS);
        HBox detailRow = new HBox(detailSpacer, actualEnhancementDetailButton);
        detailRow.setAlignment(Pos.CENTER_RIGHT);
        detailRow.getStyleClass().add("summary-detail-row");
        VBox summary = new VBox(8, header, enhancementScopeLabel, counts, impactStatusLabel, detailRow);
        summary.getStyleClass().addAll("selection-summary-card","runtime-summary");
        return summary;
    }

    private void buildActionBar() {
        startBtn.getStyleClass().addAll("action-btn", "start-btn");
        UiIcons.decorate(startBtn, "play");
        startBtn.setTooltip(new Tooltip("将当前配置应用到目标 JVM，开始采集调用链"));
        startBtn.setOnAction(e -> startMonitoring());

        stopBtn.getStyleClass().addAll("action-btn", "stop-btn");
        UiIcons.decorate(stopBtn, "stop");
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
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        statusLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(14, statusLabel, spacer, agentConnectionLabel, buildMetricChips());
        bar.getStyleClass().add("status-bar");
        bar.setAlignment(Pos.CENTER_LEFT);

        return bar;
    }

    private void startMonitoring() {
        if (classIndexLoading || yamlApplyBusy || offlineReplayLoading) {
            setStatus("正在更新范围或加载会话，请等待完成后再开始采集。");
            return;
        }
        if (classSelectionPane.hasPendingRuleEdits()) {
            showAlert(Alert.AlertType.WARNING, "请先应用或还原范围面板中的排除规则修改。"); return;
        }
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
        if (pkgs.isBlank() && config.scope().includeClasses().isEmpty()) {
            showAlert(Alert.AlertType.WARNING, "启动失败：YAML 中 includePackages 与 includeClasses 至少填写一项。\n\n请在左侧勾选包或类，或在 YAML 中填写目标范围。");
            setStatus("启动失败: 尚未选择要采集的包或类");
            return;
        }

        final MonitoringRuntime monitorServer;
        try {
            monitorServer = new MonitoringRuntime(target.getPid(), config);
            monitoringSession = monitorServer.session();
            Thread shutdown = new Thread(monitorServer::stopAndAwaitRestore, "desktop-restore-on-exit");
            shutdownHooks.put(monitorServer, shutdown);
            Runtime.getRuntime().addShutdownHook(shutdown);
        } catch (Exception error) {
            showAlert(Alert.AlertType.ERROR, "无法创建监控会话: " + error.getMessage());
            return;
        }
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
        workbench.replacePage("sessions", buildSessionEmptyState());
        traceServer = monitorServer;
        enhancementScopeLabel.setText("本次挂载 · 等待实际增强结果");
        enhancementStateLabel.setText("挂载中");enhancedClassLabel.setText("—");enhancedMethodLabel.setText("—");
        monitorServer.setOnRootNode(node -> callTreePane.addTrace(node, () -> !controllerClosed && traceServer == monitorServer));
        monitorServer.setOnStatusChange(msg -> Platform.runLater(() -> {
            if (targetExitDetected.get() || traceServer != monitorServer) return;
            if (msg.startsWith("Agent 已连接")) {
                setAgentConnectionState("状态: 初始化中", "agent-connection-connecting");
            } else if ("Agent 连接已断开".equals(msg)) {
                setAgentConnectionState("状态: 重连中", "agent-connection-connecting");
            }
            setStatus(msg);
        }));

        try {
            setAgentConnectionState("状态: 连接中", "agent-connection-connecting");
            markResourceAwaitingAgent();
            monitorServer.startCollector();
        } catch (Exception ex) {
            monitorServer.stop();
            removeShutdownHook(monitorServer);
            if (traceServer == monitorServer) traceServer = null;
            setAgentConnectionState("状态: 未连接", "agent-connection-offline");
            resetResourceSummary();
            showAlert(Alert.AlertType.ERROR, "启动失败：" + ex.getMessage());
            setStatus("启动失败: " + ex.getMessage());
            return;
        }

        MonitoringRuntime readyServer = monitorServer;
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
                elapsedLabel.setText(String.format("已运行 %02d:%02d:%02d",
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

        final MonitoringRuntime ts = traceServer;
        final MonitoringSession session = monitoringSession;
        Thread t = new Thread(() -> {
            try {
                ts.attach();
                ts.awaitReady(20, java.util.concurrent.TimeUnit.SECONDS);
                Platform.runLater(() -> {
                    if (targetExitDetected.get() || traceServer != ts) return;
                    if (cancelRequested) {
                        stopMonitoringInternal(ts);
                        return;
                    }
                    monitoring = true;
                    refreshSelectionSummary();
                    stopBtn.setDisable(false);
                    setStatus("监控中");
                });
            } catch (Exception ex) {
                TraceServer.StopResult cleanup = ts.stopAndAwaitRestore();
                removeShutdownHook(ts);
                Platform.runLater(() -> {
                    if (controllerClosed || targetExitDetected.get() || traceServer != ts) return;
                    if (!cleanup.restored()) AppLog.warn("挂载失败后的还原状态: " + cleanup.message());
                    traceServer = null;
                    setAgentConnectionState("状态: 未连接", "agent-connection-offline");
                    resetResourceSummary();
                    startBtn.setDisable(ts.wasAttachAttempted() && !cleanup.restored());
                    stopBtn.setDisable(true);
                    setStopButtonVisible(false);
                    pauseBtn.setDisable(true);
                    showProgress(false);
                    String detail = ex.getClass().getSimpleName()
                            + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
                    if (ts.wasAttachAttempted() && !cleanup.restored()) detail += "\n字节码还原未确认：" + cleanup.message() + "\n请重启目标 JVM 后再采集。";
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
        MonitoringRuntime ts = traceServer;
        stopMonitoringInternal(ts);
    }

    private void stopMonitoringInternal(MonitoringRuntime ts) {
        startBtn.setDisable(true);
        stopBtn.setDisable(true);
        pauseBtn.setDisable(true);
        showProgress(true);
        setStatus("正在还原字节码，请稍候...");
        final MonitoringSession session = ts == null ? monitoringSession : ts.session();
        Thread t = new Thread(() -> {
            TraceServer.StopResult result = ts == null
                    ? TraceServer.StopResult.notConfirmed("Collector 不可用，无法确认 Agent 状态")
                    : ts.stopAndAwaitRestore();
            if (ts != null) removeShutdownHook(ts);
            SessionRetentionManager.CleanupResult cleanupResult = ts == null
                    ? new SessionRetentionManager.CleanupResult(List.of(), List.of()) : ts.cleanupResult();
            for (String failure : cleanupResult.failures()) {
                AppLog.warn("[agent-monitor] session retention cleanup skipped: " + failure);
            }
            Platform.runLater(() -> {
                if (controllerClosed || targetExitDetected.get() || traceServer != ts) return;
                traceServer = null;
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
                        setStatus("已停止监控，字节码已还原；本次采集不完整；"
                                + result.captureQuality().summary() + cleanupSummary);
                    }
                    showSessionResults(session, result.outputDrained(), result.message(),
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
            pauseBtn.setText("恢复输出");
            setStatus("已暂停输出（agent 持续运行）");
        } else {
            MonitoringRuntime current = traceServer;
            traceServer.setOnRootNode(node -> callTreePane.addTrace(node, () -> !controllerClosed && traceServer == current));
            pauseBtn.setText("暂停输出");
            setStatus("监控中");
        }
    }

    public void onWindowClose() {
        if (controllerClosed) return;
        controllerClosed = true;
        if (diagnosticsPane != null) diagnosticsPane.close();
        classSelectionPane.dispose();
        if (metricsTimeline != null) {
            metricsTimeline.stop();
            metricsTimeline = null;
        }
        if (elapsedTimeline != null) elapsedTimeline.stop();
        if (selectionMetricsDelay != null) selectionMetricsDelay.stop();
        setStatusDot(false);
        closeMetricsAsync();
        monitoring = false;
        MonitoringRuntime currentTraceServer = traceServer;
        traceServer = null;
        if (currentTraceServer != null) {
            Thread cleanup = new Thread(() -> {
                TraceServer.StopResult result = currentTraceServer.stopAndAwaitRestore();
                removeShutdownHook(currentTraceServer);
                if (!result.restored()) AppLog.warn("窗口关闭后的还原状态: " + result.message());
            }, "desktop-close-monitoring");
            cleanup.setDaemon(false); cleanup.start();
        }
    }

    private void removeShutdownHook(MonitoringRuntime runtime) {
        Thread hook = shutdownHooks.remove(runtime);
        if (hook != null) {
            try { Runtime.getRuntime().removeShutdownHook(hook); }
            catch (IllegalStateException ignored) { /* The JVM is already shutting down. */ }
        }
    }

    private void closeMetricsAsync() {
        Thread cleanup = new Thread(jvmMetricsService::close, "desktop-close-jmx");
        cleanup.setDaemon(true);
        cleanup.start();
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

        if (diagnosticsPane != null) diagnosticsPane.close();
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
        closeMetricsAsync();

        MonitoringRuntime stoppedServer = traceServer;
        traceServer = null;
        if (stoppedServer != null) {
            Thread cleanup = new Thread(() -> {
                stoppedServer.stop(); removeShutdownHook(stoppedServer);
            }, "stop-collector-after-target-exit");
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
        if (controllerClosed) return;
        if (!metricsSampling.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                JvmMetrics metrics = jvmMetricsService.sample();
                Platform.runLater(() -> { if (!controllerClosed) updateJvmMetrics(metrics); });
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
        startBtn.setVisible(!visible);
        startBtn.setManaged(!visible);
    }

    public StackPane getRoot() { return rootWithOverlay; }
    /** Current validated next-session configuration; read on the JavaFX application thread. */
    public MonitoringConfig getConfiguration() { return currentConfig; }

    private void setStatus(String msg) {
        statusLabel.setText(msg);
        statusLabel.setTooltip(new Tooltip(msg));
    }

    /** Establishes one model before the asynchronous class index begins driving the preview. */
    private void initializeDefaultConfiguration() {
        MonitoringConfig defaults = MonitoringConfig.defaults();
        MonitoringConfig.Scope scope = new MonitoringConfig.Scope(List.of(), List.of(),
                defaults.scope().excludeConditions(), defaults.scope().excludeMethods());
        applyYamlConfigurationToPanels(defaults.withScope(scope));
        setYamlPreview(currentConfig);
    }

    private void refreshYamlPreview() {
        refreshYamlPreview(false);
    }

    private void refreshYamlPreview(boolean force) {
        MonitoringConfig base = currentConfig == null ? MonitoringConfig.defaults() : currentConfig;
        List<String> includedPackages = classSelectionPane.getIncludedPackages();
        List<String> includedClasses = classSelectionPane.getIncludedClasses();
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
        // Editing next-session rules cannot overwrite the active Attach result.
        if(traceServer!=null || monitoring || collectorReady){updateImpactIndicators(classes,methods);return;}
        if (!monitoring) {
            enhancementScopeLabel.setText("全局范围 · 规则匹配，尚未增强");
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
            startBtn.setTooltip(new Tooltip("监控范围过大，请缩小到 " + MAX_ENHANCED_CLASSES + " 个类以内"));
        } else if (!monitoring && traceServer == null) {
            boolean noScope = classSelectionPane.getIncludedPackages().isEmpty()
                    && classSelectionPane.getIncludedClasses().isEmpty();
            String reason = classIndexLoading ? "正在扫描目标 JVM 的类，请稍候"
                    : yamlApplyBusy ? "正在应用配置，请稍候"
                    : offlineReplayLoading ? "正在载入历史会话，请稍候"
                    : classSelectionPane.hasPendingRuleEdits() ? "先应用或撤销未保存的范围规则"
                    : noScope ? "先在采集配置中纳入业务包或具体类" : null;
            startBtn.setDisable(reason != null);
            startBtn.setTooltip(new Tooltip(reason == null ? "将当前配置应用到目标 JVM，开始采集调用链" : reason));
        }
    }

    private static String formatCount(long value) {
        return String.format("%,d", value);
    }

    /** Process totals are not instrumentation overhead; only a controlled comparison can quantify it. */
    private void updateImpactIndicators(long classes, long methods) {
        impactStatusLabel.setText((monitoring || collectorReady)
                ? "运行中 · 性能增量尚未实测；JVM 指标显示进程总量。"
                : "性能影响尚未实测 · 需在相同业务负载下对照验证。");
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
                return token.substring(token.lastIndexOf('.') + 1);
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
        if (controllerClosed) return;
        Thread t = new Thread(() -> {
            try {
                String yaml = Files.readString(file.toPath());
                MonitoringConfig config = MonitoringConfigYaml.parse(yaml);
                Platform.runLater(() -> applyYamlConfigurationAsync(config, "配置已导入: " + file.getName()));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (controllerClosed) return;
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
        callTreePane.showOfflineReplay(result.roots().stream().map(com.agentmonitor.app.model.CallNode::fromTrace).toList());
        showCallsPage();
        String quality = result.hasDegradation()
                ? "；跳过 " + result.skippedSpans() + " 个，孤儿 " + result.orphanSpans()
                        + " 个，循环 " + result.cyclicSpans() + " 个，文件警告 " + result.warnings().size() + " 项"
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
        if (controllerClosed) return;
        setYamlApplyBusy(true, "正在应用 YAML 配置，请稍候...");
        Thread parser = new Thread(() -> {
            try {
                MonitoringConfig config = MonitoringConfigYaml.parse(yaml);
                Platform.runLater(() -> applyYamlConfigurationAsync(config, successMessage));
            } catch (Exception error) {
                Platform.runLater(() -> {
                    if (controllerClosed) return;
                    setYamlApplyBusy(false, "");
                    showAlert(Alert.AlertType.ERROR, "YAML 配置无效：" + error.getMessage());
                });
            }
        }, "parse-yaml-config");
        parser.setDaemon(true);
        parser.start();
    }

    private boolean applyYamlConfigurationAsync(MonitoringConfig config, String successMessage) {
        if (controllerClosed) return false;
        setYamlApplyBusy(true, "正在同步类选择，请稍候...");
        try {
            currentConfig = config;
            yamlDirty = true;
            classSelectionPane.applyConfigurationAsync(config.scope().includePackages(),
                    config.scope().includeClasses(), config.scope().excludeConditions(), config.scope().excludeMethods(), () -> {
                        if (controllerClosed) return;
                        showProgress(true, "正在写入配置并计算影响...");
                        setYamlPreview(config);
                        scheduleSelectionSummaryRefresh();
                        setStatus(successMessage);
                        setYamlApplyBusy(false, "");
                    });
            return true;
        } catch (RuntimeException error) {
            setYamlApplyBusy(false, "");
            showAlert(Alert.AlertType.ERROR, "同步 YAML 配置失败：" + error.getMessage());
            return false;
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

    private void showCallsPage() {
        workbench.selectPage("calls");
    }

    private void showLogDialog() {
        LogViewerDialog dialog = new LogViewerDialog(root.getScene().getWindow(),
                root.getScene() != null ? root.getScene().getStylesheets() : java.util.List.of(),
                monitoringSession == null ? null : monitoringSession.agentLogsDirectory(), AppLog.file());
        dialog.show();
    }

    /** Offers the three useful handoff actions without implying a failed STOP produced a final report. */
    private void showSessionResults(MonitoringSession session, boolean outputDrained,
                                          String completionMessage,
                                          CaptureQuality captureQuality,
                                          SessionRetentionManager.CleanupResult cleanupResult) {
        if (session == null) return;
        Label resultTitle = new Label(outputDrained ? "采集完成，字节码已还原" : "字节码已还原，采集不完整");
        resultTitle.getStyleClass().add("session-result-title");

        Label description = new Label(outputDrained
                ? "字节码已还原，最终报告已在停止流程中写入。"
                : "字节码已还原，可以开始下一轮监控；本次存在未完成调用、未闭合链路或导出未完成，请结合下方原因检查报告完整性。");
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

        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(8, 8, reportButton, directoryButton, copyPromptButton);
        VBox content = new VBox(20, resultTitle, description, actions, new Separator(), location, feedback);
        content.getStyleClass().add("session-result");
        ScrollPane scroll = new ScrollPane(content); scroll.setFitToWidth(true);
        workbench.replacePage("sessions", scroll);
        workbench.selectPage("sessions");
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
        statusDotLabel.setText(active ? "采集中" : "未运行");
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
