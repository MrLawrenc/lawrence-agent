package com.lawrence.monitor.statistics;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.sql.ResultSet;

/**
 * JDBC 监控统计类
 */
@Getter
@Setter
@ToString(callSuper = true)
public class JdbcStatistics extends Statistics {
    public JdbcStatistics(String traceId, String spanId) {
        super(traceId, spanId);
    }

    /**
     * 连接信息
     */
    private String url;

    /**
     * 执行的sql语句 可能是预编译语句
     */
    private String sql;
    /**
     * sql查询结果 fix:销毁
     */
    private ResultSet resultSet;

    /**
     * sql insert update delete 影响的行数
     */
    private long count;
    /**
     * sql是否执行成功
     */
    private boolean success;

    /** 原始 Connection 对象（代理前） */
    private Object originalConnection;

    /** 代理后的 Connection 对象 */
    private Object proxiedConnection;
}