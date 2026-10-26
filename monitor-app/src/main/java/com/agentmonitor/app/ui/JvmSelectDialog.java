package com.agentmonitor.app.ui;

import java.util.function.Consumer;
import java.util.Comparator;
import java.util.Locale;
import com.agentmonitor.app.util.AppLog;
import javafx.scene.control.Tooltip;

import com.agentmonitor.app.model.JvmProcess;
import com.agentmonitor.app.service.JvmService;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

public class JvmSelectDialog {

    private final Stage dialog;
    private final ObservableList<JvmProcess> allItems = FXCollections.observableArrayList();
    private final FilteredList<JvmProcess> filtered   = new FilteredList<>(allItems, p -> true);
    private final ListView<JvmProcess> listView;
    private final Button refreshBtn = new Button("重新扫描");
    private final ProgressIndicator refreshProgress = new ProgressIndicator();
    private volatile boolean refreshing = false;
    private Consumer<JvmProcess> onConfirm;
    private final Label resultCount = new Label("正在发现本机进程…");
    private final Label emptyTitle = new Label("正在扫描 JVM 进程");
    private final Label emptyDescription = new Label("正在读取本机可连接的 Java 应用，请稍候。");
    private final TextField searchField = new TextField();
    private String scanError;
    private boolean confirmed;

    public JvmSelectDialog(Stage owner) {
        dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.APPLICATION_MODAL);
        dialog.initStyle(StageStyle.DECORATED);
        dialog.setTitle("Agent Monitor");
        dialog.getIcons().add(AppIcons.monitorIcon());
        dialog.setResizable(true);
        dialog.setMinWidth(760);
        dialog.setMinHeight(560);

        listView = buildListView();

        /* ── Big title ─────────────────────────────────────── */
        Label appTitle = new Label("选择目标进程");
        appTitle.getStyleClass().add("jvm-select-title");
        Label appSubtitle = new Label("连接本机 Java 应用，按需观测方法耗时与调用链。");
        appSubtitle.getStyleClass().add("jvm-select-subtitle");
        VBox titleBox = new VBox(6, appTitle, appSubtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);
        titleBox.setPadding(new Insets(8, 0, 8, 0));

        /* ── Search bar ─────────────────────────────────────── */
        searchField.setPromptText("搜索 JVM 进程（PID / 主类 / 路径）");
        searchField.getStyleClass().add("search-field");
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, old, val) -> {
            String lower = val == null ? "" : val.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(p ->
                    lower.isEmpty()
                    || p.getPid().contains(lower)
                    || p.getDisplayName().toLowerCase(Locale.ROOT).contains(lower));
            updateResults();
            if (!filtered.isEmpty()) {
                listView.getSelectionModel().select(0);
                listView.scrollTo(0);
            }
        });
        searchField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.DOWN) {
                listView.requestFocus();
                if (listView.getSelectionModel().isEmpty() && !filtered.isEmpty()) {
                    listView.getSelectionModel().select(0);
                }
                e.consume();
            } else if (e.getCode() == KeyCode.UP) {
                listView.requestFocus();
                listView.getSelectionModel().selectPrevious();
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                confirm();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                dialog.close();
                e.consume();
            }
        });

        refreshBtn.getStyleClass().add("ghost-btn");
        UiIcons.decorate(refreshBtn, "refresh");
        refreshBtn.setOnAction(e -> refresh());
        refreshProgress.setPrefSize(18, 18);
        refreshProgress.setMaxSize(18, 18);
        refreshProgress.setVisible(false);
        refreshProgress.setManaged(false);

        HBox searchBar = new HBox(8, searchField, refreshProgress, refreshBtn);
        searchBar.setAlignment(Pos.CENTER_LEFT);

        /* ── List ───────────────────────────────────────────── */
        listView.setPrefHeight(390);
        VBox.setVgrow(listView, Priority.ALWAYS);

        /* ── Confirm button ─────────────────────────────────── */
        Button confirmBtn = new Button("继续配置");
        confirmBtn.getStyleClass().addAll("primary-btn", "jvm-confirm-btn");
        confirmBtn.setPrefWidth(160);
        confirmBtn.setPrefHeight(42);
        UiIcons.decorate(confirmBtn, "arrow");
        confirmBtn.setDefaultButton(false);
        confirmBtn.setOnAction(e -> confirm());
        confirmBtn.disableProperty().bind(
                listView.getSelectionModel().selectedItemProperty().isNull());

        Label keyboardHint = new Label("↑ ↓ 选择进程    Enter 继续    F5 刷新");
        keyboardHint.getStyleClass().add("hint-label");
        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        HBox btnBar = new HBox(12, keyboardHint, footerSpacer, confirmBtn);
        btnBar.setAlignment(Pos.CENTER_LEFT);
        btnBar.getStyleClass().add("selector-footer");
        resultCount.getStyleClass().add("section-header");
        VBox content = new VBox(16, titleBox, searchBar, resultCount, listView, btnBar);
        content.getStyleClass().add("jvm-select-card");
        HBox.setHgrow(content, Priority.ALWAYS);
        VBox.setVgrow(listView, Priority.ALWAYS);

        HBox hCenter = new HBox(content);
        hCenter.setPadding(new Insets(24, 48, 24, 48));
        VBox.setVgrow(content, Priority.ALWAYS);

        VBox root = new VBox(hCenter);
        root.getStyleClass().add("jvm-select-root");
        VBox.setVgrow(hCenter, Priority.ALWAYS);

        Scene scene = new Scene(root, 920, 660);
        scene.getStylesheets().add(
                getClass().getResource("/com/agentmonitor/app/main.css").toExternalForm());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                dialog.close();
                e.consume();
            } else if (e.getCode() == KeyCode.F5 || (e.isShortcutDown() && e.getCode() == KeyCode.R)) {
                refresh();
                e.consume();
            }
        });
        dialog.setScene(scene);

        refresh();
        Platform.runLater(searchField::requestFocus);
    }

    /* ── ListView ──────────────────────────────────────────── */
    private ListView<JvmProcess> buildListView() {
        ListView<JvmProcess> lv = new ListView<>(filtered);
        lv.getStyleClass().add("jvm-list-view");
        lv.setFixedCellSize(82);
        lv.setCellFactory(list -> new JvmCard());
        lv.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && lv.getSelectionModel().getSelectedItem() != null)
                confirm();
        });
        lv.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                confirm();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                dialog.close();
                e.consume();
            }
        });
        lv.setPlaceholder(buildPlaceholder());
        return lv;
    }

    private VBox buildPlaceholder() {
        emptyTitle.getStyleClass().add("jvm-empty-title");
        emptyDescription.getStyleClass().add("jvm-empty-subtitle");
        emptyDescription.setWrapText(true);
        emptyDescription.setMaxWidth(420);
        VBox box = new VBox(12, UiIcons.icon("search"), emptyTitle, emptyDescription);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /* ── Card cell ─────────────────────────────────────────── */
    private static class JvmCard extends ListCell<JvmProcess> {
        private final Label pidBadge = new Label();
        private final Label titleLbl = new Label();
        private final Label detailLbl = new Label();
        private final Label typeBadge = new Label();
        private final VBox textBox = new VBox(4, titleLbl, detailLbl);
        private final HBox card = new HBox(12, pidBadge, textBox, new Region(), typeBadge);

        JvmCard() {
            pidBadge.getStyleClass().add("jvm-pid-badge");
            titleLbl.getStyleClass().add("jvm-process-title");
            detailLbl.getStyleClass().add("jvm-process-detail");
            typeBadge.getStyleClass().add("jvm-type-badge");
            textBox.setMinWidth(0);
            titleLbl.setMaxWidth(Double.MAX_VALUE);
            detailLbl.setMaxWidth(Double.MAX_VALUE);
            titleLbl.setWrapText(false);
            detailLbl.setWrapText(false);
            HBox.setHgrow(textBox, Priority.ALWAYS);
            HBox.setHgrow(card.getChildren().get(2), Priority.ALWAYS);
            card.getStyleClass().add("jvm-process-card");
            card.setAlignment(Pos.CENTER_LEFT);

            setPrefHeight(82);
            setMinHeight(82);
            setMaxHeight(82);
        }

        @Override
        protected void updateItem(JvmProcess item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            if (empty || item == null) {
                setGraphic(null);
                getStyleClass().remove("jvm-card-selected-cell");
            } else {
                pidBadge.setText("PID " + item.getPid());
                setAccessibleText(item.getDisplayName() + ", PID " + item.getPid());
                setTooltip(new Tooltip(item.getDisplayName()));
                titleLbl.setText(displayTitle(item.getDisplayName()));
                detailLbl.setText(displayDetail(item.getDisplayName()));
                typeBadge.setText(typeLabel(item.getDisplayName()));
                card.getStyleClass().remove("jvm-process-card-selected");
                if (isSelected()) card.getStyleClass().add("jvm-process-card-selected");
                setGraphic(card);
            }
        }

        @Override
        public void updateSelected(boolean selected) {
            super.updateSelected(selected);
            card.getStyleClass().remove("jvm-process-card-selected");
            if (getItem() != null && !isEmpty() && selected) {
                card.getStyleClass().add("jvm-process-card-selected");
            }
        }

        private String displayTitle(String displayName) {
            String value = displayName == null ? "(unknown)" : displayName;
            int split = value.indexOf("  |  ");
            return split > 0 ? value.substring(0, split) : firstMeaningfulToken(value);
        }

        private String displayDetail(String displayName) {
            String value = displayName == null ? "" : displayName;
            int split = value.indexOf("  |  ");
            String detail = split > 0 ? value.substring(split + 5) : value;
            return abbreviate(detail, 180);
        }

        private String firstMeaningfulToken(String value) {
            if (value == null || value.isBlank()) return "(unknown)";
            String[] parts = value.split("\\s+");
            return parts.length == 0 ? value : parts[parts.length - 1].contains(".") ? parts[parts.length - 1] : value;
        }

        private String typeLabel(String displayName) {
            String value = displayName == null ? "" : displayName.toLowerCase();
            if (value.contains("gradle worker")) return "Worker";
            if (value.contains("gradledaemon")) return "Gradle";
            if (value.contains("bytesforce") || value.contains("gicore") || value.contains("insmate")) return "App";
            if (value.contains("intellij")) return "IDE";
            return "JVM";
        }

        private String abbreviate(String value, int maxLength) {
            if (value == null || value.length() <= maxLength) return value == null ? "" : value;
            return value.substring(0, maxLength - 1) + "…";
        }
    }

    /* ── Actions ───────────────────────────────────────────── */
    private void confirm() {
        JvmProcess selected = listView.getSelectionModel().getSelectedItem();
        if (selected == null || refreshing || confirmed) return;
        confirmed = true;
        dialog.close();
        if (onConfirm != null) onConfirm.accept(selected);
    }

    private void refresh() {
        if (refreshing) return;
        refreshing = true;
        scanError = null;
        updateResults();
        refreshBtn.setDisable(true);
        refreshBtn.setText("扫描中…");
        refreshProgress.setVisible(true);
        refreshProgress.setManaged(true);
        Thread scanner = new Thread(() -> {
            try {
                java.util.List<JvmProcess> list = JvmService.listProcesses().stream()
                        .sorted(Comparator.comparingInt(this::processRank)
                                .thenComparing(JvmProcess::getDisplayName)
                                .thenComparing(JvmProcess::getPid))
                        .toList();
                Platform.runLater(() -> {
                    JvmProcess sel = listView.getSelectionModel().getSelectedItem();
                    allItems.setAll(list);
                    JvmProcess previous = sel == null ? null : filtered.stream()
                            .filter(p -> p.getPid().equals(sel.getPid())).findFirst().orElse(null);
                    if (previous != null) listView.getSelectionModel().select(previous);
                    else if (!filtered.isEmpty()) listView.getSelectionModel().selectFirst();
                    updateResults();
                });
            } catch (Exception error) {
                AppLog.warn("JVM scan failed: " + error.getMessage());
                Platform.runLater(() -> {
                    scanError = "扫描失败，请重新扫描。";
                    allItems.clear();
                });
            } finally {
                Platform.runLater(() -> {
                    refreshing = false;
                    updateResults();
                    refreshBtn.setDisable(false);
                    refreshBtn.setText("重新扫描");
                    refreshProgress.setVisible(false);
                    refreshProgress.setManaged(false);
                });
            }
        }, "jvm-refresh");
        scanner.setDaemon(true);
        scanner.start();
    }

    private void updateResults() {
        resultCount.setText(refreshing ? "正在发现本机进程…"
                : "可连接进程 · " + filtered.size() + " / " + allItems.size());
        if (refreshing) {
            emptyTitle.setText("正在扫描 JVM 进程");
            emptyDescription.setText("正在读取本机可连接的 Java 应用，请稍候。");
        } else if (scanError != null) {
            emptyTitle.setText("暂时无法读取进程");
            emptyDescription.setText(scanError);
        } else if (!searchField.getText().isBlank()) {
            emptyTitle.setText("没有匹配的进程");
            emptyDescription.setText("试试其他 PID、主类或路径，或清空搜索条件。");
        } else {
            emptyTitle.setText("未发现 JVM 进程");
            emptyDescription.setText("启动需要监控的 Java 应用后，点击「重新扫描」。");
        }
    }

    private int processRank(JvmProcess process) {
        String name = process.getDisplayName() == null ? "" : process.getDisplayName().toLowerCase();
        if (name.contains("bytesforce") || name.contains("gicore") || name.contains("insmate")) return 0;
        if (name.contains("gradle worker")) return 8;
        if (name.contains("gradledaemon") || name.contains("gradle")) return 7;
        if (name.contains("intellij")) return 6;
        return 3;
    }

    public void show(Consumer<JvmProcess> onConfirmCallback) {
        this.onConfirm = onConfirmCallback;
        dialog.show();
    }

    public void showAndWait(Consumer<JvmProcess> onConfirmCallback) {
        this.onConfirm = onConfirmCallback;
        dialog.showAndWait();
    }
}
