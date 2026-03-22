package com.lawrence.monitor.output;

import com.lawrence.monitor.trace.SpanNode;

public class ConsoleChainOutput extends AbstractTextChainOutput {

    @Override
    public void output(SpanNode root) {
        System.out.println(buildTextTree(root));
    }
}
