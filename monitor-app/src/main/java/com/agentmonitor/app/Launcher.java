package com.agentmonitor.app;

import com.agentmonitor.core.util.AppLog;
import com.agentmonitor.model.config.ScopeSelection;

/** Plain entry point also works with JavaFX on an installed distribution's classpath. */
public final class Launcher {
    private Launcher() { }
    public static void main(String[] args) {
        AppLog.installUncaughtHandler();
        // Resolve the edit result before any UI rule can be partly applied.
        ScopeSelection.Change.values();
        AppLog.info("Desktop dependencies ready; scope model="+ScopeSelection.class.getProtectionDomain().getCodeSource());
        MainApp.main(args);
    }
}
