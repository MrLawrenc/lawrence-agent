package com.agentmonitor.app.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** Persistent navigation; each page is created once so navigation preserves edits and selection. */
public final class WorkbenchPane extends BorderPane {
    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final ToggleGroup navigation = new ToggleGroup();
    private final VBox links = new VBox(5);
    private final VBox utilities = new VBox(5);
    private final Label title = new Label();
    private final Label description = new Label();
    private final StackPane content = new StackPane();
    private final HBox pageActions = new HBox(8);
    private final BorderPane page = new BorderPane();
    private String selected;

    public WorkbenchPane() {
        setId("workbench");
        getStyleClass().add("workbench");
        title.getStyleClass().add("workbench-title");
        title.setId("workbench-title");
        description.getStyleClass().add("workbench-description");
        description.setWrapText(true);
        Region headingSpace = new Region(); HBox.setHgrow(headingSpace, Priority.ALWAYS);
        HBox titleRow = new HBox(16, title, headingSpace, pageActions);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        pageActions.setAlignment(Pos.CENTER_RIGHT);
        pageActions.setMinWidth(Region.USE_PREF_SIZE);
        VBox heading = new VBox(5, titleRow, description);
        heading.getStyleClass().add("workbench-heading");
        page.setTop(heading);
        page.setCenter(content);
        page.setMinWidth(0);
        page.getStyleClass().add("workbench-page");
        Region space = new Region();
        VBox.setVgrow(space, Priority.ALWAYS);
        VBox rail = new VBox(20, links, space, utilities);
        rail.setId("workbench-navigation");
        rail.getStyleClass().add("workbench-navigation");
        rail.setMinWidth(160);
        rail.setPrefWidth(176);
        setLeft(rail);
        setCenter(page);
        javafx.event.EventHandler<KeyEvent> shortcuts = event -> {
            if (!event.isShortcutDown() || event.isAltDown() || event.isShiftDown()) return;
            if (event.getCode() == javafx.scene.input.KeyCode.F && SearchBox.focusIn(content)) {
                event.consume(); return;
            }
            int index = switch (event.getCode()) {
                case DIGIT1 -> 0; case DIGIT2 -> 1; case DIGIT3 -> 2;
                case DIGIT4 -> 3; case DIGIT5 -> 4; default -> -1;
            };
            if (index >= 0 && index < pages.size()) {
                selectPage(pages.keySet().stream().skip(index).findFirst().orElseThrow());
                event.consume();
            }
        };
        sceneProperty().addListener((o, before, scene) -> {
            if (before != null) before.removeEventFilter(KeyEvent.KEY_PRESSED, shortcuts);
            if (scene != null) scene.addEventFilter(KeyEvent.KEY_PRESSED, shortcuts);
        });
    }

    public void addPage(String id, String name, String hint, String icon, Supplier<Node> factory) {
        if (pages.containsKey(id)) throw new IllegalArgumentException("Duplicate page: " + id);
        ToggleButton button = new ToggleButton(name);
        button.setId("nav-" + id);
        button.setToggleGroup(navigation);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().add("workbench-nav-item");
        UiIcons.decorate(button, icon);
        button.setTooltip(new Tooltip(name + (pages.size() < 5 ? " · ⌘ / Ctrl + " + (pages.size() + 1) : "")));
        pages.put(id, new Page(name, hint, button, factory));
        button.setOnAction(event -> selectPage(id));
        links.getChildren().add(button);
    }

    public void addUtility(Button button, String icon) {
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.getStyleClass().add("workbench-utility");
        UiIcons.decorate(button, icon);
        utilities.getChildren().add(button);
    }

    public void selectPage(String id) {
        Page target = pages.get(id);
        if (target == null) throw new IllegalArgumentException("Unknown page: " + id);
        if (target.view == null) target.view = target.factory.get();
        if (!id.equals(selected)) content.getChildren().setAll(target.view);
        selected = id;
        target.button.setSelected(true);
        title.setText(target.name);
        description.setText(target.hint);
        pageActions.getChildren().setAll(target.actions == null ? java.util.List.of() : java.util.List.of(target.actions));
    }

    public void setPageActions(String id, Node actions) {
        Page target = pages.get(id);
        if (target == null) throw new IllegalArgumentException("Unknown page: " + id);
        target.actions = actions;
        if (id.equals(selected)) pageActions.getChildren().setAll(actions);
    }

    public String selectedPage() { return selected; }

    public void replacePage(String id, Node view) {
        Page target = pages.get(id);
        if (target == null) throw new IllegalArgumentException("Unknown page: " + id);
        target.view = view;
        if (id.equals(selected)) content.getChildren().setAll(view);
    }

    /** Keep global capture controls and navigation reachable during asynchronous work. */
    public void setOverlay(Node overlay) {
        StackPane layer = new StackPane(page, overlay);
        setCenter(layer);
    }

    public static HBox actions(Node... nodes) {
        HBox row = new HBox(8, nodes);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("page-actions");
        return row;
    }

    private static final class Page {
        final String name, hint;
        final ToggleButton button;
        final Supplier<Node> factory;
        Node view;
        Node actions;
        Page(String name, String hint, ToggleButton button, Supplier<Node> factory) {
            this.name = name; this.hint = hint; this.button = button; this.factory = factory;
        }
    }
}
