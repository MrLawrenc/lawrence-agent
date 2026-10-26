package com.agentmonitor.app.ui;

import javafx.scene.control.ButtonBase;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Small, consistently stroked vector icons; color follows the owning control. */
public final class UiIcons {
    private UiIcons() {}

    public static StackPane icon(String name) {
        SVGPath path = new SVGPath();
        path.setContent(switch (name) {
            case "refresh" -> "M13 5 A5 5 0 1 0 13 11 M13 1 V5 H9";
            case "play" -> "M5 2 L13 8 L5 14 Z";
            case "stop" -> "M3 3 H13 V13 H3 Z";
            case "copy" -> "M6 5 H14 V14 H6 Z M10 5 V2 H2 V10 H6";
            case "edit" -> "M3 10 L11 2 L14 5 L6 13 L2 14 Z M9 4 L12 7";
            case "file" -> "M3 1 H10 L14 5 V15 H3 Z M10 1 V5 H14 M6 8 H11 M6 11 H11";
            case "folder" -> "M1 4 H6 L8 6 H15 V14 H1 Z M1 4 V2 H6 L8 4 H13 V6";
            case "download" -> "M8 1 V11 M4 7 L8 11 L12 7 M2 11 V15 H14 V11";
            case "save" -> "M2 1 H12 L15 4 V15 H2 Z M5 1 V6 H11 V1 M5 15 V10 H12 V15";
            case "switch" -> "M1 5 H14 M10 1 L14 5 L10 9 M15 12 H2 M6 8 L2 12 L6 16";
            case "monitor" -> "M1 2 H15 V12 H1 Z M5 15 H11 M8 12 V15 M3 7 H5 L7 4 L9 10 L11 7 H13";
            case "search" -> "M11 7 A4 4 0 1 1 3 7 A4 4 0 1 1 11 7 M10 10 L15 15";
            case "arrow" -> "M2 8 H14 M9 3 L14 8 L9 13";
            case "chevron" -> "M5 2 L11 8 L5 14";
            default -> "M3 4 H13 M3 8 H13 M3 12 H13";
        });
        path.getStyleClass().add("ui-icon-path");
        StackPane box = new StackPane(path);
        box.getStyleClass().add("ui-icon");
        box.setMinSize(16, 16);
        box.setPrefSize(16, 16);
        box.setMaxSize(16, 16);
        box.setMouseTransparent(true);
        return box;
    }

    public static void decorate(ButtonBase button, String name) {
        button.setGraphic(icon(name));
        button.setGraphicTextGap(7);
    }
}
