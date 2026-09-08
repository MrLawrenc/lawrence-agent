package com.agentmonitor.app.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;

public class LogViewerDialog {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String AGENT_LOG_FILE = "agent-monitor.log";

    private final Stage stage = new Stage();
    private final ListView<Path> historyList = new ListView<>();
    private final TextArea logText = new TextArea();
    private final Label pathLabel = new Label();
    private final Button autoRefreshButton = new Button("自动刷新: 开");
    private final Button sessionButton = new Button("会话日志");
    private final Button applicationButton = new Button("应用日志");
    private final ComboBox<Integer> refreshIntervalBox = new ComboBox<>();
    private final Timeline refreshTimer;
    private final Path sessionLogDirectory;
    private final Path applicationLogFile;
    private boolean autoRefresh = true;
    private Path selectedFile;

    public LogViewerDialog(Window owner, List<String> stylesheets, Path sessionLogDirectory, Path applicationLogFile) {
        this.sessionLogDirectory = sessionLogDirectory == null ? null : sessionLogDirectory.toAbsolutePath().normalize();
        this.applicationLogFile = applicationLogFile == null ? null : applicationLogFile.toAbsolutePath().normalize();
        stage.initOwner(owner);
        // An owned window closes with the main App, while NONE keeps every other App dialog usable.
        stage.initModality(Modality.NONE);
        stage.setAlwaysOnTop(false);
        stage.setTitle("运行日志");
        stage.getIcons().add(AppIcons.monitorIcon());

        historyList.getStyleClass().add("log-history-list");
        historyList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Path path, boolean empty) {
                super.updateItem(path, empty);
                if (empty || path == null) {
                    setText(null);
                    return;
                }
                setText(labelFor(path));
            }
        });
        historyList.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (now != null) {
                selectedFile = now;
                updateSourceButtons();
                refreshContent(false);
            }
        });

        logText.getStyleClass().add("log-text-area");
        logText.setEditable(false);
        logText.setWrapText(false);

        Button refreshButton = new Button("刷新");
        refreshButton.getStyleClass().add("ghost-btn");
        refreshButton.setOnAction(e -> {
            reloadHistory();
            refreshContent(false);
        });

        autoRefreshButton.getStyleClass().add("ghost-btn");
        autoRefreshButton.setOnAction(e -> toggleAutoRefresh());

        refreshIntervalBox.setItems(FXCollections.observableArrayList(5, 10, 30, 60));
        refreshIntervalBox.setValue(10);
        refreshIntervalBox.setPrefWidth(92);
        refreshIntervalBox.getStyleClass().add("log-refresh-interval");
        refreshIntervalBox.setButtonCell(new IntervalCell());
        refreshIntervalBox.setCellFactory(list -> new IntervalCell());
        refreshIntervalBox.valueProperty().addListener((obs, old, now) -> restartRefreshTimer());

        sessionButton.setOnAction(e -> selectFile(sessionLogFile()));

        applicationButton.setOnAction(e -> selectFile(applicationLogFile));

        pathLabel.getStyleClass().add("hint-label");

        HBox toolbar = new HBox(8, sessionButton, applicationButton, refreshButton,
                autoRefreshButton, new Label("间隔"), refreshIntervalBox);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox left = new VBox(8, new Label("历史日志"), historyList);
        left.getStyleClass().add("log-history-pane");
        VBox.setVgrow(historyList, Priority.ALWAYS);

        VBox right = new VBox(8, toolbar, pathLabel, logText);
        right.getStyleClass().add("log-content-pane");
        VBox.setVgrow(logText, Priority.ALWAYS);

        SplitPane split = new SplitPane(left, right);
        split.setDividerPositions(0.24);

        VBox root = new VBox(split);
        root.getStyleClass().add("log-viewer-root");
        root.setPadding(new Insets(12));
        VBox.setVgrow(split, Priority.ALWAYS);

        Scene scene = new Scene(root, 1080, 680);
        scene.getStylesheets().addAll(stylesheets);
        stage.setScene(scene);

        refreshTimer = new Timeline(new KeyFrame(Duration.seconds(refreshIntervalSeconds()), e -> {
            if (autoRefresh && stage.isShowing()) refreshContent(true);
        }));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        stage.setOnHidden(e -> refreshTimer.stop());

        reloadHistory();
        selectFile(sessionLogFile() == null ? applicationLogFile : sessionLogFile());
    }

    public void show() {
        stage.show();
        stage.toFront();
        restartRefreshTimer();
    }

    private void reloadHistory() {
        List<Path> files;
        if (sessionLogDirectory == null || !Files.isDirectory(sessionLogDirectory)) {
            files = List.of();
        } else {
            try (Stream<Path> stream = Files.list(sessionLogDirectory)) {
                files = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".log"))
                        .sorted(Comparator.comparing(this::lastModified).reversed())
                        .toList();
            } catch (Exception ignored) {
                files = List.of();
            }
        }
        if (applicationLogFile != null && !files.contains(applicationLogFile)) {
            files = Stream.concat(files.stream(), Stream.of(applicationLogFile))
                    .sorted(Comparator.comparing(this::lastModified).reversed()).toList();
        }
        historyList.setItems(FXCollections.observableArrayList(files));
    }

    private void selectFile(Path file) {
        selectedFile = file;
        reloadHistory();
        historyList.getSelectionModel().select(file);
        updateSourceButtons();
        refreshContent(false);
    }

    private void refreshContent(boolean keepScrollAtEnd) {
        if (selectedFile == null) return;
        try {
            String content = Files.exists(selectedFile) ? Files.readString(selectedFile) : "";
            logText.setText(content);
            pathLabel.setText(sourceLabel(selectedFile) + ": " + selectedFile.toAbsolutePath());
            if (keepScrollAtEnd) {
                Platform.runLater(() -> logText.positionCaret(logText.getText().length()));
            }
        } catch (Exception e) {
            logText.setText("读取日志失败: " + e.getMessage());
            pathLabel.setText(sourceLabel(selectedFile) + ": " + selectedFile.toAbsolutePath());
        }
    }

    private void toggleAutoRefresh() {
        autoRefresh = !autoRefresh;
        autoRefreshButton.setText(autoRefresh ? "自动刷新: 开" : "自动刷新: 关");
        if (autoRefresh) refreshContent(true);
    }

    private void restartRefreshTimer() {
        refreshTimer.stop();
        refreshTimer.getKeyFrames().setAll(new KeyFrame(Duration.seconds(refreshIntervalSeconds()), e -> {
            if (autoRefresh && stage.isShowing()) refreshContent(true);
        }));
        refreshTimer.setCycleCount(Timeline.INDEFINITE);
        if (stage.isShowing()) refreshTimer.play();
    }

    private int refreshIntervalSeconds() {
        Integer value = refreshIntervalBox.getValue();
        return value == null ? 10 : value;
    }

    private String labelFor(Path path) {
        String name = path.getFileName().toString();
        try {
            String modified = Files.getLastModifiedTime(path).toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(TIME_FORMATTER);
            long size = Files.size(path);
            return sourceLabel(path) + " · " + name + "\n" + modified + " · " + humanSize(size);
        } catch (Exception e) {
            return sourceLabel(path) + " · " + name;
        }
    }

    private String humanSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        return String.format("%.1f MB", size / 1024.0 / 1024.0);
    }

    private Path sessionLogFile() {
        return sessionLogDirectory == null ? null : sessionLogDirectory.resolve(AGENT_LOG_FILE);
    }

    private String sourceLabel(Path path) {
        return applicationLogFile != null && applicationLogFile.equals(path) ? "应用日志" : "会话日志";
    }

    /** Keeps the toggle-style buttons aligned with the actual file selected in the history list. */
    private void updateSourceButtons() {
        boolean applicationSelected = applicationLogFile != null && applicationLogFile.equals(selectedFile);
        setSelectedStyle(sessionButton, !applicationSelected);
        setSelectedStyle(applicationButton, applicationSelected);
    }

    private static void setSelectedStyle(Button button, boolean selected) {
        button.getStyleClass().removeAll("primary-btn", "ghost-btn");
        button.getStyleClass().add(selected ? "primary-btn" : "ghost-btn");
    }

    private java.nio.file.attribute.FileTime lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path);
        } catch (Exception ignored) {
            return java.nio.file.attribute.FileTime.fromMillis(0);
        }
    }

    private static class IntervalCell extends ListCell<Integer> {
        @Override
        protected void updateItem(Integer item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty || item == null ? null : item + "s");
        }
    }
}
