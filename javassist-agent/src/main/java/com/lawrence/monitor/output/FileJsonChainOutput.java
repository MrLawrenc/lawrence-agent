package com.lawrence.monitor.output;

import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * 将调用链 JSON 追加写入文件，并在控制台仅打印文件路径。
 */
public class FileJsonChainOutput extends JsonChainOutput {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileJsonChainOutput.class);

    private final String filePath;

    public FileJsonChainOutput(String filePath) {
        this.filePath = filePath;
    }

    @Override
    protected void send(String json) {
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, true))) {
            pw.println(json);
        } catch (IOException e) {
            LOGGER.error("FileJsonChainOutput write failed [{}]: {}", filePath, e.getMessage());
        }
        LOGGER.info("[JSON chain written to: {}]", filePath);
    }
}
