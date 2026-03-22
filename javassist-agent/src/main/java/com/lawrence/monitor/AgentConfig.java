package com.lawrence.monitor;

import com.lawrence.AttachMain;
import lombok.Data;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author : Lawrence
 * date  2023/11/10 17:06
 */
@Data
public class AgentConfig {
    private JdbcConfig jdbcConfig;
    private ServletConfig servletConfig;
    private LogConfig log;
    private TimingConfig timingConfig;

    /**
     * 全局输出配置（适用于所有 Monitor）
     */
    private OutputConfig outputConfig;

    public static AgentConfig init(String agentOps) {
        if (agentOps.endsWith(".yml")) {
            return loadFromYaml(AttachMain.class.getClassLoader().getResourceAsStream(agentOps));
        }
        Properties properties = parseProperties(agentOps);
        AgentConfig agentConfig = new AgentConfig();
        JdbcConfig jdbcConfig = new JdbcConfig();
        ServletConfig servletConfig = new ServletConfig();
        agentConfig.setJdbcConfig(jdbcConfig);
        agentConfig.setServletConfig(servletConfig);

        boolean enable = Boolean.parseBoolean(properties.getOrDefault("jdbc.enable", Boolean.FALSE.toString()).toString());
        String scanPackages = properties.getOrDefault("jdbc.scan-packages", "").toString();
        jdbcConfig.setEnable(enable);
        if (Objects.nonNull(scanPackages) && !scanPackages.trim().isEmpty()) {
            List<String> packages = Stream.of(scanPackages.split(",")).collect(Collectors.toList());
            jdbcConfig.setScanPackages(packages);
        }
        String driverClassesRaw = properties.getOrDefault("jdbc.driver-classes", "").toString().trim();
        if (!driverClassesRaw.isEmpty()) {
            jdbcConfig.setDriverClasses(Stream.of(driverClassesRaw.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
        }

        boolean servletEnable = Boolean.parseBoolean(properties.getOrDefault("servlet.enable", Boolean.FALSE.toString()).toString());

        String version = properties.getOrDefault("servlet.version", "8").toString();
        String servletScanPackages = properties.getOrDefault("servlet.scan-packages", "").toString();
        servletConfig.setEnable(servletEnable);
        servletConfig.setVersion(version);
        if (Objects.nonNull(servletScanPackages) && !servletScanPackages.trim().isEmpty()) {
            List<String> packages = Stream.of(scanPackages.split(",")).collect(Collectors.toList());
            servletConfig.setScanPackages(packages);
        }

        // 解析 timing 监控配置
        TimingConfig timingConfig = new TimingConfig();
        boolean timingEnable = Boolean.parseBoolean(
                properties.getOrDefault("timing.enable", Boolean.FALSE.toString()).toString());
        timingConfig.setEnable(timingEnable);
        String timingPackages = properties.getOrDefault("timing.packages", "").toString();
        if (!timingPackages.trim().isEmpty()) {
            timingConfig.setPackages(Stream.of(timingPackages.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
        }
        String timingExcludePackages = properties.getOrDefault("timing.exclude-packages", "").toString();
        if (!timingExcludePackages.trim().isEmpty()) {
            timingConfig.setExcludePackages(Stream.of(timingExcludePackages.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList()));
        }
        agentConfig.setTimingConfig(timingConfig);

        // 全局输出配置
        OutputConfig outputConfig = new OutputConfig();
        String outputModeRaw = properties.getOrDefault("output.mode", "CONSOLE").toString().trim();
        Set<OutputConfig.OutputMode> modeSet = new LinkedHashSet<>();
        for (String token : outputModeRaw.split(",")) {
            try {
                modeSet.add(OutputConfig.OutputMode.valueOf(token.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {}
        }
        if (modeSet.isEmpty()) modeSet.add(OutputConfig.OutputMode.CONSOLE);
        outputConfig.setModes(modeSet);
        String outputFile = properties.getOrDefault("output.file", "agent-output.log").toString().trim();
        if (!outputFile.isEmpty()) {
            outputConfig.setOutputFile(outputFile);
        }
        agentConfig.setOutputConfig(outputConfig);

        String level = properties.getProperty("log.level", "info");
        LogConfig logConfig = new LogConfig();
        logConfig.setLevel(level);
        agentConfig.setLog(logConfig);
        return agentConfig;
    }

    private static Properties parseProperties(String agentOps) {
        Properties properties = new Properties();
        try (InputStream is = AttachMain.class.getClassLoader().getResourceAsStream(agentOps)) {
            if (is == null) {
                throw new RuntimeException("agent config file not found: " + agentOps);
            }
            properties.load(is);
        } catch (IOException e) {
            throw new RuntimeException("agent config file not found: " + agentOps, e);
        }
        return properties;
    }

    static AgentConfig loadFromYaml(InputStream in) {
        Yaml yaml = new Yaml();
        return yaml.loadAs(in, AgentConfig.class);
    }

}