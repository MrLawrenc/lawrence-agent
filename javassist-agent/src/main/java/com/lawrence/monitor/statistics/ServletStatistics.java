package com.lawrence.monitor.statistics;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * Servlet 监控统计类
 */
@Getter
@Setter
@ToString(callSuper = true)
public class ServletStatistics extends Statistics {

    public ServletStatistics(String traceId, String spanId) {
        super(traceId, spanId);
    }

    /**
     * 请求的url地址
     */
    private String url;
    /**
     * 请求方式，如post get
     */
    private String method;

    private String urlData;
    private String bodyData;

    private int respStatus;

    public enum ReqType {
        POST(), GET();
    }
}