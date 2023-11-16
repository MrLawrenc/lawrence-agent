package com.lawrence.monitor.write.impl;


import com.lawrence.monitor.write.AbstractWriter;
import com.lawrence.monitor.write.Writeable;
import com.lawrence.monitor.write.WriterResp;

/**
 * @author : MrLawrenc
 * date  2020/7/6 22:30
 */
public class RedisWriter  extends AbstractWriter<String> {
    @Override
    protected void init(String s) {

    }

    @Override
    public void destroy() {

    }

    @Override
    public WriterResp write(Writeable writeable) {
        return null;
    }
}