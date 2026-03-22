package com.lawrence.monitor;

import lombok.Data;

import java.util.List;

/**
 * 方法耗时监控配置（仅包含 timing 专属字段，输出方式见全局 {@link OutputConfig}）
 */
@Data
public class TimingConfig {

    /** 是否启用方法耗时监控 */
    private boolean enable;

    /**
     * 需要监控的包路径前缀列表（逗号分隔），如 com.lawrence.test,com.example.service
     * 只有归属于这些包下的类才会被监控
     */
    private List<String> packages;

    /**
     * 额外排除的包路径前缀列表（逗号分隔）
     * 默认已排除 java/javax/jakarta/org/sun/javassist/com.lawrence.monitor 等框架包
     */
    private List<String> excludePackages;
}
