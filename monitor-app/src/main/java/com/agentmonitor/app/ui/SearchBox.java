package com.agentmonitor.app.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.StackPane;

/** Shared search affordances while retaining the native field and its existing filter bindings. */
public final class SearchBox extends StackPane {
    public SearchBox(TextField field, String name, boolean primary) {
        getStyleClass().add("search-box");
        setMinWidth(100);
        setPrefWidth(field.getPrefWidth());
        field.getStyleClass().add("clearable-search");
        if (primary) field.getStyleClass().add("workbench-search-target");
        field.setAccessibleText(name);
        field.setTooltip(new Tooltip(name + (primary ? " · ⌘ / Ctrl + F" : "") + " · Esc 清除"));
        Button clear = new Button();
        clear.setGraphic(UiIcons.icon("close"));
        clear.getStyleClass().add("search-clear");
        clear.setAccessibleText("清除" + name);
        clear.setTooltip(new Tooltip("清除搜索（Esc）"));
        clear.visibleProperty().bind(field.textProperty().isNotEmpty());
        clear.managedProperty().bind(clear.visibleProperty());
        clear.setOnAction(event -> { field.clear(); field.requestFocus(); });
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && !field.getText().isEmpty()) {
                field.clear(); event.consume();
            }
        });
        getChildren().addAll(field, clear);
        StackPane.setAlignment(clear, Pos.CENTER_RIGHT);
        StackPane.setMargin(clear, new javafx.geometry.Insets(0, 4, 0, 0));
    }

    public static boolean focusIn(Node page) {
        for (Node candidate : page.lookupAll(".workbench-search-target")) {
            if (!(candidate instanceof TextField field) || field.isDisabled()) continue;
            Node ancestor = field;
            while (ancestor != null && ancestor != page && ancestor.isVisible()) ancestor = ancestor.getParent();
            if (ancestor == page && page.isVisible()) {
                field.requestFocus(); field.selectAll(); return true;
            }
        }
        return false;
    }
}
