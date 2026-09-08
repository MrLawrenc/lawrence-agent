package com.agentmonitor.app.ui;

import java.util.function.Consumer;

import com.agentmonitor.app.model.JvmProcess;
import com.agentmonitor.app.service.JvmService;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class JvmListPane extends VBox {

    private final ObservableList<JvmProcess> items = FXCollections.observableArrayList();
    private final TableView<JvmProcess> table;
    private Consumer<JvmProcess> onSelected;

    public JvmListPane() {
        setSpacing(6);
        setPadding(new Insets(8));
        getStyleClass().add("side-panel");

        Label title = new Label("JVM 进程");
        title.getStyleClass().add("panel-title");

        table = new TableView<>(items);
        table.setPlaceholder(new Label("暂无 JVM 进程"));
        table.getStyleClass().add("jvm-table");
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<JvmProcess, String> pidCol = new TableColumn<>("PID");
        pidCol.setCellValueFactory(c -> c.getValue().pidProperty());
        pidCol.setPrefWidth(60);
        pidCol.setResizable(false);

        TableColumn<JvmProcess, String> nameCol = new TableColumn<>("主类 / 名称");
        nameCol.setCellValueFactory(c -> c.getValue().displayNameProperty());
        nameCol.setPrefWidth(200);

        table.getColumns().addAll(pidCol, nameCol);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, now) -> {
            if (now != null && onSelected != null) onSelected.accept(now);
        });

        Button refreshBtn = new Button("⟳ 刷新");
        refreshBtn.getStyleClass().add("action-btn");
        refreshBtn.setMaxWidth(Double.MAX_VALUE);
        refreshBtn.setOnAction(e -> refresh());

        HBox.setHgrow(refreshBtn, Priority.ALWAYS);

        getChildren().addAll(title, table, refreshBtn);
        refresh();
    }

    public void refresh() {
        JvmProcess selected = table.getSelectionModel().getSelectedItem();
        items.setAll(JvmService.listProcesses());
        if (selected != null) {
            items.stream()
                 .filter(p -> p.getPid().equals(selected.getPid()))
                 .findFirst()
                 .ifPresent(p -> table.getSelectionModel().select(p));
        }
    }

    public JvmProcess getSelectedProcess() {
        return table.getSelectionModel().getSelectedItem();
    }

    public void setOnSelected(Consumer<JvmProcess> handler) {
        this.onSelected = handler;
    }
}
