package com.agentmonitor.app;

import com.agentmonitor.app.controller.MainController;
import com.agentmonitor.app.model.JvmProcess;
import com.agentmonitor.app.ui.AppIcons;
import com.agentmonitor.app.ui.JvmSelectDialog;
import com.agentmonitor.app.util.AppLog;

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

    private MainController controller;
    private Stage primaryStage;
    private Scene mainScene;
    private javafx.scene.layout.StackPane targetSelectionPlaceholder;

    @Override
    public void start(Stage stage) {
        AppLog.installUncaughtHandler();
        this.primaryStage = stage;
        primaryStage.setTitle("Java 性能监控工具");
        primaryStage.getIcons().add(AppIcons.monitorIcon());
        primaryStage.setMinWidth(1240);
        primaryStage.setMinHeight(720);
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

        mainScene = new Scene(targetSelectionPlaceholder, 1520, 820);
        mainScene.getStylesheets().add(
                getClass().getResource("/com/agentmonitor/app/main.css").toExternalForm());
        primaryStage.setScene(mainScene);
        primaryStage.show();

        showSelectDialog();
    }

    private void showSelectDialog() {
        // Do not leave controls bound to an exited target visible behind the selector.
        mainScene.setRoot(targetSelectionPlaceholder);
        JvmSelectDialog dialog = new JvmSelectDialog(primaryStage);
        dialog.showAndWait(this::showMain);
    }

    private void showMain(JvmProcess selected) {
        if (controller != null) controller.onWindowClose();
        controller = new MainController(selected);
        controller.setOnSwitchTarget(() -> Platform.runLater(this::showSelectDialog));
        mainScene.setRoot(controller.getRoot());
    }

    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "Agent Monitor");
        System.setProperty("com.apple.mrj.application.apple.menu.about.name", "Agent Monitor");
        AppLog.installUncaughtHandler();
        AppLog.info("launching JavaFX application");
        launch(args);
    }
}
