package com.lawrence.monitor.statistics;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * 方法耗时监控统计类
 */
@Getter
@Setter
@ToString(callSuper = true)
public class TimingStatistics extends Statistics {

    public TimingStatistics(String traceId, String spanId) {
        super(traceId, spanId);
    }
}
