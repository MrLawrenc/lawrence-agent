package com.agentmonitor.model.output;

import java.util.Locale;
import java.util.Optional;

/** Stable output destinations shared by UI configuration and Agent exporter implementations. */
public enum ExporterType {
    NETTY("netty"),
    FILE("file");

    private final String configValue;

    ExporterType(String configValue) {
        this.configValue = configValue;
    }

    public String configValue() {
        return configValue;
    }

    public static Optional<ExporterType> fromConfig(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        for (ExporterType type : values()) {
            if (type.configValue.equals(normalized)) return Optional.of(type);
        }
        return Optional.empty();
    }
}
