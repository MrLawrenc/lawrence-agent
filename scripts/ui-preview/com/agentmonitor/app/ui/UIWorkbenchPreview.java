package com.agentmonitor.app.ui;

import com.agentmonitor.app.model.CallNode;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import java.util.List;

/** Gallery for inspecting actual JavaFX controls when the baseline app sources are incomplete. */
public final class UIWorkbenchPreview extends Application {
    private Stage stage;
    private final Label state = new Label("界面预览 · 示例数据，无真实 JVM 连接");
    @Override public void start(Stage stage) {
        this.stage = stage;
        stage.setTitle("Agent Monitor · UI Preview");
        stage.setMinWidth(1240);
        stage.setMinHeight(720);
        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        Button choose = button("选择进程", "switch", "ghost-btn");
        choose.setOnAction(e -> new JvmSelectDialog(stage).show(p -> state.setText("已选择示例进程 · PID " + p.getPid())));
        Label title = label("Java 性能监控工具", "app-title");
        Label identity = label("目标: OrderApplication · PID 28416", "target-label");
        Label runtime = label("JVM: Java 21 · 示例", "resource-runtime-label");
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        ToolBar top = new ToolBar(UiIcons.icon("monitor"), title, new Separator(), new VBox(3, identity, runtime), spacer, choose);
        top.getStyleClass().add("app-toolbar"); root.setTop(top);
        ClassSelectionPane selection = new ClassSelectionPane("preview");
        VBox.setVgrow(selection, Priority.ALWAYS);
        Button output = button("查看调用链", "monitor", "ghost-btn");
        output.setOnAction(e -> showCalls());
        Button refresh = button("刷新范围", "refresh", "ghost-btn");
        refresh.setOnAction(e -> selection.reloadClasses());
        ToolBar actions = new ToolBar(label("● 未运行", "status-dot-inactive"), refresh, output);
        actions.getStyleClass().add("center-action-bar");
        VBox workspace = new VBox(12, actions, selection); workspace.getStyleClass().add("monitor-panel");
        TextArea yaml = new TextArea("version: 2\n\nscope:\n  includePackages:\n    - com.example.orders\n  excludeConditions:\n    - cls:*DTO\n  excludeMethods:\n    - get*\n    - set*\n\nsampling:\n  ratePercent: 10\n  tailCaptureThresholdMs: 50\n\noutput:\n  exporters: [netty, file]\n");
        yaml.getStyleClass().add("yaml-preview"); yaml.setEditable(false); VBox.setVgrow(yaml, Priority.ALWAYS);
        Button copy = button("复制", "copy", "ghost-btn");
        copy.setOnAction(e -> { javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent(); content.putString(yaml.getText()); javafx.scene.input.Clipboard.getSystemClipboard().setContent(content); state.setText("示例 YAML 已复制"); });
        HBox heading = new HBox(12, label("配置 YAML", "card-title"), copy); heading.setAlignment(Pos.CENTER_LEFT);
        VBox summary = new VBox(8, label("选择概览", "summary-title"), label("示例数据 · 仅用于布局检查", "hint-label"));
        summary.getStyleClass().add("selection-summary-card");
        VBox config = new VBox(14, heading, yaml, summary); config.getStyleClass().add("preview-panel");
        JvmMetricsPane metrics = new JvmMetricsPane(); metrics.setVisible(false); metrics.setManaged(false);
        StackPane preview = new StackPane(config, metrics); VBox.setVgrow(preview, Priority.ALWAYS);
        ToggleButton configToggle = new ToggleButton("配置"), metricsToggle = new ToggleButton("JVM 指标");
        ToggleGroup group = new ToggleGroup(); configToggle.setToggleGroup(group); metricsToggle.setToggleGroup(group); configToggle.setSelected(true);
        configToggle.getStyleClass().add("segment-btn"); metricsToggle.getStyleClass().add("segment-btn");
        group.selectedToggleProperty().addListener((obs, old, now) -> {
            if (now == null) { group.selectToggle(old); return; }
            boolean showConfig = now == configToggle;
            config.setVisible(showConfig); config.setManaged(showConfig);
            metrics.setVisible(!showConfig); metrics.setManaged(!showConfig);
        });
        HBox segments = new HBox(4, configToggle, metricsToggle); segments.getStyleClass().add("segment-group");
        HBox previewBar = new HBox(segments); previewBar.setAlignment(Pos.CENTER_LEFT); previewBar.getStyleClass().add("preview-top-bar");
        VBox side = new VBox(12, previewBar, preview); side.setMinWidth(330); side.getStyleClass().add("preview-column");
        SplitPane split = new SplitPane(workspace, side); split.setDividerPositions(.75); root.setCenter(split);
        Button start = button("开始监控", "play", "start-btn");
        start.setOnAction(e -> state.setText("这是界面预览，不会连接或注入真实 JVM。"));
        HBox footer = new HBox(16, state, start); footer.setAlignment(Pos.CENTER_LEFT); footer.getStyleClass().add("status-bar"); root.setBottom(footer);
        Scene scene = new Scene(root, 1440, 860);
        scene.getStylesheets().add(getClass().getResource("/com/agentmonitor/app/main.css").toExternalForm());
        stage.setScene(scene); stage.show();
        if (getParameters().getRaw().contains("--verify")) PreviewChecks.run(stage);
        Platform.runLater(() -> selection.applyConfiguration(List.of("com.example.orders"), List.of(), List.of(), List.of("get*", "set*")));
    }
    private void showCalls() {
        CallTreePane calls = new CallTreePane();
        CallNode root = new CallNode("com.example.orders.OrderController", "create", "http-nio-8080-exec-1", 128_000_000, false, 0);
        CallNode child = new CallNode("com.example.orders.OrderService", "validateOrder", "http-nio-8080-exec-1", 34_000_000, false, 1);
        root.getChildren().add(child);
        calls.showOfflineReplay(List.of(root));
        Dialog<Void> dialog = new Dialog<>(); dialog.initOwner(stage); dialog.setTitle("调用链 · 示例");
        dialog.getDialogPane().setContent(calls); dialog.getDialogPane().setPrefSize(1040, 620);
        dialog.getDialogPane().getStylesheets().addAll(stage.getScene().getStylesheets());
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE); dialog.setResizable(true); dialog.show();
    }
    private static Label label(String text, String style) { Label label = new Label(text); label.getStyleClass().add(style); return label; }
    private static Button button(String text, String icon, String style) { Button button = new Button(text); button.getStyleClass().add(style); UiIcons.decorate(button, icon); return button; }
    public static void main(String[] args) { launch(args); }
}
