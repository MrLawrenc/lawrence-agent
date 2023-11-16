package com.github.mrlawrenc.monitor.write.impl;

import com.github.mrlawrenc.monitor.write.AbstractWriter;
import com.github.mrlawrenc.monitor.write.Writeable;
import com.github.mrlawrenc.monitor.write.WriterResp;
import lombok.extern.slf4j.Slf4j;

/**
 * @author : MrLawrenc
 * date  2020/7/6 22:30
 * <p>
 * 统计数据输出到mysql数据库
 */
@Slf4j
public class MysqlWriter extends AbstractWriter<String> {
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