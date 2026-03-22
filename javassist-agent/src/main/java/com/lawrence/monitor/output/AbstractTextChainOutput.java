package com.lawrence.monitor.output;

import com.lawrence.monitor.trace.SpanNode;

import java.util.List;

public abstract class AbstractTextChainOutput implements ChainOutput {

    protected StringBuilder buildTextTree(SpanNode root) {
        StringBuilder sb = new StringBuilder();
        String threadName = Thread.currentThread().getName();
        long totalMs = root.durationMs();

        sb.append("\n");
        sb.append("+==================================================================+\n");
        sb.append(String.format("| [Timing Chain]  Thread: %-28s        |\n", shorten(threadName, 28)));
        sb.append(String.format("| Total: %-4d ms%51s|\n", totalMs, ""));
        sb.append("+==================================================================+\n");

        renderNode(sb, root, "", true);
        return sb;
    }

    private void renderNode(StringBuilder sb, SpanNode node, String prefix, boolean isLast) {
        long durationMs = node.durationMs();
        String connector = isLast ? "`-- " : "+-- ";
        String childPrefix = isLast ? "    " : "|   ";

        String status = node.isError() ? " [ERR]" : "";
        String simpleClass = simpleClassName(node.getClassName());

        String label = simpleClass + "#" + node.getMethodName() + status;
        int dotCount = Math.max(1, 60 - prefix.length() - connector.length() - label.length());
        String dots = " " + ".".repeat(dotCount) + " ";

        sb.append(prefix)
                .append(connector)
                .append(label)
                .append(dots)
                .append(durationMs).append(" ms\n");

        List<SpanNode> children = node.getChildren();
        for (int i = 0; i < children.size(); i++) {
            renderNode(sb, children.get(i), prefix + childPrefix, i == children.size() - 1);
        }
    }

    protected static String simpleClassName(String fullName) {
        int idx = fullName.lastIndexOf('.');
        return idx >= 0 ? fullName.substring(idx + 1) : fullName;
    }

    protected static String shorten(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "~";
    }
}
