package com.lawrence.monitor.output;

import com.lawrence.monitor.trace.SpanNode;

public interface ChainOutput {
    void output(SpanNode root);
}
