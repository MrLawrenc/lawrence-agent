package com.lawrence.monitor.statistics;

import com.lawrence.monitor.trace.SpanNode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 统计基类，贯穿被监控方法的整个生命周期。
 * 职责：携带方法调用元信息 + 时序数据 + 异常信息 + 关联 Span。
 * Monitor 的 begin/exception/end 只负责填充此对象；
 * 输出由 StatisticsCollector 按类型分发给各 StatisticsExporter。
 */
@Getter
@Setter
@ToString
public abstract class Statistics implements Serializable {

    /** 调用链唯一 ID（同一次请求所有 Span 共享） */
    private final String traceId;

    /** 本次方法调用的 Span ID */
    private final String spanId;

    /** 执行类全限定名 */
    private String className;

    /** 执行方法名 */
    private String methodName;

    /** 方法参数 */
    private Object[] args;

    /** 方法开始时间（ms） */
    private long startTime;

    /** 方法结束时间（ms），通过 finish() 设置 */
    @Setter(lombok.AccessLevel.NONE)
    private long endTime;

    /** 耗时（ms） */
    @Setter(lombok.AccessLevel.NONE)
    private long costMs;

    /** 执行时抛出的异常，无异常则为 null */
    private Throwable error;

    /** 关联的 Span 节点，便于导出器获取调用树 */
    private SpanNode span;

    protected Statistics(String traceId, String spanId) {
        this.traceId = traceId;
        this.spanId = spanId;
    }

    /** 由 Monitor.end() 调用，设置结束时间并计算耗时 */
    public void finish(long endTimeMs) {
        this.endTime = endTimeMs;
        this.costMs = endTimeMs - this.startTime;
    }

    public boolean hasError() {
        return error != null;
    }
}