package com.lawrence.monitor.write;

/**
 * @author : MrLawrenc
 * date  2020/7/6 21:34
 */
public abstract class AbstractWriter<T> implements Writer {
    /**
     * writer初始化方法
     *
     * @param t 初始化方法参数
     */
    protected abstract void init(T t);

}