package com.agentmonitor.agent.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.agentmonitor.agent.config.AgentConfig;

class AgentResourceTest {

    @Test
    void configurationOverridesTheDetectedServiceIdentity() {
        AgentConfig config = AgentConfig.parse("pkg=example,resource.service.name=orders,"
                + "resource.service.version=2026.08,resource.deployment.environment=staging");

        Map<String, String> resource = AgentResource.detect(config);

        assertEquals("orders", resource.get("service.name"));
        assertEquals("2026.08", resource.get("service.version"));
        assertEquals("staging", resource.get("deployment.environment.name"));
        assertFalse(resource.get("process.pid").isBlank());
        assertEquals("com.agentmonitor.java-agent", resource.get("telemetry.sdk.name"));
    }
}
