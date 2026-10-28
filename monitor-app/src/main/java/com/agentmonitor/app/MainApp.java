package com.agentmonitor.app;

import com.agentmonitor.app.controller.MainController;
import com.agentmonitor.model.jvm.JvmProcess;
import com.agentmonitor.app.ui.AppIcons;
import com.agentmonitor.app.ui.JvmSelectDialog;
import com.agentmonitor.core.util.AppLog;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.geometry.Pos;
import com.agentmonitor.app.ui.UiIcons;
import javafx.stage.Stage;

public class MainApp extends Application {

    private static final double SELECTOR_WIDTH = 740;
    private static final double SELECTOR_HEIGHT = 450;
    private static final double WORKBENCH_MIN_WIDTH = 1080;
    private static final double WORKBENCH_MIN_HEIGHT = 720;

    private MainController controller;
    private Stage primaryStage;
    private Scene mainScene;
    private javafx.scene.layout.StackPane targetSelectionPlaceholder;
    private double workbenchWidth = 1440;
    private double workbenchHeight = 900;
    private boolean showingWorkbench;

    @Override
    public void start(Stage stage) {
        AppLog.installUncaughtHandler();
        AppLog.info("UI runtime="+System.getProperty("java.home")+"; scope model="+
                com.agentmonitor.model.config.ScopeSelection.class.getProtectionDomain().getCodeSource());
        this.primaryStage = stage;
        primaryStage.setTitle("Agent Monitor");
        primaryStage.getIcons().add(AppIcons.monitorIcon());
        primaryStage.setMinWidth(680);
        primaryStage.setMinHeight(420);
        primaryStage.setOnCloseRequest(e -> {
            if (controller != null) controller.onWindowClose();
        });

        Label title = new Label("连接你的 Java 应用");
        title.getStyleClass().add("welcome-title");
        Label description = new Label("选择本机 JVM，配置监控范围，查看方法耗时与调用链。");
        description.getStyleClass().add("hint-label");
        Button select = new Button("选择 JVM 进程");
        select.getStyleClass().add("primary-btn");
        UiIcons.decorate(select, "arrow");
        select.setOnAction(e -> showSelectDialog());
        VBox welcome = new VBox(16, UiIcons.icon("monitor"), title, description, select);
        welcome.setAlignment(Pos.CENTER);
        targetSelectionPlaceholder = new javafx.scene.layout.StackPane(welcome);
        targetSelectionPlaceholder.getStyleClass().add("root-pane");

        mainScene = new Scene(targetSelectionPlaceholder, SELECTOR_WIDTH, SELECTOR_HEIGHT);
        mainScene.getStylesheets().add(
                getClass().getResource("/com/agentmonitor/app/main.css").toExternalForm());
        primaryStage.setScene(mainScene);
        primaryStage.show();

        showSelectDialog();
    }

    private void showSelectDialog() {
        if (showingWorkbench) {
            workbenchWidth = Math.max(WORKBENCH_MIN_WIDTH, primaryStage.getWidth());
            workbenchHeight = Math.max(WORKBENCH_MIN_HEIGHT, primaryStage.getHeight());
            showingWorkbench = false;
        }
        // Do not leave controls bound to an exited target visible behind the selector.
        mainScene.setRoot(targetSelectionPlaceholder);
        primaryStage.setMinWidth(680);
        primaryStage.setMinHeight(420);
        primaryStage.setWidth(SELECTOR_WIDTH);
        primaryStage.setHeight(SELECTOR_HEIGHT);
        primaryStage.centerOnScreen();
        JvmSelectDialog dialog = new JvmSelectDialog(primaryStage);
        dialog.showEmbedded(mainScene, this::showMain);
    }

    private void showMain(JvmProcess selected) {
        if (controller != null) controller.onWindowClose();
        controller = new MainController(selected);
        controller.setOnSwitchTarget(() -> Platform.runLater(this::showSelectDialog));
        mainScene.setRoot(controller.getRoot());
        showingWorkbench = true;
        primaryStage.setMinWidth(WORKBENCH_MIN_WIDTH);
        primaryStage.setMinHeight(WORKBENCH_MIN_HEIGHT);
        primaryStage.setWidth(workbenchWidth);
        primaryStage.setHeight(workbenchHeight);
        primaryStage.centerOnScreen();
    }

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "Agent Monitor");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "Agent Monitor");
        AppLog.installUncaughtHandler();
        AppLog.info("launching JavaFX application");
        launch(args);
    }
}
