package com.agentmonitor.app.ui;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.agentmonitor.app.model.JvmMetrics;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

public class JvmMetricsPane extends VBox {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Label statusLabel = new Label("等待采集");
    private final MetricCard heapCard = new MetricCard("Heap", "0 MB", "0 / 0 MB");
    private final MetricCard nonHeapCard = new MetricCard("Non-Heap", "0 MB", "0 / 0 MB");
    private final MetricCard threadCard = new MetricCard("Threads", "0", "daemon 0");
    private final MetricCard gcCard = new MetricCard("GC", "0 次", "0 ms");
    private final MetricCard classCard = new MetricCard("Classes", "0", "total 0");

    public JvmMetricsPane() {
        getStyleClass().add("jvm-metrics-pane");
        setSpacing(10);

        Label icon = new Label("◈");
        icon.getStyleClass().add("metrics-panel-icon");
        Label title = new Label("JVM 指标");
        title.getStyleClass().add("card-title");

        statusLabel.getStyleClass().add("metrics-status-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(8, icon, title, spacer, statusLabel);
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("preview-panel-head");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(heapCard, 0, 0);
        grid.add(nonHeapCard, 1, 0);
        grid.add(threadCard, 0, 1);
        grid.add(gcCard, 1, 1);
        grid.add(classCard, 0, 2, 2, 1);
        GridPane.setHgrow(heapCard, Priority.ALWAYS);
        GridPane.setHgrow(nonHeapCard, Priority.ALWAYS);
        GridPane.setHgrow(threadCard, Priority.ALWAYS);
        GridPane.setHgrow(gcCard, Priority.ALWAYS);
        GridPane.setHgrow(classCard, Priority.ALWAYS);

        Label hint = new Label("旁路采集，不影响当前 Java 方法监控配置。");
        hint.getStyleClass().add("metrics-hint-label");

        getChildren().addAll(head, grid, hint);
    }

    public void update(JvmMetrics metrics) {
        if (metrics == null) return;
        if (!metrics.available()) {
            statusLabel.setText("采集失败");
            statusLabel.getStyleClass().removeAll("metrics-status-ok");
            statusLabel.getStyleClass().add("metrics-status-error");
            heapCard.setValue("不可用", metrics.error(), 0);
            nonHeapCard.setValue("不可用", "等待下次采样", 0);
            return;
        }

        statusLabel.getStyleClass().removeAll("metrics-status-error");
        statusLabel.getStyleClass().add("metrics-status-ok");
        statusLabel.setText("更新 " + TIME_FORMAT.format(Instant.ofEpochMilli(metrics.timestampMillis())));

        heapCard.setValue(formatBytes(metrics.heapUsed()),
                formatBytes(metrics.heapUsed()) + " / " + formatBytes(maxOrCommitted(metrics.heapMax(), metrics.heapCommitted())),
                metrics.heapUsageRatio());
        nonHeapCard.setValue(formatBytes(metrics.nonHeapUsed()),
                formatBytes(metrics.nonHeapUsed()) + " / " + formatBytes(metrics.nonHeapCommitted()),
                metrics.nonHeapUsageRatio());
        threadCard.setValue(String.valueOf(metrics.threadCount()),
                "daemon " + metrics.daemonThreadCount(), progressFrom(metrics.threadCount(), 400));
        gcCard.setValue(metrics.gcCount() + " 次",
                metrics.gcTimeMillis() + " ms", progressFrom(metrics.gcTimeMillis(), 60_000));
        classCard.setValue(String.valueOf(metrics.loadedClassCount()),
                "total " + metrics.totalLoadedClassCount() + " / unloaded " + metrics.unloadedClassCount(),
                progressFrom(metrics.loadedClassCount(), 20_000));
    }

    private static long maxOrCommitted(long max, long committed) {
        return max > 0 ? max : committed;
    }

    private static double progressFrom(long value, long max) {
        return max <= 0 ? 0 : Math.min(1.0, value / (double) max);
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 MB";
        double mb = bytes / 1024.0 / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        return String.format("%.2f GB", mb / 1024.0);
    }

    public static class MetricCard extends VBox {
        private final Label valueLabel = new Label();
        private final Label descLabel = new Label();
        private final ProgressBar progressBar = new ProgressBar(0);

        MetricCard(String title, String value, String desc) {
            getStyleClass().add("metric-card");
            setPadding(new Insets(10));
            setSpacing(6);
            setMaxWidth(Double.MAX_VALUE);

            Label titleLabel = new Label(title);
            titleLabel.getStyleClass().add("metric-card-title");
            valueLabel.getStyleClass().add("metric-card-value");
            descLabel.getStyleClass().add("metric-card-desc");
            progressBar.getStyleClass().add("metric-progress");
            progressBar.setMaxWidth(Double.MAX_VALUE);

            setValue(value, desc, 0);
            getChildren().addAll(titleLabel, valueLabel, descLabel, progressBar);
        }

        void setValue(String value, String desc, double progress) {
            valueLabel.setText(value == null ? "-" : value);
            descLabel.setText(desc == null ? "" : desc);
            progressBar.setProgress(Math.max(0, Math.min(1, progress)));
        }
    }
}
