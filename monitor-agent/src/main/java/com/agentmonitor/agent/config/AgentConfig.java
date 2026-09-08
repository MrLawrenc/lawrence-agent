package com.agentmonitor.agent.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Properties;

import com.agentmonitor.agent.log.AgentLog;

import com.agentmonitor.model.config.MonitoringConfig;
import com.agentmonitor.model.config.MonitoringConfig.TailOverflowPolicy;
import com.agentmonitor.model.output.ExporterType;

/** Immutable runtime configuration supplied through direct agent arguments or a properties file. */
public final class AgentConfig {

    private static final String CONFIG_FILE_PREFIX = "cfg=";
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 19_999;
    private static final long DEFAULT_FILE_ROTATE_BYTES = 100L * 1024 * 1024;
    private static final long DEFAULT_FILE_ROTATE_MILLIS = 10L * 60 * 1_000;
    private static final int DEFAULT_SAMPLING_RATE_PERCENT = MonitoringConfig.DEFAULT_SAMPLING_RATE_PERCENT;
    private static final long DEFAULT_SLOW_THRESHOLD_MILLIS = MonitoringConfig.DEFAULT_TAIL_CAPTURE_THRESHOLD_MILLIS;
    private static final int DEFAULT_TAIL_MAX_BUFFERED_SPANS = MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SPANS;
    private static final int DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB = MonitoringConfig.DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB;
    private static final TailOverflowPolicy DEFAULT_TAIL_OVERFLOW_POLICY = MonitoringConfig.DEFAULT_TAIL_OVERFLOW_POLICY;
    private static final EnumSet<ExporterType> DEFAULT_EXPORTERS = EnumSet.of(ExporterType.NETTY);

    private final String[] packagePrefixes;
    private final String[] includeClasses;
    private final String host;
    private final int port;
    private final String jarPath;
    private final String[] excludeConditions;
    private final String[] excludeMethods;
    private final EnumSet<ExporterType> exporters;
    private final String fileDirectory;
    private final String fileSessionDirectory;
    private final String agentLogDirectory;
    private final long fileRotateBytes;
    private final long fileRotateMillis;
    private final boolean fileCompress;
    private final boolean captureArguments;
    private final boolean captureReturnValue;
    private final boolean captureSqlParameters;
    private final boolean jdbcEnabled;
    private final boolean httpEnabled;
    private final String serviceName;
    private final String serviceVersion;
    private final String deploymentEnvironment;
    private final int samplingRatePercent;
    private final long slowThresholdMillis;
    private final int tailMaxBufferedSpans;
    private final int tailMaxBufferedSizeMb;
    private final TailOverflowPolicy tailOverflowPolicy;

    private AgentConfig(String[] packagePrefixes, String[] includeClasses, String host, int port, String jarPath,
                        String[] excludeConditions, String[] excludeMethods,
                        EnumSet<ExporterType> exporters, String fileDirectory, String fileSessionDirectory,
                        String agentLogDirectory,
                        long fileRotateBytes, long fileRotateMillis, boolean fileCompress,
                        boolean captureArguments, boolean captureReturnValue, boolean captureSqlParameters,
                        boolean jdbcEnabled, boolean httpEnabled,
                        String serviceName, String serviceVersion, String deploymentEnvironment,
                        int samplingRatePercent, long slowThresholdMillis,
                        int tailMaxBufferedSpans, int tailMaxBufferedSizeMb,
                        TailOverflowPolicy tailOverflowPolicy) {
        this.packagePrefixes = packagePrefixes;
        this.includeClasses = includeClasses;
        this.host = host;
        this.port = port;
        this.jarPath = jarPath;
        this.excludeConditions = excludeConditions;
        this.excludeMethods = excludeMethods;
        this.exporters = exporters.clone();
        this.fileDirectory = fileDirectory;
        this.fileSessionDirectory = fileSessionDirectory;
        this.agentLogDirectory = agentLogDirectory;
        this.fileRotateBytes = fileRotateBytes;
        this.fileRotateMillis = fileRotateMillis;
        this.fileCompress = fileCompress;
        this.captureArguments = captureArguments;
        this.captureReturnValue = captureReturnValue;
        this.captureSqlParameters = captureSqlParameters;
        this.jdbcEnabled = jdbcEnabled;
        this.httpEnabled = httpEnabled;
        this.serviceName = safe(serviceName);
        this.serviceVersion = safe(serviceVersion);
        this.deploymentEnvironment = safe(deploymentEnvironment);
        this.samplingRatePercent = samplingRatePercent;
        this.slowThresholdMillis = slowThresholdMillis;
        this.tailMaxBufferedSpans = tailMaxBufferedSpans;
        this.tailMaxBufferedSizeMb = tailMaxBufferedSizeMb;
        this.tailOverflowPolicy = tailOverflowPolicy;
    }

    public static AgentConfig parse(String args) {
        if (args != null && args.startsWith(CONFIG_FILE_PREFIX)) {
            AgentConfig config = parseConfigFile(args.substring(CONFIG_FILE_PREFIX.length()).trim());
            if (config != null) return config;
        }
        return parseDirectArguments(args);
    }

    private static AgentConfig parseDirectArguments(String args) {
        String[] packages = new String[0];
        String[] includedClasses = new String[0];
        String host = DEFAULT_HOST;
        int port = DEFAULT_PORT;
        String jar = "";
        String[] excludes = new String[0];
        String[] methodExcludes = new String[0];
        EnumSet<ExporterType> exporters = DEFAULT_EXPORTERS.clone();
        String fileDirectory = "";
        String fileSessionDirectory = "";
        String agentLogDirectory = "";
        long fileRotateBytes = DEFAULT_FILE_ROTATE_BYTES;
        long fileRotateMillis = DEFAULT_FILE_ROTATE_MILLIS;
        boolean fileCompress = false;
        boolean captureArguments = true;
        boolean captureReturnValue = true;
        boolean captureSqlParameters = true;
        boolean jdbcEnabled = true;
        boolean httpEnabled = true;
        String serviceName = "";
        String serviceVersion = "";
        String deploymentEnvironment = "";
        int samplingRatePercent = DEFAULT_SAMPLING_RATE_PERCENT;
        long slowThresholdMillis = DEFAULT_SLOW_THRESHOLD_MILLIS;
        int tailMaxBufferedSpans = DEFAULT_TAIL_MAX_BUFFERED_SPANS;
        int tailMaxBufferedSizeMb = DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB;
        TailOverflowPolicy tailOverflowPolicy = DEFAULT_TAIL_OVERFLOW_POLICY;

        if (args != null && !args.isBlank()) {
            for (String part : args.split(",")) {
                String[] keyValue = part.split("=", 2);
                if (keyValue.length != 2) continue;
                String key = keyValue[0].trim();
                String value = keyValue[1].trim();
                switch (key) {
                    case "pkg" -> packages = splitPipe(value);
                    case "inclCls" -> includedClasses = splitPipe(value);
                    case "host" -> host = value;
                    case "jar" -> jar = value;
                    case "excl" -> excludes = splitPipe(value);
                    case "mexcl" -> methodExcludes = splitPipe(value);
                    case "exporters", "exporter" -> exporters = parseExporters(value);
                    case "fileDir" -> fileDirectory = value;
                    case "fileSessionDir" -> fileSessionDirectory = value;
                    case "agentLogDir" -> agentLogDirectory = value;
                    case "fileRotateSizeMb" -> fileRotateBytes = megabytes(value, fileRotateBytes);
                    case "fileRotateMinutes" -> fileRotateMillis = minutes(value, fileRotateMillis);
                    case "fileCompress" -> fileCompress = Boolean.parseBoolean(value);
                    case "captureArgs" -> captureArguments = Boolean.parseBoolean(value);
                    case "captureReturnValue" -> captureReturnValue = Boolean.parseBoolean(value);
                    case "captureSqlParameters", "capture.sqlParameters" -> captureSqlParameters = Boolean.parseBoolean(value);
                    case "dependency.jdbc" -> jdbcEnabled = Boolean.parseBoolean(value);
                    case "dependency.http" -> httpEnabled = Boolean.parseBoolean(value);
                    case "resource.service.name" -> serviceName = value;
                    case "resource.service.version" -> serviceVersion = value;
                    case "resource.deployment.environment" -> deploymentEnvironment = value;
                    case "samplingRate", "sampling.rate" -> samplingRatePercent = samplingRate(value,
                            samplingRatePercent);
                    case "slowThreshold", "sampling.slowThreshold" -> slowThresholdMillis = nonNegativeLong(value,
                            slowThresholdMillis);
                    case "tailMaxBufferedSpans", "sampling.tailMaxBufferedSpans" -> tailMaxBufferedSpans =
                            positiveInt(value, tailMaxBufferedSpans);
                    case "tailMaxBufferedSizeMb", "sampling.tailMaxBufferedSizeMb" -> tailMaxBufferedSizeMb =
                            positiveInt(value, tailMaxBufferedSizeMb);
                    case "tailOverflowPolicy", "sampling.tailOverflowPolicy" -> tailOverflowPolicy =
                            tailOverflowPolicy(value, tailOverflowPolicy);
                    case "port" -> port = parseInt(value, port);
                    default -> { }
                }
            }
        }
        return new AgentConfig(packages, includedClasses, host, port, jar, excludes, methodExcludes, exporters,
                fileDirectory, fileSessionDirectory, agentLogDirectory,
                fileRotateBytes, fileRotateMillis, fileCompress,
                captureArguments, captureReturnValue, captureSqlParameters, jdbcEnabled, httpEnabled,
                serviceName, serviceVersion, deploymentEnvironment,
                samplingRatePercent, slowThresholdMillis,
                tailMaxBufferedSpans, tailMaxBufferedSizeMb, tailOverflowPolicy);
    }

    private static AgentConfig parseConfigFile(String file) {
        try (InputStream input = Files.newInputStream(Path.of(file))) {
            Properties properties = new Properties();
            properties.load(input);
            return new AgentConfig(
                    splitPipe(properties.getProperty("pkg", "")),
                    splitPipe(properties.getProperty("inclCls", "")),
                    properties.getProperty("host", DEFAULT_HOST).trim(),
                    parseInt(properties.getProperty("port", ""), DEFAULT_PORT),
                    properties.getProperty("jar", "").trim(),
                    splitPipe(properties.getProperty("excl", "")),
                    splitPipe(properties.getProperty("mexcl", "")),
                    parseExporters(properties.getProperty("exporters",
                            properties.getProperty("exporter", ExporterType.NETTY.configValue()))),
                    properties.getProperty("fileDir", "").trim(),
                    properties.getProperty("fileSessionDir", "").trim(),
                    properties.getProperty("agentLogDir", "").trim(),
                    megabytes(properties.getProperty("fileRotateSizeMb", ""), DEFAULT_FILE_ROTATE_BYTES),
                    minutes(properties.getProperty("fileRotateMinutes", ""), DEFAULT_FILE_ROTATE_MILLIS),
                    Boolean.parseBoolean(properties.getProperty("fileCompress", "false").trim()),
                    Boolean.parseBoolean(properties.getProperty("captureArgs", "true").trim()),
                    Boolean.parseBoolean(properties.getProperty("captureReturnValue", "true").trim()),
                    Boolean.parseBoolean(properties.getProperty("captureSqlParameters",
                            properties.getProperty("capture.sqlParameters", "true")).trim()),
                    Boolean.parseBoolean(properties.getProperty("dependency.jdbc", "true").trim()),
                    Boolean.parseBoolean(properties.getProperty("dependency.http", "true").trim()),
                    properties.getProperty("resource.service.name", "").trim(),
                    properties.getProperty("resource.service.version", "").trim(),
                    properties.getProperty("resource.deployment.environment", "").trim(),
                    samplingRate(properties.getProperty("samplingRate",
                            properties.getProperty("sampling.rate", "")), DEFAULT_SAMPLING_RATE_PERCENT),
                    nonNegativeLong(properties.getProperty("slowThreshold",
                            properties.getProperty("sampling.slowThreshold", "")),
                            DEFAULT_SLOW_THRESHOLD_MILLIS),
                    positiveInt(properties.getProperty("tailMaxBufferedSpans",
                            properties.getProperty("sampling.tailMaxBufferedSpans", "")),
                            DEFAULT_TAIL_MAX_BUFFERED_SPANS),
                    positiveInt(properties.getProperty("tailMaxBufferedSizeMb",
                            properties.getProperty("sampling.tailMaxBufferedSizeMb", "")),
                            DEFAULT_TAIL_MAX_BUFFERED_SIZE_MB),
                    tailOverflowPolicy(properties.getProperty("tailOverflowPolicy",
                            properties.getProperty("sampling.tailOverflowPolicy", "")),
                            DEFAULT_TAIL_OVERFLOW_POLICY));
        } catch (Exception e) {
            // Configuration is read before the session log destination is known.  Do not
            // print into the monitored business process when a malformed file is supplied.
            AgentLog.error("[agent-monitor] config file read failed: " + e.getMessage());
            return null;
        }
    }

    public String[] getPackagePrefixes() { return packagePrefixes.clone(); }
    public String[] getIncludeClasses() { return includeClasses.clone(); }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getJarPath() { return jarPath; }
    public String[] getExcludeConditions() { return excludeConditions.clone(); }
    public String[] getExcludeMethods() { return excludeMethods.clone(); }
    public EnumSet<ExporterType> getExporters() { return exporters.clone(); }
    public String getFileDirectory() { return fileDirectory; }
    public String getFileSessionDirectory() { return fileSessionDirectory; }
    public String getAgentLogDirectory() { return agentLogDirectory; }
    public long getFileRotateBytes() { return fileRotateBytes; }
    public long getFileRotateMillis() { return fileRotateMillis; }
    public boolean isFileCompress() { return fileCompress; }
    public boolean isCaptureArguments() { return captureArguments; }
    public boolean isCaptureReturnValue() { return captureReturnValue; }
    public boolean isCaptureSqlParameters() { return captureSqlParameters; }
    public boolean isJdbcEnabled() { return jdbcEnabled; }
    public boolean isHttpEnabled() { return httpEnabled; }
    public String getServiceName() { return serviceName; }
    public String getServiceVersion() { return serviceVersion; }
    public String getDeploymentEnvironment() { return deploymentEnvironment; }
    public int getSamplingRatePercent() { return samplingRatePercent; }
    public long getSlowThresholdMillis() { return slowThresholdMillis; }
    public int getTailMaxBufferedSpans() { return tailMaxBufferedSpans; }
    public int getTailMaxBufferedSizeMb() { return tailMaxBufferedSizeMb; }
    public TailOverflowPolicy getTailOverflowPolicy() { return tailOverflowPolicy; }

    private static String[] splitPipe(String value) {
        return value == null || value.isBlank() ? new String[0] : value.trim().split("\\|");
    }

    private static EnumSet<ExporterType> parseExporters(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_EXPORTERS.clone();
        EnumSet<ExporterType> exporters = EnumSet.noneOf(ExporterType.class);
        for (String value : raw.split("[,|]")) ExporterType.fromConfig(value).ifPresent(exporters::add);
        return exporters;
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value.trim()); }
        catch (Exception ignored) { return fallback; }
    }

    private static int samplingRate(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= 0 && parsed <= 100 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long nonNegativeLong(String value, long fallback) {
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed >= 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static int positiveInt(String value, int fallback) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static TailOverflowPolicy tailOverflowPolicy(String value, TailOverflowPolicy fallback) {
        try {
            return value == null || value.isBlank() ? fallback : TailOverflowPolicy.fromConfig(value);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private static long megabytes(String value, long fallback) {
        try {
            long megabytes = Long.parseLong(value.trim());
            return megabytes > 0 ? Math.multiplyExact(megabytes, 1024L * 1024) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long minutes(String value, long fallback) {
        try {
            long minutes = Long.parseLong(value.trim());
            return minutes > 0 ? Math.multiplyExact(minutes, 60L * 1_000) : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

}
