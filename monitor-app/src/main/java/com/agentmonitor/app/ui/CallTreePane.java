package com.agentmonitor.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.agentmonitor.app.model.CallNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.cell.TreeItemPropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class CallTreePane extends VBox implements AutoCloseable {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    /** Keep the output dialog readable when a captured call tree is deeply nested. */
    private static final int DEFAULT_EXPANDED_CALL_DEPTH = 3;

    private final TreeTableView<CallNode> treeTable;
    private final TreeItem<CallNode> rootItem;
    private final Label titleLabel;
    private final TextField searchField;
    private final Button pauseOutputButton;
    private final VBox emptyState;
    private final javafx.scene.control.SplitPane contentSplit=new javafx.scene.control.SplitPane();
    private final CallDetailPane detailPane;
    private final Button detailsToggle=new Button("调用详情");
    private VBox waterfallView;
    private final Button waterfall=new Button("瀑布图");
    private final Button evidenceFocus=new Button("关联证据");
    private java.util.function.Consumer<CallNode> onEvidenceFocus;
    private CallNode inspectedNode;
    private boolean rebuildingRows;
    private final Label emptyTitle;
    private final Label emptyDescription;
    private final List<TreeItem<CallNode>> allRoots = new ArrayList<>();
    private final java.util.Set<String> countedTraces = new java.util.HashSet<>();
    private volatile long totalRoots = 0;
    private volatile boolean outputPaused = false;
    /** Online and offline data share the renderer, but the latter has no stream to pause. */
    private String titlePrefix = "调用链";
    private List<com.agentmonitor.model.trace.TraceNode> offlineTraces;
    private final java.util.concurrent.ExecutorService searchWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"call-search");t.setDaemon(true);return t;});
    private java.util.concurrent.Future<?> queryTask;
    private long queryGeneration;
    private int offlinePage;
    private final Button previousPage=new Button("上一页"),nextPage=new Button("下一页"),previousMatch=new Button("上个命中"),nextMatch=new Button("下个命中");
    private final Label matchCount=new Label();
    private final EvidenceFilters filters=new EvidenceFilters("calls");
    private EvidenceTimelinePane activeTimeline;
    private CallDetailPane waterfallDetails;
    private TreeItem<CallNode> waterfallAnchor;
    private record CallFocus(com.agentmonitor.model.trace.TraceNode root,List<Integer> path) { }
    private CallFocus pendingCallFocus;
    private List<TreeItem<CallNode>> matches=List.of();private int matchIndex=-1;
    private final java.util.Map<TreeItem<CallNode>,Boolean> expansionBeforeSearch=new java.util.IdentityHashMap<>();
    private record PendingTrace(com.agentmonitor.model.trace.TraceNode node,java.util.function.BooleanSupplier current) { }
    private final java.util.LinkedHashMap<String,PendingTrace> queued=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,java.util.function.BooleanSupplier> queuedCounts=new java.util.LinkedHashMap<>();
    private boolean drainScheduled,closed,batching,submittingSort;private long frozenUpdates;
    private final java.util.Map<String,CallNode> frozenRoots=new java.util.LinkedHashMap<>();

    public CallTreePane() {
        setSpacing(0);
        getStyleClass().add("call-tree-pane");

        titleLabel = new Label("调用链  (0)");
        titleLabel.getStyleClass().add("panel-title");
        titleLabel.setPadding(new Insets(6, 8, 6, 8));

        rootItem = new TreeItem<>();
        rootItem.setExpanded(true);

        treeTable = new TreeTableView<>(rootItem);
        treeTable.setShowRoot(false);
        treeTable.getStyleClass().add("call-tree-table");
        treeTable.setColumnResizePolicy(TreeTableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        treeTable.setFixedCellSize(DesktopPreferences.get().cellSize());treeTable.setId("call-tree-table");
        VBox.setVgrow(treeTable, Priority.ALWAYS);

        TreeTableColumn<CallNode, String> methodCol = new TreeTableColumn<>("方法");
        methodCol.setCellValueFactory(new TreeItemPropertyValueFactory<>("method"));
        methodCol.setCellFactory(col -> new MethodCell());
        methodCol.setPrefWidth(400);

        TreeTableColumn<CallNode, String> threadCol = new TreeTableColumn<>("线程");
        threadCol.setCellValueFactory(new TreeItemPropertyValueFactory<>("thread"));
        threadCol.setPrefWidth(190);threadCol.setMinWidth(130);threadCol.setMaxWidth(280);
        threadCol.setCellFactory(col->new TreeTableCell<>(){
            @Override protected void updateItem(String value,boolean empty){super.updateItem(value,empty);setText(empty?null:value);setTooltip(empty||value==null?null:new Tooltip(value));}
        });

        TreeTableColumn<CallNode, Long> durCol = new TreeTableColumn<>("耗时");
        durCol.setCellValueFactory(c -> c.getValue().getValue().durationNanosProperty().asObject());
        durCol.setCellFactory(col -> new DurationCell());
        durCol.setPrefWidth(116);durCol.setMinWidth(108);durCol.setMaxWidth(136);
        durCol.setSortType(TreeTableColumn.SortType.DESCENDING);

        TreeTableColumn<CallNode, Long> pctCol = new TreeTableColumn<>("占根调用");
        pctCol.setCellValueFactory(c -> c.getValue().getValue().durationNanosProperty().asObject());
        pctCol.setCellFactory(col -> new PercentCell());
        pctCol.setPrefWidth(96);pctCol.setMinWidth(90);
        pctCol.setResizable(false);
        pctCol.getStyleClass().add("percent-col");

        treeTable.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            TreeItem<CallNode> selected = treeTable.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            if (event.getCode() == KeyCode.ENTER) {
                showDetails(selected.getValue());
                event.consume();
            } else if (event.getCode() == KeyCode.RIGHT && !selected.isLeaf() && !selected.isExpanded()) {
                selected.setExpanded(true);
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT && selected.isExpanded()) {
                selected.setExpanded(false);
                event.consume();
            }
        });
        treeTable.setRowFactory(tv -> {
            TreeTableRow<CallNode> row = new TreeTableRow<>();
            Region empty = new Region();
            empty.setMinSize(0, 0);
            empty.setPrefSize(0, 0);
            empty.setMaxSize(0, 0);
            empty.setMouseTransparent(true);
            row.setDisclosureNode(empty);
            row.itemProperty().addListener((o,a,b)->row.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("search-match"),b!=null&&!searchText().isBlank()&&matchesText(b.getFullClassName(),b.getMethodName2(),b.getThread(),searchText())));
            row.setOnMouseClicked(event->{if(event.getClickCount()==2&&!row.isEmpty())showDetails(row.getItem());});
            return row;
        });

        treeTable.getColumns().addAll(methodCol,threadCol,durCol,pctCol);
        methodCol.setMinWidth(200);methodCol.setUserData("method");threadCol.setUserData("thread");durCol.setUserData("duration");pctCol.setUserData("ratio");
        treeTable.setSortPolicy(table->{if(offlineTraces!=null){if(!submittingSort){offlinePage=0;applyFilter(searchText());}return true;}return TreeTableView.DEFAULT_SORT_POLICY.call(table);});
        treeTable.setPlaceholder(new Label(""));

        searchField = new TextField();
        filters.setQueryField(searchField);
        searchField.setPromptText("搜索方法名、线程名...");
        searchField.getStyleClass().add("search-field");
        searchField.setPrefWidth(200);
        searchField.textProperty().addListener((obs, old, now) -> {offlinePage=0;applyFilter(now);});

        Button clearBtn = new Button("清空");
        clearBtn.getStyleClass().add("action-btn");
        clearBtn.setTooltip(new Tooltip("清空当前显示的调用链；会话文件保留在磁盘上"));
        clearBtn.setOnAction(e -> clear());

        pauseOutputButton = new Button("冻结视图");
        pauseOutputButton.getStyleClass().add("ghost-btn");
        pauseOutputButton.setTooltip(new Tooltip("冻结阅读位置；Agent 继续采集，恢复时显示最新 500 条窗口"));
        pauseOutputButton.setOnAction(e -> {
            outputPaused = !outputPaused;
            pauseOutputButton.setText(outputPaused ? "恢复实时" : "冻结视图");
            if(!outputPaused){var pending=new ArrayList<>(frozenRoots.values());frozenRoots.clear();batching=true;try{for(CallNode node:pending)addRootOnFx(node);}finally{batching=false;}applyFilter(searchField.getText());frozenUpdates=0;}
            updateTitle();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        detailsToggle.setId("call-show-details");detailsToggle.getStyleClass().add("ghost-btn");
        detailsToggle.setOnAction(e->{if(contentSplit.getItems().size()>1){hideDetails();return;}
            var selected=treeTable.getSelectionModel().getSelectedItem();
            if(selected==null&&!rootItem.getChildren().isEmpty()){selected=rootItem.getChildren().get(0);treeTable.getSelectionModel().select(selected);}
            if(selected!=null)showDetails(selected.getValue());
        });
        searchField.setMinWidth(140);titleLabel.setMinWidth(130);
        SearchBox search = new SearchBox(searchField,"方法或线程",true);search.setMinWidth(140);search.setPrefWidth(220);
        javafx.scene.layout.FlowPane toolbar = new javafx.scene.layout.FlowPane(8,6,titleLabel,search,pauseOutputButton,clearBtn,detailsToggle);
        toolbar.setPadding(new Insets(4, 8, 4, 0));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        evidenceFocus.setVisible(false);evidenceFocus.setManaged(false);evidenceFocus.setDisable(true);toolbar.getChildren().addAll(evidenceFocus,waterfall);
        waterfall.setOnAction(e->{
            var selected=treeTable.getSelectionModel().getSelectedItem();if(selected==null||selected.getValue().traceSource()==null)return;
            var source=selected.getValue().traceSource();long generation=++queryGeneration;
            searchWorker.submit(()->{var rows=EvidenceTimelinePane.fromTrace(source);Platform.runLater(()->{
                if(closed||generation!=queryGeneration)return;hideDetails();
                activeTimeline=new EvidenceTimelinePane();activeTimeline.setEvents(rows);activeTimeline.setShowRawDetails(false);waterfallAnchor=selected;
                waterfallDetails=new CallDetailPane(this::hideDetails);waterfallDetails.show(selected.getValue(),PercentCell.getRootDuration(selected));
                activeTimeline.setOnSelect(row->{var item=resolvePath(selected,row.path("callPath"));treeTable.getSelectionModel().select(item);treeTable.scrollTo(treeTable.getRow(item));waterfallDetails.show(item.getValue(),PercentCell.getRootDuration(item));});
                Button back=new Button("返回调用详情");back.setOnAction(event->{var current=treeTable.getSelectionModel().getSelectedItem();showDetails(current==null?selected.getValue():current.getValue());});
                SplitPane evidence=new SplitPane(activeTimeline,waterfallDetails);evidence.setOrientation(javafx.geometry.Orientation.VERTICAL);evidence.setDividerPositions(.62);
                VBox box=new VBox(8,back,evidence);VBox.setVgrow(evidence,Priority.ALWAYS);waterfallView=box;contentSplit.getItems().add(box);contentSplit.setDividerPositions(.48);
            });});
        });
        waterfall.setDisable(true);treeTable.getSelectionModel().selectedItemProperty().addListener((o,a,b)->waterfall.setDisable(b==null||b.getValue().traceSource()==null));
        evidenceFocus.setOnAction(e->{var item=treeTable.getSelectionModel().getSelectedItem();if(item!=null&&onEvidenceFocus!=null)onEvidenceFocus.accept(item.getValue());});
        treeTable.getSelectionModel().selectedItemProperty().addListener((o,a,b)->evidenceFocus.setDisable(b==null||b.getValue().getTraceId().isBlank()));

        emptyTitle = new Label("等待第一条调用链");
        emptyTitle.getStyleClass().add("empty-state-title");
        emptyDescription = new Label("开始监控后，在目标应用中执行一次业务操作。");
        emptyDescription.getStyleClass().add("empty-state-desc");
        emptyDescription.setWrapText(true);
        emptyDescription.setMaxWidth(420);
        emptyState = new VBox(12, UiIcons.icon("monitor"), emptyTitle, emptyDescription);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setMouseTransparent(true);

        StackPane treeArea = new StackPane(treeTable, emptyState);
        StackPane.setMargin(emptyState, new Insets(32, 0, 0, 0));
        treeArea.setMinWidth(370);
        detailPane=new CallDetailPane(this::hideDetails);
        contentSplit.getItems().add(treeArea);VBox.setVgrow(contentSplit,Priority.ALWAYS);
        treeTable.getSelectionModel().selectedItemProperty().addListener((obs,old,item)->{
            if(item!=null&&contentSplit.getItems().size()>1){
                inspectedNode=item.getValue();detailPane.show(item.getValue(),PercentCell.getRootDuration(item));
                if(activeTimeline!=null){waterfallDetails.show(item.getValue(),PercentCell.getRootDuration(item));var path=pathFrom(waterfallAnchor,item);activeTimeline.highlightEvidence(path==null?null:new ObjectMapper().createObjectNode().set("callPath",new ObjectMapper().valueToTree(path)));}
            }
            else if(item==null&&!rebuildingRows)hideDetails();
        });
        previousMatch.setOnAction(e->moveMatch(-1));nextMatch.setOnAction(e->moveMatch(1));
        previousPage.setOnAction(e->{offlinePage=Math.max(0,offlinePage-1);applyFilter(searchField.getText());});nextPage.setOnAction(e->{offlinePage++;applyFilter(searchField.getText());});
        previousPage.setVisible(false);previousPage.setManaged(false);nextPage.setVisible(false);nextPage.setManaged(false);
        filters.setOnChanged(()->{offlinePage=0;applyFilter(searchText());});
        getChildren().addAll(toolbar,filters,new javafx.scene.layout.FlowPane(8,4,previousMatch,nextMatch,matchCount,previousPage,nextPage),contentSplit);
    }

    public void setOnEvidenceFocus(java.util.function.Consumer<CallNode> action){onEvidenceFocus=action;evidenceFocus.setVisible(true);evidenceFocus.setManaged(true);}
    private String searchText(){return searchField==null?"":searchField.getText().trim().toLowerCase(Locale.ROOT);}
    public long getObservedRootCount() { return totalRoots; }

    public void addRootNode(CallNode node) {
        Platform.runLater(() -> { if (!closed) acceptRoot(node); });
    }
    private void addRootOnFx(CallNode node) {
        if(offlineTraces!=null)return;
        TreeItem<CallNode> item = buildTreeItem(node, 1);
        if (!node.getTraceId().isBlank()) allRoots.removeIf(previous -> node.getTraceId().equals(previous.getValue().getTraceId()));
        allRoots.add(0, item);
        if (node.getTraceId().isBlank() || countedTraces.add(node.getTraceId())) totalRoots++;
        if (allRoots.size() > 500) allRoots.remove(allRoots.size() - 1);
        if(!batching)applyFilter(searchField.getText());
    }

    /** All JavaFX state is created on the application thread, after leaving the collector. */
    public void addTrace(com.agentmonitor.model.trace.TraceNode node) {
        addTrace(node, () -> true);
    }
    public void addTrace(com.agentmonitor.model.trace.TraceNode node, java.util.function.BooleanSupplier stillCurrent) {
        synchronized(queued){if(closed)return;String key=node.getTraceId().isBlank()?java.util.UUID.randomUUID().toString():node.getTraceId();queued.put(key,new PendingTrace(node,stillCurrent));queuedCounts.put(key,stillCurrent);while(queued.size()>500)queued.remove(queued.keySet().iterator().next());
            if(drainScheduled)return;drainScheduled=true;}
        Platform.runLater(()->{List<PendingTrace> batch;java.util.Map<String,java.util.function.BooleanSupplier> counts;
            synchronized(queued){batch=new ArrayList<>(queued.values());counts=new java.util.LinkedHashMap<>(queuedCounts);queuedCounts.clear();queued.clear();drainScheduled=false;}
            if(closed)return;
            counts.forEach((key,current)->{if(current.getAsBoolean()&&countedTraces.add(key))totalRoots++;});
            batching=true;try{for(var trace:batch)if(trace.current().getAsBoolean())acceptRoot(CallNode.fromTrace(trace.node()));}finally{batching=false;}
            if(!outputPaused)applyFilter(searchField.getText());
        });
    }
    private void acceptRoot(CallNode node){
        if(outputPaused){String key=node.getTraceId().isBlank()?"anonymous-"+(frozenUpdates++):node.getTraceId();frozenRoots.put(key,node);while(frozenRoots.size()>500)frozenRoots.remove(frozenRoots.keySet().iterator().next());updateTitle();}
        else addRootOnFx(node);
    }

    /** A newly attached target must not inherit a manually paused or stale previous session view. */
    public void beginMonitoringSession() {
        outputPaused = false;
        titlePrefix = "调用链";
        pauseOutputButton.setText("冻结视图");
        pauseOutputButton.setDisable(false);
        clear();
    }

    /**
     * Replaces the view with a completed on-disk capture. Unlike the live stream's rolling
     * window, replay retains every supplied root so historical traces are never silently capped.
     * This method must be invoked from the JavaFX application thread.
     */
    public void showOfflineReplay(List<CallNode> roots) {
        hideDetails();pendingCallFocus=null;offlineTraces=null;queryGeneration++;
        outputPaused = false;
        titlePrefix = "离线调用链";
        pauseOutputButton.setText("冻结视图");
        pauseOutputButton.setDisable(true);
        allRoots.clear(); countedTraces.clear();
        rootItem.getChildren().clear();
        if (roots != null) {
            for (CallNode root : roots) {
                if (root != null) allRoots.add(0, buildTreeItem(root, 1));
            }
        }
        totalRoots = allRoots.size();
        applyFilter(searchField.getText());

    }

    private TreeItem<CallNode> buildTreeItem(CallNode node, int depth) {
        TreeItem<CallNode> item=new TreeItem<>(node){
            private boolean loaded;
            @Override public boolean isLeaf(){return !getValue().hasChildren();}
            @Override public javafx.collections.ObservableList<TreeItem<CallNode>> getChildren(){
                if(!loaded){loaded=true;for(CallNode child:getValue().getChildren())super.getChildren().add(buildTreeItem(child,depth+1));}
                return super.getChildren();
            }
        };
        item.setExpanded(depth<DEFAULT_EXPANDED_CALL_DEPTH);return item;
    }
    public void showOfflineTraces(List<com.agentmonitor.model.trace.TraceNode> roots){
        clear();outputPaused=false;pauseOutputButton.setText("冻结视图");offlineTraces=List.copyOf(roots);totalRoots=roots.size();titlePrefix="离线调用链";pauseOutputButton.setDisable(true);offlinePage=0;
        previousPage.setVisible(true);previousPage.setManaged(true);nextPage.setVisible(true);nextPage.setManaged(true);applyFilter(searchField.getText());
    }
    private record TraceMatch(com.agentmonitor.model.trace.TraceNode root,List<List<Integer>> paths) { }
    private void filterOffline(String needle){
        long generation=++queryGeneration;if(queryTask!=null)queryTask.cancel(true);
        List<com.agentmonitor.model.trace.TraceNode> source=offlineTraces;int requested=offlinePage;CallFocus focus=pendingCallFocus;
        var criteria=filters.criteria();
        var sorting=treeTable.getSortOrder().stream().map(column->java.util.Map.entry(column.getUserData().toString(),column.getSortType()==TreeTableColumn.SortType.DESCENDING)).toList();
        matchCount.setText("正在搜索历史调用…");
        queryTask=searchWorker.submit(()->{
            List<TraceMatch> found=new ArrayList<>();
            for(var root:source){if(Thread.currentThread().isInterrupted())return;List<List<Integer>> paths=new ArrayList<>();
                if(!needle.isBlank()){
                    record Visit(com.agentmonitor.model.trace.TraceNode node,List<Integer> path) { }
                    var pending=new java.util.ArrayDeque<Visit>();pending.add(new Visit(root,List.of()));
                    while(!pending.isEmpty()){
                        if(Thread.currentThread().isInterrupted())return;var visit=pending.removeLast();var node=visit.node();
                        if(matchesText(node.getFullClassName(),node.getMethodName2(),node.getThread(),needle)&&paths.size()<2000)paths.add(visit.path());
                        for(int i=0;i<node.getChildren().size();i++){var path=new ArrayList<>(visit.path());path.add(i);pending.add(new Visit(node.getChildren().get(i),List.copyOf(path)));}
                    }
                }
                if((needle.isBlank()||!paths.isEmpty())&&criteria.matches(root.getDurationNanos(),root.isError(),root.getThread(),root.getAttributes().getOrDefault("http.route",root.getAttributes().getOrDefault("url.path",""))))found.add(new TraceMatch(root,paths));
            }
            if(!sorting.isEmpty())found.sort((a,b)->{for(var sort:sorting){var x=a.root();var y=b.root();int result=switch(sort.getKey()){case "duration"->Long.compare(x.getDurationNanos(),y.getDurationNanos());case "method"->(x.getFullClassName()+"#"+x.getMethodName2()).compareToIgnoreCase(y.getFullClassName()+"#"+y.getMethodName2());case "thread"->x.getThread().compareToIgnoreCase(y.getThread());default->0;};if(result!=0)return sort.getValue()?-result:result;}return 0;});
            int requestedPage=requested;if(focus!=null)for(int i=0;i<found.size();i++)if(found.get(i).root()==focus.root()){requestedPage=i/100;break;}
            int pages=Math.max(1,(found.size()+99)/100),page=Math.min(requestedPage,pages-1);
            var visible=List.copyOf(found.subList(Math.min(page*100,found.size()),Math.min((page+1)*100,found.size())));
            Platform.runLater(()->{if(closed||generation!=queryGeneration)return;hideDetails();offlinePage=page;allRoots.clear();List<TreeItem<CallNode>> hits=new ArrayList<>();
                for(var result:visible){var item=buildTreeItem(CallNode.fromTrace(result.root()),1);allRoots.add(item);
                    for(var path:result.paths()){var current=item;for(int index:path){current.setExpanded(true);current=current.getChildren().get(index);}hits.add(current);}}
                submittingSort=true;try{rootItem.getChildren().setAll(allRoots);}finally{submittingSort=false;}setMatches(hits);emptyState.setVisible(allRoots.isEmpty());emptyTitle.setText("没有匹配的调用链");emptyDescription.setText("试试其他方法或线程名。");
                previousPage.setDisable(page==0);nextPage.setDisable(page>=pages-1);updateTitle(allRoots.size());if(focus!=null){pendingCallFocus=null;for(int i=0;i<visible.size();i++)if(visible.get(i).root()==focus.root()){var target=resolvePath(allRoots.get(i),new ObjectMapper().valueToTree(focus.path()));treeTable.getSelectionModel().select(target);treeTable.scrollTo(treeTable.getRow(target));showDetails(target.getValue());break;}}
                matchCount.setText("匹配链 "+found.size()+" · 第 "+(page+1)+" / "+pages+" 页 · 本页命中 "+hits.size()+"（每链最多定位 2000 处）");
            });
        });
    }
    private void setMatches(List<TreeItem<CallNode>> values){matches=List.copyOf(values);matchIndex=-1;previousMatch.setDisable(matches.isEmpty());nextMatch.setDisable(matches.isEmpty());treeTable.refresh();}
    private void moveMatch(int direction){if(matches.isEmpty())return;matchIndex=Math.floorMod(matchIndex+direction,matches.size());var item=matches.get(matchIndex);treeTable.getSelectionModel().select(item);treeTable.scrollTo(treeTable.getRow(item));showDetails(item.getValue());}
    @Override public void close(){closed=true;queryGeneration++;if(queryTask!=null)queryTask.cancel(true);searchWorker.shutdownNow();synchronized(queued){queued.clear();}}

    private void showDetails(CallNode node) {
        clearWaterfall();
        inspectedNode=node;
        if(contentSplit.getItems().size()==1){contentSplit.getItems().add(detailPane);contentSplit.setDividerPositions(.66);}
        detailsToggle.setText("收起详情");
        detailPane.show(node,PercentCell.getRootDuration(treeTable.getSelectionModel().getSelectedItem()));
    }
    private void hideDetails(){clearWaterfall();contentSplit.getItems().remove(detailPane);inspectedNode=null;detailsToggle.setText("调用详情");}

    private void clearWaterfall(){if(waterfallView!=null)contentSplit.getItems().remove(waterfallView);waterfallView=null;activeTimeline=null;waterfallDetails=null;waterfallAnchor=null;}
    private static TreeItem<CallNode> resolvePath(TreeItem<CallNode> root,JsonNode path){var item=root;for(JsonNode index:path){item.setExpanded(true);item=item.getChildren().get(index.asInt());}return item;}
    private static List<Integer> pathFrom(TreeItem<CallNode> root,TreeItem<CallNode> selected){List<Integer> path=new ArrayList<>();var item=selected;while(item!=root){var parent=item.getParent();if(parent==null)return null;path.add(0,parent.getChildren().indexOf(item));item=parent;}return path;}
    public void focusCall(JsonNode evidence){
        String trace=evidence.path("traceId").asText(),span=evidence.path("spanId").asText(),method=evidence.path("method").asText();
        if(trace.isBlank())return;
        List<com.agentmonitor.model.trace.TraceNode> source=offlineTraces!=null?offlineTraces:allRoots.stream().map(item->item.getValue().traceSource()).filter(java.util.Objects::nonNull).toList();
        long version=++queryGeneration;if(queryTask!=null)queryTask.cancel(true);
        queryTask=searchWorker.submit(()->{
            record Visit(com.agentmonitor.model.trace.TraceNode node,List<Integer> path) { }
            CallFocus found=null;
            outer:for(var root:source){var pending=new java.util.ArrayDeque<Visit>();pending.add(new Visit(root,List.of()));
                while(!pending.isEmpty()){if(Thread.currentThread().isInterrupted())return;var visit=pending.removeLast();var node=visit.node();
                    boolean identity=span.isBlank()?(method.isBlank()||method.startsWith(node.getFullClassName()+"#"+node.getMethodName2())):span.equals(node.getAttributes().get("agent.span.id"));
                    if(trace.equals(node.getTraceId())&&identity){found=new CallFocus(root,visit.path());break outer;}
                    for(int i=0;i<node.getChildren().size();i++){var path=new ArrayList<>(visit.path());path.add(i);pending.add(new Visit(node.getChildren().get(i),List.copyOf(path)));}
                }
            }
            CallFocus match=found;Platform.runLater(()->{
                if(closed||version!=queryGeneration)return;
                if(match==null){matchCount.setText("当前证据中没有对应调用；实时窗口仅保留最近 500 条链，可停止后加载完整会话。");return;}
                searchField.clear();filters.clear();
                if(offlineTraces!=null){pendingCallFocus=match;applyFilter("");}
                else{applyFilter("");for(var item:allRoots)if(item.getValue().traceSource()==match.root()){var target=resolvePath(item,new ObjectMapper().valueToTree(match.path()));treeTable.getSelectionModel().select(target);treeTable.scrollTo(treeTable.getRow(target));showDetails(target.getValue());break;}}
            });
        });
    }

    static String formatJsonText(String rawText) throws Exception {
        String text = rawText == null ? "" : rawText.trim();
        if (text.isEmpty()) return text;

        try {
            return prettyJson(text);
        } catch (Exception strictError) {
            String normalized = normalizeLooseJson(text);
            try {
                return prettyJson(normalized);
            } catch (Exception normalizedError) {
                String loose = looseIndentJsonLike(text);
                if (!loose.equals(text)) return loose;
                throw strictError;
            }
        }
    }

    private static String prettyJson(String text) throws Exception {
        JsonNode node = JSON_MAPPER.readTree(text);
        if (node.isTextual()) {
            String inner = node.asText().trim();
            if (looksLikeJson(inner)) {
                node = JSON_MAPPER.readTree(normalizeLooseJson(inner));
            }
        }
        return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    private static boolean looksLikeJson(String text) {
        return (text.startsWith("{") && text.endsWith("}"))
                || (text.startsWith("[") && text.endsWith("]"));
    }

    private static String normalizeLooseJson(String text) {
        String normalized = text
                .replaceAll("(?<=[:\\[,])\\s*\\.\\.\\.\\s*(?=[,\\]}])", " \"...\"")
                .replaceAll("(?<=[:\\[,])\\s*…\\s*(?=[,\\]}])", " \"...\"");
        if (normalized.endsWith("…")) {
            normalized = normalized.substring(0, normalized.length() - 1).stripTrailing();
        }
        return normalized;
    }

    private static String looseIndentJsonLike(String text) {
        StringBuilder out = new StringBuilder(text.length() + 64);
        int indent = 0;
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escaping) {
                out.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\' && inString) {
                out.append(ch);
                escaping = true;
                continue;
            }
            if (ch == '"') {
                out.append(ch);
                inString = !inString;
                continue;
            }
            if (inString) {
                out.append(ch);
                continue;
            }
            switch (ch) {
                case '{', '[' -> {
                    out.append(ch).append('\n');
                    indent++;
                    appendIndent(out, indent);
                }
                case '}', ']' -> {
                    trimTrailingSpaces(out);
                    out.append('\n');
                    indent = Math.max(0, indent - 1);
                    appendIndent(out, indent);
                    out.append(ch);
                }
                case ',' -> {
                    out.append(ch).append('\n');
                    appendIndent(out, indent);
                }
                case ':' -> out.append(": ");
                default -> {
                    if (!Character.isWhitespace(ch) || !endsWithWhitespace(out)) {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void appendIndent(StringBuilder out, int indent) {
        out.append("  ".repeat(Math.max(0, indent)));
    }

    private static void trimTrailingSpaces(StringBuilder out) {
        while (!out.isEmpty() && Character.isWhitespace(out.charAt(out.length() - 1))
                && out.charAt(out.length() - 1) != '\n') {
            out.setLength(out.length() - 1);
        }
    }

    private static boolean endsWithWhitespace(StringBuilder out) {
        return !out.isEmpty() && Character.isWhitespace(out.charAt(out.length() - 1));
    }

    static String parseDescriptor(String desc) {
        if (desc == null || desc.isEmpty()) return "";
        try {
            int parenClose = desc.lastIndexOf(')');
            if (parenClose < 1) return desc;
            List<String> params = parseParamTypes(desc.substring(1, parenClose));
            String ret = parseTypeName(desc.substring(parenClose + 1), new int[]{0});
            return "(" + String.join(", ", params) + ") \u2192 " + ret;
        } catch (Exception e) { return desc; }
    }
    private static List<String> parseParamTypes(String s) {
        List<String> list = new ArrayList<>();
        int[] i = {0};
        while (i[0] < s.length()) list.add(parseTypeName(s, i));
        return list;
    }
    private static String parseTypeName(String s, int[] i) {
        if (i[0] >= s.length()) return "";
        char c = s.charAt(i[0]++);
        return switch (c) {
            case 'V' -> "void";   case 'Z' -> "boolean"; case 'B' -> "byte";
            case 'C' -> "char";   case 'S' -> "short";   case 'I' -> "int";
            case 'J' -> "long";   case 'F' -> "float";   case 'D' -> "double";
            case 'L' -> {
                int end = s.indexOf(';', i[0]);
                String name = s.substring(i[0], end < 0 ? s.length() : end).replace('/', '.');
                i[0] = end < 0 ? s.length() : end + 1;
                int dot = name.lastIndexOf('.');
                yield dot >= 0 ? name.substring(dot + 1) : name;
            }
            case '[' -> parseTypeName(s, i) + "[]";
            default -> String.valueOf(c);
        };
    }

    public void clear() {
        hideDetails();
        synchronized(queued){queued.clear();queuedCounts.clear();}
        offlineTraces=null;queryGeneration++;offlinePage=0;if(queryTask!=null)queryTask.cancel(true);frozenRoots.clear();frozenUpdates=0;
        previousPage.setVisible(false);previousPage.setManaged(false);nextPage.setVisible(false);nextPage.setManaged(false);
        expansionBeforeSearch.clear();setMatches(List.of());
        allRoots.clear(); countedTraces.clear();
        rootItem.getChildren().clear();
        totalRoots = 0;
        applyFilter(searchField.getText());
    }

    private void applyFilter(String text) {
        String query=text==null?"":text.trim().toLowerCase(Locale.ROOT);
        if(offlineTraces!=null){filterOffline(query);return;}
        List<TreeItem<CallNode>> hits=new ArrayList<>();
        if(query.isBlank()){expansionBeforeSearch.forEach(TreeItem::setExpanded);expansionBeforeSearch.clear();}
        else for(var item:allRoots)if(matchesCriteria(item.getValue()))collectMatches(item,query,hits);
        setMatches(hits);matchCount.setText(query.isBlank()?"实时视图最多保留最近 500 条链":"命中 "+hits.size()+" 处");
        rebuildingRows=true;
        rootItem.getChildren().clear();
        List<TreeItem<CallNode>> filtered = new ArrayList<>();
        String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (TreeItem<CallNode> item : allRoots) {
            if ((needle.isEmpty() || matchesCall(item, needle))&&matchesCriteria(item.getValue())) filtered.add(item);
        }
        rootItem.getChildren().setAll(filtered);
        // Rebuilding the visible roots replaces the TreeItem list. JavaFX keeps the
        // selected columns, but does not automatically reapply their comparator to
        // those replacement items. Preserve the user's active ordering as new calls
        // arrive; with no selected sort column, retain the original arrival order.
        if (!treeTable.getSortOrder().isEmpty()) {
            treeTable.sort();
        }
        rebuildingRows=false;
        if(inspectedNode!=null){
            TreeItem<CallNode> match=null;
            for(var item:filtered){match=findInspected(item,inspectedNode);if(match!=null)break;}
            if(match==null)hideDetails();
            else{treeTable.getSelectionModel().select(match);inspectedNode=match.getValue();detailPane.show(inspectedNode,PercentCell.getRootDuration(match));}
        }
        emptyState.setVisible(filtered.isEmpty());
        boolean hasCalls = !allRoots.isEmpty();
        emptyTitle.setText(hasCalls ? "没有匹配的调用链" : "等待第一条调用链");
        emptyDescription.setText(hasCalls ? "试试其他方法名、线程名，或清空搜索条件。"
                : "开始监控后，在目标应用中执行一次业务操作。");
        updateTitle(filtered.size());
    }
    private static TreeItem<CallNode> findInspected(TreeItem<CallNode> item,CallNode previous){
        CallNode node=item.getValue();String span=previous.getAttributes().getOrDefault("agent.span.id","");
        if(node==previous||(!span.isBlank()&&!previous.getTraceId().isBlank()&&previous.getTraceId().equals(node.getTraceId())&&span.equals(node.getAttributes().get("agent.span.id"))))return item;
        for(var child:item.getChildren()){var match=findInspected(child,previous);if(match!=null)return match;}return null;
    }

    private void collectMatches(TreeItem<CallNode> item,String needle,List<TreeItem<CallNode>> hits){
        CallNode node=item.getValue();if(matchesText(node.getFullClassName(),node.getMethodName2(),node.getThread(),needle)){
            hits.add(item);for(var ancestor=item.getParent();ancestor!=null&&ancestor.getValue()!=null;ancestor=ancestor.getParent()){expansionBeforeSearch.putIfAbsent(ancestor,ancestor.isExpanded());ancestor.setExpanded(true);}
        }
        for(var child:item.getChildren())collectMatches(child,needle,hits);
    }
    private boolean matchesCriteria(CallNode node){return filters.criteria().matches(node.getDurationNanos(),node.isError(),node.getThread(),node.getAttributes().getOrDefault("http.route",node.getAttributes().getOrDefault("url.path","")));}
    private static boolean matchesText(String cls,String method,String thread,String query){return (cls+"#"+method+" "+cls+"."+method+"() "+thread).toLowerCase(Locale.ROOT).contains(query);}
    private boolean matchesCall(TreeItem<CallNode> item, String needle) {
        CallNode node = item.getValue();
        if(matchesText(node.getFullClassName(),node.getMethodName2(),node.getThread(),needle))return true;
        return item.getChildren().stream().anyMatch(child -> matchesCall(child, needle));
    }

    private void updateTitle() {
        updateTitle(rootItem.getChildren().size());
    }

    private void updateTitle(int visibleCount) {
        String suffix = outputPaused ? " · 已冻结 · 待更新 "+frozenRoots.size()+" 条（最多 500）" : "";
        if (totalRoots == 0) {
            titleLabel.setText(titlePrefix + "  (0)" + suffix);
        } else {
            titleLabel.setText(titlePrefix + "  (" + visibleCount + "/" + totalRoots + ")" + suffix);
        }
    }

    private static class MethodCell extends TreeTableCell<CallNode, String> {
        private final Region  indentRegion = new Region();
        private final Label   toggleLbl    = new Label();
        private final Label   nameLbl      = new Label();
        private final Tooltip tip          = new Tooltip();
        private final HBox    content;

        MethodCell() {
            indentRegion.setMinWidth(Region.USE_PREF_SIZE);
            toggleLbl.setStyle("-fx-text-fill: #6B7280; -fx-font-size: 12px;"
                    + " -fx-cursor: hand; -fx-min-width: 18; -fx-max-width: 18;");
            nameLbl.getStyleClass().add("call-method-name");
            tip.setStyle("-fx-font-size: 12px;");
            Tooltip.install(nameLbl, tip);
            content = new HBox(0, indentRegion, toggleLbl, nameLbl);
            content.setAlignment(Pos.CENTER_LEFT);

            toggleLbl.setOnMouseClicked(e -> {
                TreeItem<CallNode> ti = getTreeTableRow().getTreeItem();
                if (ti != null && !ti.getChildren().isEmpty()) {
                    ti.setExpanded(!ti.isExpanded());
                    toggleLbl.setText("");
                    javafx.scene.Node chevron = UiIcons.icon("chevron");
                    chevron.setRotate(ti.isExpanded() ? 90 : 0);
                    toggleLbl.setGraphic(chevron);
                    e.consume();
                }
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeIf(s -> s.startsWith("cell-"));
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
            } else {
                TreeItem<CallNode> ti = getTreeTableRow().getTreeItem();
                int depth = 0;
                TreeItem<CallNode> cur = ti;
                while (cur != null && cur.getParent() != null
                        && cur.getParent().getValue() != null) { depth++; cur = cur.getParent(); }
                indentRegion.setPrefWidth(depth * 9.0);
                if (ti != null && !ti.getChildren().isEmpty()) {
                    toggleLbl.setText("");
                    javafx.scene.Node chevron = UiIcons.icon("chevron");
                    chevron.setRotate(ti.isExpanded() ? 90 : 0);
                    toggleLbl.setGraphic(chevron);
                } else {
                    toggleLbl.setText("");
                    toggleLbl.setGraphic(null);
                }
                nameLbl.setText(item);
                CallNode value=ti==null?null:ti.getValue();
                tip.setText(value==null?item:value.getFullClassName()+"#"+value.getMethodName2()+value.getSignature());
                nameLbl.setMinWidth(0);nameLbl.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(nameLbl,Priority.ALWAYS);
                setText(null);
                setGraphic(content);
            }
        }
    }

    private static class DurationCell extends TreeTableCell<CallNode, Long> {
        @Override
        protected void updateItem(Long item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeIf(s -> s.startsWith("cell-"));
            if (empty || item == null) {
                setText(null);
            } else {
                CallNode node = getTableRow() != null ? getTableRow().getItem() : null;
                if (node != null) {
                    setText(node.getDurationDisplay());
                    getStyleClass().add("cell-" + node.getDurationStyle());
                } else {
                    setText(item + " ns");
                }
            }
        }
    }

    private static class PercentCell extends TreeTableCell<CallNode, Long> {
        @Override
        protected void updateItem(Long item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeIf(s -> s.startsWith("cell-"));
            setStyle("-fx-alignment: center-right;");
            if (empty || item == null) {
                setText(null);
                return;
            }
            CallNode node = getTableRow() != null ? getTableRow().getItem() : null;
            if (node == null || node.getDurationNanos() == 0) { setText("—"); return; }
            long rootNs = getRootDuration(getTreeTableRow().getTreeItem());
            if (rootNs <= 0) { setText("—"); return; }
            double pct = node.getDurationNanos() * 100.0 / rootNs;
            setText(String.format("%.0f%%",pct));
            setTooltip(new Tooltip("当前调用耗时 / 本地根调用耗时；包含子调用，不是 CPU 占用。"));
            getStyleClass().add("cell-" + node.getDurationStyle());
        }

        private static long getRootDuration(TreeItem<CallNode> item) {
            if (item == null) return 0;
            TreeItem<CallNode> cur = item;
            while (cur.getParent() != null && cur.getParent().getValue() != null)
                cur = cur.getParent();
            CallNode root = cur.getValue();
            return root != null ? root.getDurationNanos() : 0;
        }
    }

}
