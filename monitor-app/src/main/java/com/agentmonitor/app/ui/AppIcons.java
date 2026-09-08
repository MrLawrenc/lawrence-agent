package com.agentmonitor.app.ui;

import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;

public final class AppIcons {

    private AppIcons() {
    }

    public static Image monitorIcon() {
        java.io.InputStream iconStream = AppIcons.class.getResourceAsStream("/com/agentmonitor/app/icon.png");
        if (iconStream != null) {
            Image resourceIcon = new Image(iconStream);
            if (!resourceIcon.isError()) {
                return resourceIcon;
            }
        }
        int size = 512;
        Canvas canvas = new Canvas(size, size);
        GraphicsContext graphics = canvas.getGraphicsContext2D();

        graphics.setFill(Color.web("#111827"));
        graphics.fillRoundRect(0, 0, size, size, 112, 112);

        graphics.setFill(Color.web("#1D4ED8"));
        graphics.fillRoundRect(54, 70, 404, 300, 28, 28);
        graphics.setFill(Color.web("#F8FAFC"));
        graphics.fillRoundRect(78, 96, 356, 248, 18, 18);

        graphics.setStroke(Color.web("#2563EB"));
        graphics.setLineWidth(18);
        graphics.setLineCap(StrokeLineCap.ROUND);
        graphics.setLineJoin(StrokeLineJoin.ROUND);
        graphics.beginPath();
        graphics.moveTo(104, 230);
        graphics.lineTo(154, 230);
        graphics.lineTo(184, 168);
        graphics.lineTo(220, 294);
        graphics.lineTo(256, 154);
        graphics.lineTo(296, 230);
        graphics.lineTo(356, 230);
        graphics.stroke();

        graphics.setFill(Color.web("#16A34A"));
        graphics.fillOval(364, 206, 34, 34);

        graphics.setFill(Color.web("#334155"));
        graphics.fillRoundRect(214, 370, 84, 42, 10, 10);
        graphics.fillRoundRect(168, 412, 176, 24, 12, 12);

        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        WritableImage image = new WritableImage(size, size);
        canvas.snapshot(params, image);
        return image;
    }

}
