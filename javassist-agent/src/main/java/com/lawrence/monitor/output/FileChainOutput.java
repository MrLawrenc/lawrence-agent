package com.lawrence.monitor.output;

import com.lawrence.utils.log.Logger;
import com.lawrence.utils.log.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;

public class FileChainOutput extends AbstractTextChainOutput {

    private static final Logger LOGGER = LoggerFactory.getLogger(FileChainOutput.class);

    private final String filePath;

    public FileChainOutput(String filePath) {
        this.filePath = filePath;
    }

    @Override
    public void output(com.lawrence.monitor.trace.SpanNode root) {
        StringBuilder sb = buildTextTree(root);
        sb.insert(1, LocalDateTime.now() + "\n");
        try (PrintWriter pw = new PrintWriter(new FileWriter(filePath, true))) {
            pw.println(sb);
        } catch (IOException e) {
            LOGGER.error("write timing chain to file failed: {}", e.getMessage(), e);
        }
    }
}
