package com.agentmonitor.app.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

/** Frequent actions stay visible; file/session actions share native menus and source state. */
public final class WorkspaceActionBar extends VBox {
    private final HBox mainRow, extraRow=new HBox(), actions, menus;
    private final Region spacer=new Region();
    private final Node status;
    private final MenuButton config;
    private boolean compact;

    public WorkspaceActionBar(Node status,Button refresh,Button calls,Button importConfig,Button saveConfig,
                              Button logs,Button history,Button currentSession) {
        this.status=status;
        getStyleClass().add("workspace-action-bar");setId("workspace-actions");setSpacing(8);
        calls.getStyleClass().add("workspace-trace-button");calls.setId("workspace-show-traces");
        calls.setTooltip(new Tooltip("查看实时调用链或已加载的历史回放"));
        refresh.setId("workspace-refresh-scope");
        refresh.setTooltip(new Tooltip("重新扫描目标 JVM 已加载的包和类，保留当前采集规则"));
        logs.setText("日志");logs.setTooltip(new Tooltip("查看 Agent Monitor 运行日志"));logs.setId("workspace-open-logs");
        config=new MenuButton("配置");config.setId("workspace-config-menu");config.setGraphic(UiIcons.icon("file"));
        config.setTooltip(new Tooltip("导入或保存采集配置 YAML"));
        config.getItems().addAll(delegate("导入配置…","download",importConfig,"workspace-import-config"),
                delegate("保存配置…","save",saveConfig,"workspace-save-config"));
        var session=new MenuButton("会话");session.setId("workspace-session-menu");session.setGraphic(UiIcons.icon("folder"));
        session.setTooltip(new Tooltip("打开本次采集结果或加载历史会话"));
        var current=delegate("本次采集结果","folder",currentSession,"workspace-current-session");
        current.textProperty().bind(javafx.beans.binding.Bindings.when(currentSession.disabledProperty())
                .then("本次采集结果（停止后可用）").otherwise("本次采集结果"));
        session.getItems().addAll(current,new SeparatorMenuItem(),delegate("打开历史会话…","folder",history,"workspace-open-history"));
        for(MenuButton menu:new MenuButton[]{config,session}) {
            menu.getStyleClass().add("workspace-menu-button");menu.setGraphicTextGap(6);menu.setMnemonicParsing(false);
        }
        actions=new HBox(8,calls,refresh);actions.setAlignment(Pos.CENTER_LEFT);actions.setMinWidth(USE_PREF_SIZE);
        menus=new HBox(6,config,session,logs);menus.setAlignment(Pos.CENTER_RIGHT);menus.setMinWidth(USE_PREF_SIZE);
        HBox.setHgrow(spacer,Priority.ALWAYS);
        mainRow=new HBox(12,status,actions,spacer,menus);mainRow.setAlignment(Pos.CENTER_LEFT);
        extraRow.setAlignment(Pos.CENTER_RIGHT);
        getChildren().add(mainRow);
        setMinHeight(USE_PREF_SIZE);setMaxHeight(USE_PREF_SIZE);
    }
    public void addAdvancedConfigurationAction(Button source) {
        config.getItems().addAll(0,java.util.List.of(delegate("高级配置（YAML）…","edit",source,"workspace-advanced-config"),new SeparatorMenuItem()));
        config.setTooltip(new Tooltip("高级 YAML 自定义、导入与保存当前配置"));
    }
    private static MenuItem delegate(String text,String icon,Button source,String id) {
        MenuItem item=new MenuItem(text,UiIcons.icon(icon));item.setId(id);
        item.disableProperty().bind(source.disabledProperty());
        item.setOnAction(event -> source.fire());
        return item;
    }
    public void addDiagnosticAction(Button button) {
        button.setId("workspace-diagnostics");button.getStyleClass().add("ghost-btn");
        button.setTooltip(new Tooltip("查看指标时间线、线程与锁、SQL、异常、异步任务及历史对比"));
        menus.getChildren().add(0,button);requestLayout();
    }
    private boolean needsCompact(double width) {
        double required=status.prefWidth(-1)+actions.prefWidth(-1)+menus.prefWidth(-1)+48+snappedLeftInset()+snappedRightInset();
        return width>0 && width<Math.max(650,required);
    }
    @Override protected double computeMinWidth(double height) {
        return status.minWidth(-1)+actions.minWidth(-1)+24+snappedLeftInset()+snappedRightInset();
    }
    @Override protected double computePrefHeight(double width) {
        double main=Math.max(status.prefHeight(-1),Math.max(actions.prefHeight(-1),needsCompact(width<0 ? getWidth() : width)?0:menus.prefHeight(-1)));
        return snappedTopInset()+snappedBottomInset()+main+(needsCompact(width<0 ? getWidth() : width) ? getSpacing()+menus.prefHeight(-1) : 0);
    }
    @Override protected void layoutChildren() {
        boolean next=needsCompact(getWidth());
        if(next!=compact) {
            compact=next;
            if(compact){mainRow.getChildren().remove(menus);extraRow.getChildren().setAll(menus);getChildren().add(extraRow);}
            else {extraRow.getChildren().clear();getChildren().remove(extraRow);mainRow.getChildren().add(menus);}
        }
        super.layoutChildren();
    }
}
