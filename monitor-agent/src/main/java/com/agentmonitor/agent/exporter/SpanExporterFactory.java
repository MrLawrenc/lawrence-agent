package com.agentmonitor.agent.exporter;

import java.util.ArrayList;
import java.util.List;

import com.agentmonitor.agent.config.AgentConfig;
import com.agentmonitor.agent.log.AgentLog;
import com.agentmonitor.agent.protocol.JsonSpanPayloadSerializer;
import com.agentmonitor.agent.protocol.SpanPayloadSerializer;
import com.agentmonitor.agent.resource.AgentResource;
import com.agentmonitor.model.output.ExporterType;

/** Factory pattern: turns runtime configuration into a fan-out exporter graph. */
public final class SpanExporterFactory {

    public SpanExporter create(AgentConfig config) {
        SpanPayloadSerializer serializer = new JsonSpanPayloadSerializer();
        java.util.Map<String, String> resource = AgentResource.detect(config);
        List<SpanExporter> exporters = new ArrayList<>();
        if (!config.getExporters().contains(ExporterType.NETTY) && !config.getCaptureSessionId().isBlank()) exporters.add(new NettySpanExporter(config.getHost(), config.getPort(), serializer, resource, false));
        for (ExporterType exporterType : config.getExporters()) {
            try {
                switch (exporterType) {
                    case NETTY -> exporters.add(new NettySpanExporter(config.getHost(), config.getPort(), serializer,
                            resource));
                    case OTLP -> exporters.add(new OtlpHttpSpanExporter(config.getOtlpEndpoint(), resource));
                    case FILE -> {
                        if (config.getFileDirectory() == null || config.getFileDirectory().isBlank()) {
                            AgentLog.warn("[agent-monitor] file exporter skipped: fileDir is empty");
                        } else {
                            exporters.add(new FileSpanExporter(config.getFileDirectory(), config.getFileSessionDirectory(),
                                    config.getFileRotateBytes(),
                                    config.getFileRotateMillis(), config.isFileCompress(), serializer, resource));
                        }
                    }
                }
            } catch (Exception e) {
                AgentLog.error("[agent-monitor] exporter configuration rejected " + exporterType
                        + ": " + e.getMessage());
            }
        }
        return exporters.isEmpty() ? null : new AsyncSpanExporter(new CompositeSpanExporter(exporters),config.getOverhead());
    }
}
