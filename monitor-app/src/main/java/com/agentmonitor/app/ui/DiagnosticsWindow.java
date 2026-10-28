package com.agentmonitor.app.ui;

import com.agentmonitor.core.model.MonitoringSession;
import com.agentmonitor.core.service.MonitoringRuntime;
import java.util.function.Supplier;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Standalone host retained for the diagnostic evidence preview. The app embeds the same pane. */
public final class DiagnosticsWindow extends Stage {
    public DiagnosticsWindow(Window owner, String pid, Supplier<MonitoringSession> session,
                             Supplier<MonitoringRuntime> runtime) {
        initOwner(owner);
        setTitle("诊断分析 · JVM " + pid);
        DiagnosticsPane pane = new DiagnosticsPane(pid, session, runtime);
        Scene scene = new Scene(pane, 1120, 760);
        if (owner != null && owner.getScene() != null) scene.getStylesheets().addAll(owner.getScene().getStylesheets());
        setScene(scene);
        setMinWidth(800);
        setMinHeight(560);
        setOnHidden(event -> pane.close());
    }
}
