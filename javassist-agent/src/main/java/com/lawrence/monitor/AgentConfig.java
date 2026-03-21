package com.lawrence.monitor;

import com.lawrence.AttachMain;
import com.lawrence.utils.log.LoggerFactory;
import lombok.Data;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.logging.Level;
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

    /**
     * 业务级自定义监控规则
     */
    private List<ClassMatchRule> businessClassRules;

    private LogConfig log;

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

        boolean servletEnable = Boolean.parseBoolean(properties.getOrDefault("servlet.enable", Boolean.FALSE.toString()).toString());

        String version = properties.getOrDefault("servlet.version", "8").toString();
        String servletScanPackages = properties.getOrDefault("servlet.scan-packages", "").toString();
        servletConfig.setEnable(servletEnable);
        servletConfig.setVersion(version);
        if (Objects.nonNull(servletScanPackages) && !servletScanPackages.trim().isEmpty()) {
            List<String> packages = Stream.of(scanPackages.split(",")).collect(Collectors.toList());
            servletConfig.setScanPackages(packages);
        }

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