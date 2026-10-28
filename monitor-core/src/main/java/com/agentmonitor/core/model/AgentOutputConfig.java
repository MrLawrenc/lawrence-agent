package com.agentmonitor.core.model;
import com.agentmonitor.model.config.MonitoringConfig;
public record AgentOutputConfig(String exportersArg, int fileRotateSizeMb, int fileRotateMinutes,
        boolean fileCompress, boolean captureArguments, boolean captureReturnValue, boolean captureSqlParameters,
        boolean jdbcEnabled, boolean httpEnabled, String serviceName, String serviceVersion, String deploymentEnvironment, boolean diagnosticsEnabled, MonitoringConfig.Overhead overhead,boolean redisEnabled,boolean messagingEnabled) {
    public AgentOutputConfig(String exportersArg,int fileRotateSizeMb,int fileRotateMinutes,boolean fileCompress,boolean captureArguments,boolean captureReturnValue,boolean captureSqlParameters,boolean jdbcEnabled,boolean httpEnabled,String serviceName,String serviceVersion,String deploymentEnvironment,boolean diagnosticsEnabled,MonitoringConfig.Overhead overhead) {
        this(exportersArg,fileRotateSizeMb,fileRotateMinutes,fileCompress,captureArguments,captureReturnValue,captureSqlParameters,jdbcEnabled,httpEnabled,serviceName,serviceVersion,deploymentEnvironment,diagnosticsEnabled,overhead,true,true);
    }
    public AgentOutputConfig(String exportersArg,int fileRotateSizeMb,int fileRotateMinutes,boolean fileCompress,boolean captureArguments,boolean captureReturnValue,boolean captureSqlParameters,boolean jdbcEnabled,boolean httpEnabled,String serviceName,String serviceVersion,String deploymentEnvironment,boolean diagnosticsEnabled) {
        this(exportersArg,fileRotateSizeMb,fileRotateMinutes,fileCompress,captureArguments,captureReturnValue,captureSqlParameters,jdbcEnabled,httpEnabled,serviceName,serviceVersion,deploymentEnvironment,diagnosticsEnabled,MonitoringConfig.Overhead.defaults());
    }
    public AgentOutputConfig(String exportersArg,int fileRotateSizeMb,int fileRotateMinutes,boolean fileCompress,boolean captureArguments,boolean captureReturnValue,boolean captureSqlParameters,boolean jdbcEnabled,boolean httpEnabled,String serviceName,String serviceVersion,String deploymentEnvironment) {
        this(exportersArg,fileRotateSizeMb,fileRotateMinutes,fileCompress,captureArguments,captureReturnValue,captureSqlParameters,jdbcEnabled,httpEnabled,serviceName,serviceVersion,deploymentEnvironment,true,MonitoringConfig.Overhead.defaults());
    }
    public static AgentOutputConfig defaults() { return from(MonitoringConfig.defaults()); }
    public static AgentOutputConfig from(MonitoringConfig config) {
        var output = config.output();
        return new AgentOutputConfig(String.join("|", output.exporters().stream().map(e -> e.configValue()).toList()),
                output.spanFiles().rotateSizeMb(), output.spanFiles().rotateMinutes(), output.spanFiles().compress(),
                output.capture().arguments(), output.capture().returnValue(), output.capture().sqlParameters(),
                config.dependencies().jdbc(), config.dependencies().http(), config.resource().serviceName(),
                config.resource().serviceVersion(), config.resource().deploymentEnvironment(),config.diagnostics().enabled(),config.overhead(),config.dependencies().redis(),config.dependencies().messaging());
    }
}
