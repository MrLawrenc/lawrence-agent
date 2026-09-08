package com.agentmonitor.app.model;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class JvmProcess {

    private final StringProperty pid;
    private final StringProperty displayName;

    public JvmProcess(String pid, String displayName) {
        this.pid = new SimpleStringProperty(pid);
        this.displayName = new SimpleStringProperty(displayName);
    }

    public String getPid() { return pid.get(); }
    public StringProperty pidProperty() { return pid; }

    public String getDisplayName() { return displayName.get(); }
    public StringProperty displayNameProperty() { return displayName; }

    @Override
    public String toString() { return pid.get() + " - " + displayName.get(); }
}
