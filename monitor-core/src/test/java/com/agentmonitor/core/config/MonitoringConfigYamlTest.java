package com.agentmonitor.core.config;
import static org.junit.jupiter.api.Assertions.*;
import com.agentmonitor.model.config.MonitoringConfig;
import org.junit.jupiter.api.Test;
class MonitoringConfigYamlTest {
    private static final String BASE = "version: 2\nscope:\n  includePackages: [com.example]\n";
    @Test void writesAndReadsTheEntireTypedModelWithoutChangingDefaults() {
        MonitoringConfig defaults = MonitoringConfig.defaults();
        var config = defaults.withScope(new MonitoringConfig.Scope(java.util.List.of("com.example"), java.util.List.of(),
                defaults.scope().excludeConditions(), defaults.scope().excludeMethods()));
        assertEquals(config, MonitoringConfigYaml.parse(MonitoringConfigYaml.write(config)));
    }
    @Test void emptyExclusionsArePreservedAndAnExactClassDoesNotNeedAPackage() {
        var config = MonitoringConfigYaml.parse("version: 2\nscope:\n  includePackages: []\n  includeClasses: [com.example.Service]\n  excludeMethods: []\n  excludeConditions: []\n");
        assertTrue(config.scope().excludeMethods().isEmpty());
        assertTrue(config.scope().excludeConditions().isEmpty());
        assertEquals(java.util.List.of("com.example.Service"), config.scope().includeClasses());
        assertEquals(10, config.sampling().ratePercent());
    }
    @Test void rejectsTyposDuplicateFieldsCoercionsAndInvalidRanges() {
        for (String suffix : java.util.List.of("sampling:\n  ratePercent: 101\n", "sampling:\n  ratePercent: '10'\n",
                "sampling:\n  typo: 1\n", "output:\n  exporters: []\n", "output:\n  exporters: [file]\n",
                "dependencies:\n  jdbc: 'false'\n", "version: 2\n", "sampling:\n  tailOverflowPolicy: ignore\n")) {
            assertThrows(IllegalArgumentException.class, () -> MonitoringConfigYaml.parse(BASE + suffix), suffix);
        }
    }
    @Test void acceptsTheBundledDocumentAndRejectsMultipleDocuments() throws Exception {
        var sample = java.nio.file.Path.of("..", "monitor-agent", "src", "main", "resources", "sample-agent.yml");
        assertEquals(2, MonitoringConfigYaml.parse(java.nio.file.Files.readString(sample)).schemaVersion());
        assertThrows(IllegalArgumentException.class, () -> MonitoringConfigYaml.parse(BASE + "---\n" + BASE));
    }
}
