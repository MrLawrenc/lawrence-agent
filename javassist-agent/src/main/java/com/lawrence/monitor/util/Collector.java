package com.lawrence.monitor.util;


import com.lawrence.monitor.stack.StackNode;
import com.lawrence.monitor.statistics.Statistics;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

/**
 * @author hz20035009-逍遥
 * date   2020/7/15 14:16
 * <p>
 * agent收集器
 */
@Slf4j
public class Collector {

    /**
     * 每一次执行流程会生成一个唯一id，该唯一id对应一系列统计{@link Statistics}信息和一条堆栈{@link StackNode}信息
     */
    private static final Map<String, ResultContainer> RESULT = new HashMap<>();


    static {
        //启动定时任务不断刷新 node tree
        new Timer("node-tree-update").schedule(new TimerTask() {
            @Override
            public void run() {
                Collector.RESULT.forEach((outerKey, outerValue) -> {
                    StackNode.Node head = buildStack(outerValue.getNodeList());
                    print(head);
                });
            }
        }, 0, 100 * 60);
    }

    public static void print(StackNode.Node root) {
        printHelper(root, "\t");
    }

    private static void printHelper(StackNode.Node root, String start) {
        if (root == null) {
            return;
        }
        String mid = start.substring(0, start.lastIndexOf("\t")) + "└---";
        System.out.println(mid + root.getId() + "[" + root.getParentId() + "] " + root.getClassName() + ":" + root.getMethodName());
        if (root.getChild() == null) {
            return;
        }
        for (StackNode.Node node : root.getChild()) {
            printHelper(node, start + "\t");
        }
    }

    public static void addStatistics(Statistics statistics) {
        ResultContainer resultContainer = getContainer(statistics.getId());
        List<Statistics> statisticsList = resultContainer.getStatisticsList();
        if (!statisticsList.contains(statistics)) {
            resultContainer.getStatisticsList().add(statistics);
        }
    }

    public static void addNode(String id, StackNode.Node node) {
        ResultContainer resultContainer = getContainer(id);
        List<StackNode.Node> nodeList = resultContainer.getNodeList();
        nodeList.add(node);
    }

    public static ResultContainer getContainer(String id) {
        ResultContainer resultContainer = RESULT.get(id);
        if (Objects.isNull(resultContainer)) {
            resultContainer = new ResultContainer();
            RESULT.put(id, resultContainer);
        }
        return resultContainer;
    }


    /**
     * 将当前堆栈链，追加到总链上
     *
     * @param currentChain 某一条堆堆栈执行链
     */
    private static StackNode.Node buildStack(List<StackNode.Node> currentChain) {
        log.info("start build stack tree,parent size:{} current size:{}", RESULT.size(), currentChain.size());
        log.info("start build stack tree,currentChain:{}", currentChain);
        StackNode.Node currentParent = currentChain.stream().filter(node -> node.getParentId() == null).findFirst().orElse(null);
        buildStack0(currentParent, currentChain);
        log.info("parent node end : {}", currentParent);
        return currentParent;
    }

    private static void buildStack0(StackNode.Node currentParent, List<StackNode.Node> currentChain) {
        if (Objects.isNull(currentChain)) {
            return;
        }
        List<StackNode.Node> child = currentChain.stream().filter(node -> Objects.equals(node.getParentId(), currentParent.getId())).toList();
        currentParent.setChild(child);
        log.info("parent node : {}", currentParent);
        if (Objects.nonNull(child)) {
            child.forEach(node -> buildStack0(node, currentChain));
        }
    }

    @Data
    public static class ResultContainer {
        /**
         * 额外统计信息
         */
        private List<Statistics> statisticsList = new ArrayList<>();
        /**
         * 堆栈统计信息
         */
        private List<StackNode.Node> nodeList = new ArrayList<>();
    }
}