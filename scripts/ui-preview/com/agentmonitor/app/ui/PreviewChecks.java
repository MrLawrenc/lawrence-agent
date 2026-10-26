package com.agentmonitor.app.ui;

import com.agentmonitor.app.model.CallNode;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** In-process render and interaction checks against the real controls. */
public final class PreviewChecks {
    private static final Path OUTPUT = Path.of("build/ui-preview");
    private static int failures;
    public static void run(Stage stage) {
        PauseTransition ready = new PauseTransition(Duration.seconds(1));
        ready.setOnFinished(event -> {
            try {
                capture(stage.getScene(), "workspace-wide");
                stage.setWidth(1240); stage.setHeight(740);
                PauseTransition resize = new PauseTransition(Duration.millis(250));
                resize.setOnFinished(resized -> {
                    try {
                        require(stage.getScene().getWidth() <= 1240, "Compact render uses the resized window");
                        capture(stage.getScene(), "workspace-compact");
                        checkCallTree(stage);
                        checkMetrics(stage);
                        checkSelector(stage);
                    } catch (Throwable error) { fail(error); finish(); }
                });
                resize.play();
            } catch (Throwable error) { fail(error); finish(); }
        });
        ready.play();
    }
    private static void checkCallTree(Stage owner) throws Exception {
        CallTreePane calls = new CallTreePane();
        CallNode root = new CallNode("com.example.OrderController", "create", "request-1", 128_000_000, false, 0);
        root.getChildren().add(new CallNode("com.example.OrderService", "validateOrder", "worker-2", 34_000_000, false, 1));
        calls.showOfflineReplay(List.of(root));
        Scene scene = scene(calls, 1020, 600, owner);
        TextField search = (TextField) calls.lookup(".search-field");
        TreeTableView<?> table = (TreeTableView<?>) calls.lookup(".call-tree-table");
        search.setText("validateOrder");
        require(table.getRoot().getChildren().size() == 1, "Child-call search preserves its root");
        capture(scene, "calls-child-match");
        search.setText("no-match");
        require(table.getRoot().getChildren().isEmpty(), "Unmatched search empties the table");
        require(calls.lookupAll(".empty-state-title").stream().anyMatch(n -> n.isVisible() && ((Label)n).getText().contains("没有匹配")), "No-match guidance is displayed");
        capture(scene, "calls-no-match");
        calls.clear();
        search.clear();
        calls.showOfflineReplay(List.of(root));
        require(table.getRoot().getChildren().size() == 1, "Replay can be loaded after clearing");
    }
    private static void checkMetrics(Stage owner) throws Exception {
        JvmMetricsPane metrics = new JvmMetricsPane();
        Scene scene = scene(metrics, 340, 620, owner);
        require(metrics.lookupAll(".metric-card-value").stream().allMatch(n -> ((Label)n).getText().equals("—")), "Unsampled metrics do not display fabricated zeroes");
        capture(scene, "metrics-waiting");
        metrics.update(new com.agentmonitor.app.model.JvmMetrics(10, 20, 40, 5, 10, 12, 6, 200, 210, 10, 3, 20, 0, ""));
        metrics.update(com.agentmonitor.app.model.JvmMetrics.unavailable("示例采集失败"));
        require(metrics.lookupAll(".metric-card-value").stream().allMatch(n -> ((Label)n).getText().equals("—") || ((Label)n).getText().equals("不可用")), "Failed sampling clears all stale metrics");
    }
    private static void checkSelector(Stage owner) {
        JvmSelectDialog selector = new JvmSelectDialog(owner);
        java.util.concurrent.atomic.AtomicInteger confirmations = new java.util.concurrent.atomic.AtomicInteger();
        selector.show(p -> confirmations.incrementAndGet());
        PauseTransition ready = new PauseTransition(Duration.millis(350));
        ready.setOnFinished(event -> {
            try {
                java.lang.reflect.Field field = JvmSelectDialog.class.getDeclaredField("dialog");
                field.setAccessible(true);
                Stage dialog = (Stage) field.get(selector);
                TextField search = (TextField) dialog.getScene().lookup(".search-field");
                ListView<?> list = (ListView<?>) dialog.getScene().lookup(".jvm-list-view");
                capture(dialog.getScene(), "selector");
                search.setText("NO_SUCH_PROCESS");
                require(list.getItems().isEmpty(), "Selector filters unmatched queries");
                capture(dialog.getScene(), "selector-no-match");
                search.clear();
                require(list.getItems().size() == 3, "Clearing the query restores all fixture processes");
                search.fireEvent(new javafx.scene.input.KeyEvent(javafx.scene.input.KeyEvent.KEY_PRESSED, "", "", javafx.scene.input.KeyCode.ENTER, false, false, false, false));
                require(confirmations.get() == 1 && !dialog.isShowing(), "Enter in the search field confirms the selected process once");
            } catch (Throwable error) { fail(error); }
            finish();
        });
        ready.play();
    }
    private static Scene scene(Node content, double width, double height, Stage owner) {
        Scene scene = new Scene(new StackPane(content), width, height);
        scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        scene.getRoot().applyCss(); scene.getRoot().layout();
        return scene;
    }
    private static void capture(Scene scene, String name) throws Exception {
        scene.getRoot().applyCss(); scene.getRoot().layout();
        WritableImage image = scene.getRoot().snapshot(null, null);
        BufferedImage png = new BufferedImage((int)image.getWidth(), (int)image.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < png.getHeight(); y++) for (int x = 0; x < png.getWidth(); x++) png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        Files.createDirectories(OUTPUT);
        ImageIO.write(png, "png", OUTPUT.resolve(name + ".png").toFile());
        System.out.println("RENDER " + OUTPUT.resolve(name + ".png"));
    }
    private static void require(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
        System.out.println("PASS " + name);
    }
    private static void fail(Throwable error) { failures++; error.printStackTrace(); }
    private static void finish() { System.out.println("UI_CHECKS_FAILURES=" + failures); Platform.exit(); if (failures > 0) System.exit(1); }
}
