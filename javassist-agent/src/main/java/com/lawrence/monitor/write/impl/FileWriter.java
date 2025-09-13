package com.lawrence.monitor.write.impl;

import com.lawrence.monitor.write.AbstractWriter;
import com.lawrence.monitor.write.Writeable;
import com.lawrence.monitor.write.WriterResp;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * @author : MrLawrenc
 * date  2020/7/6 22:30
 * <p>
 * 文件缓存
 */
public class FileWriter extends AbstractWriter<String> {

    private static final String PROJECT_PATH = new File("").getAbsolutePath() + File.separator;


    private static final RandomAccessFile STACK_FILE;
    private static final RandomAccessFile STATISTICS_FILE;

    private long stackOff = 0;

    static {
        try {
            STACK_FILE = new RandomAccessFile(new File(PROJECT_PATH + "stackInfo.txt"), "rw");
            STATISTICS_FILE = new RandomAccessFile(new File(PROJECT_PATH + "statisticsFileInfo.txt"), "rw");
        } catch (FileNotFoundException e) {
            throw new RuntimeException("init target file fail");
        }
    }

    @Override
    protected void init(String s) {

    }

    @Override
    public void destroy() {
        try {
            STACK_FILE.close();
            STATISTICS_FILE.close();
        } catch (IOException e) {
            System.err.println("close file( fail" + e.getMessage());
        }
    }

    @Override
    public synchronized WriterResp write(Writeable writeable) {
        return null;
    }

}