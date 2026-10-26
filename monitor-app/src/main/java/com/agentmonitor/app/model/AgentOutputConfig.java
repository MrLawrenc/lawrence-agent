package com.agentmonitor.app.model;
import com.agentmonitor.model.config.MonitoringConfig;
public record AgentOutputConfig(String exportersArg, int fileRotateSizeMb, int fileRotateMinutes,
        boolean fileCompress, boolean captureArguments, boolean captureReturnValue, boolean captureSqlParameters,
        boolean jdbcEnabled, boolean httpEnabled, String serviceName, String serviceVersion, String deploymentEnvironment) {
    public static AgentOutputConfig defaults() { return from(MonitoringConfig.defaults()); }
    public static AgentOutputConfig from(MonitoringConfig config) {
        var output = config.output();
        return new AgentOutputConfig(String.join("|", output.exporters().stream().map(e -> e.configValue()).toList()),
                output.spanFiles().rotateSizeMb(), output.spanFiles().rotateMinutes(), output.spanFiles().compress(),
                output.capture().arguments(), output.capture().returnValue(), output.capture().sqlParameters(),
                config.dependencies().jdbc(), config.dependencies().http(), config.resource().serviceName(),
                config.resource().serviceVersion(), config.resource().deploymentEnvironment());
    }
}
