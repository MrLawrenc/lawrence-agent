package com.agentmonitor.app.ui;

import com.agentmonitor.app.controller.MainController;
import com.agentmonitor.model.jvm.JvmProcess;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import java.util.List;

/** Production controller and controls, isolated by fixture service adapters. Never attaches. */
public final class UIWorkbenchPreview extends Application {
    private MainController controller;
    @Override public void start(Stage stage) {
        stage.setTitle("Agent Monitor · 示例预览，不连接真实 JVM");
        stage.setMinWidth(1080); stage.setMinHeight(720);
        controller = new MainController(new JvmProcess("preview", "com.example.orders.OrderApplication  |  示例订单服务"));
        Scene scene = new Scene(controller.getRoot(), 1440, 900);
        scene.getStylesheets().add(getClass().getResource("/com/agentmonitor/app/main.css").toExternalForm());
        stage.setScene(scene); stage.setOnCloseRequest(e -> controller.onWindowClose()); stage.show();
        Label status=(Label)scene.lookup(".status-label");
        ((Button)scene.lookup("#capture-start")).setOnAction(e -> status.setText("示例预览不会启动采集，也不会连接真实 JVM。"));
        ((Button)scene.lookup("#capture-stop")).setOnAction(e -> status.setText("示例预览：没有运行中的采集。"));
        ((Button)scene.lookup("#capture-select-target")).setOnAction(e -> new JvmSelectDialog(stage).show(p -> status.setText("示例进程："+p.getDisplayName())));
        configureFixture(scene,40);
        if (getParameters().getRaw().contains("--verify")) PreviewChecks.run(stage);
    }
    private static void configureFixture(Scene scene,int attempts){
        var tree=(javafx.scene.control.TreeView<String>)scene.lookup("#scope-package-tree");
        var item=find(tree.getRoot(),"com.example.orders");
        if(item!=null){
            tree.getSelectionModel().select(item);
            ((Button)scene.lookup("#scope-include-package")).fire();
            ((Label)scene.lookup(".status-label")).setText("示例数据 · 无真实 JVM 连接");
        }else if(attempts>0){
            var delay=new javafx.animation.PauseTransition(javafx.util.Duration.millis(50));
            delay.setOnFinished(e->configureFixture(scene,attempts-1));delay.play();
        }
    }
    private static javafx.scene.control.TreeItem<String> find(javafx.scene.control.TreeItem<String> item,String name){
        if(item==null)return null;if(name.equals(item.getValue()))return item;
        for(var child:item.getChildren()){var match=find(child,name);if(match!=null)return match;}return null;
    }
    @Override public void stop(){if(controller!=null)controller.onWindowClose();}
    public static void main(String[] args) { launch(args); }
}
