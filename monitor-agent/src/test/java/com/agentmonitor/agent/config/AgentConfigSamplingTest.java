package com.agentmonitor.agent.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.agentmonitor.model.config.MonitoringConfig.TailOverflowPolicy;

class AgentConfigSamplingTest {

    @Test
    void directArgumentsOverrideEverySamplingDefault() {
        AgentConfig config = AgentConfig.parse("pkg=example.,inclCls=example.StandaloneService,sampling.rate=37,sampling.slowThreshold=0,"
                + "sampling.tailMaxBufferedSpans=2048,sampling.tailMaxBufferedSizeMb=8,"
                + "sampling.tailOverflowPolicy=drop");

        assertEquals(37, config.getSamplingRatePercent());
        assertEquals(0, config.getSlowThresholdMillis());
        assertEquals(2048, config.getTailMaxBufferedSpans());
        assertEquals(8, config.getTailMaxBufferedSizeMb());
        assertEquals(TailOverflowPolicy.DROP, config.getTailOverflowPolicy());
        assertArrayEquals(new String[] { "example.StandaloneService" }, config.getIncludeClasses());
    }

    @Test
    void propertiesFileUsesTheAttachPropertyNamesAndRejectsInvalidPercentages() throws Exception {
        Path properties = Files.createTempFile("agent-monitor-sampling-", ".properties");
        Files.writeString(properties, "pkg=example.\nsamplingRate=101\nslowThreshold=125\n"
                + "sampling.tailMaxBufferedSpans=4096\n"
                + "sampling.tailMaxBufferedSizeMb=16\n"
                + "sampling.tailOverflowPolicy=promote\n");

        AgentConfig config = AgentConfig.parse("cfg=" + properties);

        assertEquals(10, config.getSamplingRatePercent());
        assertEquals(125, config.getSlowThresholdMillis());
        assertEquals(4096, config.getTailMaxBufferedSpans());
        assertEquals(16, config.getTailMaxBufferedSizeMb());
        assertEquals(TailOverflowPolicy.PROMOTE, config.getTailOverflowPolicy());
    }

    @Test
    void rawCaptureDefaultsAreEnabledForPersonalMonitoring() {
        AgentConfig config = AgentConfig.parse("pkg=example.");

        assertTrue(config.isCaptureArguments());
        assertTrue(config.isCaptureReturnValue());
        assertTrue(config.isCaptureSqlParameters());
        assertTrue(config.isJdbcEnabled());
        assertTrue(config.isHttpEnabled());
        assertEquals(512, config.getTailMaxBufferedSpans());
        assertEquals(1, config.getTailMaxBufferedSizeMb());
        assertEquals(TailOverflowPolicy.PROMOTE, config.getTailOverflowPolicy());
    }
}
