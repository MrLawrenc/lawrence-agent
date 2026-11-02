package com.agentmonitor.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Typed sorting over the entire evidence set; only one page is submitted to JavaFX. */
public final class EvidenceTablePane extends VBox implements AutoCloseable {
    private record Row(JsonNode value,String search) { }
    private final EvidenceFilters filters;
    private final TableView<JsonNode> table=new TableView<>();
    private final TextField search=new TextField();
    private final TextArea summary=new TextArea(),raw=new TextArea();
    private final Label count=new Label("暂无证据");
    private final Button previous=new Button("上一页"),next=new Button("下一页");
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"evidence-table");t.setDaemon(true);return t;});
    private final javafx.animation.PauseTransition delay=new javafx.animation.PauseTransition(Duration.millis(140));
    private List<Row> rows=List.of();
    private JsonNode source, indexedSource;
    private long generation;
    private int page;
    private Future<?> pending;
    private boolean closed,submitting;
    private Consumer<JsonNode> focus=ignored->{};
    private SplitPane split;
    private VBox detail;
    private Button related;
    private long detailGeneration;
    private EvidenceTimelinePane.TimeRange timeRange;
    private String focusIdentity;
    private Consumer<JsonNode> onSelection=ignored->{};
    @FunctionalInterface public interface Resolver { JsonNode resolve(JsonNode row) throws Exception; }
    private Resolver resolver;


    public EvidenceTablePane(String label,String[] names,String[] fields) {
        filters=new EvidenceFilters(label);filters.setOnChanged(()->{page=0;generation++;delay.playFromStart();});
        setSpacing(10);setPadding(new javafx.geometry.Insets(12));
        table.setAccessibleText(label);table.setFixedCellSize(DesktopPreferences.get().cellSize());table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        Label empty=new Label("暂无"+label+"证据\n开始采集，或加载历史会话；未采集与读取失败的原因见上方状态。");empty.setWrapText(true);table.setPlaceholder(empty);
        for(int i=0;i<fields.length;i++) {
            String field=fields[i];TableColumn<JsonNode,JsonNode> column=new TableColumn<>(names[i]);column.setUserData(field);
            column.setCellValueFactory(c->new ReadOnlyObjectWrapper<>(c.getValue().path(field)));
            column.setComparator(EvidenceTablePane::compareValues);column.setMinWidth(70);
            try{column.setPrefWidth(Double.parseDouble(DesktopPreferences.get().value("width."+label+"."+field,"160")));}catch(NumberFormatException ignored){}
            column.widthProperty().addListener((o,a,b)->{if(b.doubleValue()>70)DesktopPreferences.get().put("width."+label+"."+field,Double.toString(b.doubleValue()));});
            column.setCellFactory(c->new TableCell<>(){
                @Override protected void updateItem(JsonNode value,boolean empty){super.updateItem(value,empty);setText(empty?null:format(field,value));setTooltip(empty||value==null?null:new Tooltip(value.isValueNode()?value.asText():value.toPrettyString()));setStyle(value!=null&&value.isNumber()?"-fx-alignment: center-right;":"");}
            });table.getColumns().add(column);
        }
        table.setSortPolicy(t->{if(!submitting){DesktopPreferences.get().put("sort."+label,table.getSortOrder().stream().map(c->c.getUserData()+":"+c.getSortType().name()).collect(java.util.stream.Collectors.joining(",")));page=0;schedule();}return true;});
        String order=DesktopPreferences.get().value("order."+label,"");if(!order.isBlank()){var columns=new ArrayList<>(table.getColumns());columns.sort(Comparator.comparingInt(c->{int i=List.of(order.split(",")).indexOf(c.getUserData().toString());return i<0?999:i;}));table.getColumns().setAll(columns);}
        table.getColumns().addListener((javafx.collections.ListChangeListener<TableColumn<JsonNode,?>>)change->DesktopPreferences.get().put("order."+label,table.getColumns().stream().map(c->c.getUserData().toString()).collect(java.util.stream.Collectors.joining(","))));
        String savedSort=DesktopPreferences.get().value("sort."+label,"");for(String sort:savedSort.split(",")){String[] part=sort.split(":");if(part.length==2)for(var column:table.getColumns())if(part[0].equals(column.getUserData())){column.setSortType(part[1].equals("DESCENDING")?TableColumn.SortType.DESCENDING:TableColumn.SortType.ASCENDING);table.getSortOrder().add(column);}}
        filters.setQueryField(search);
        search.setPromptText("筛选"+label);search.setAccessibleText("筛选"+label);
        search.textProperty().addListener((o,a,b)->{page=0;generation++;delay.playFromStart();});delay.setOnFinished(e->schedule());
        summary.setEditable(false);summary.setWrapText(true);summary.setPromptText("选择一行查看摘要、调用栈和关联标识。");summary.setAccessibleText(label+"摘要");
        raw.setEditable(false);raw.setWrapText(false);raw.setAccessibleText(label+"原始证据");
        related=new Button("查看关联时间线");related.setVisible(false);related.setManaged(false);related.setOnAction(e->{var selected=table.getSelectionModel().getSelectedItem();if(selected!=null)focus.accept(selected);});
        table.getSelectionModel().selectedItemProperty().addListener((o,a,b)->{updateDetails(b);related.setDisable(b==null);if(!submitting)onSelection.accept(b);});related.setDisable(true);
        TabPane details=new TabPane(new Tab("摘要",summary),new Tab("原始证据",raw));details.getStyleClass().add("evidence-details");details.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        detail=new VBox(6,related,details);VBox.setVgrow(details,Priority.ALWAYS);
        split=new SplitPane(table,detail);split.setOrientation(Orientation.VERTICAL);DesktopPreferences.get().remember(split,"split.evidence."+label,.62);
        previous.setDisable(true);next.setDisable(true);previous.setOnAction(e->{page=Math.max(0,page-1);schedule();});next.setOnAction(e->{page++;schedule();});
        getChildren().addAll(new SearchBox(search,label,true),filters,split,new HBox(10,count,previous,next));VBox.setVgrow(split,Priority.ALWAYS);
    }
    public void setOnFocus(Consumer<JsonNode> action){setOnFocus("查看关联时间线",action);}
    public void setOnFocus(String text,Consumer<JsonNode> action){focus=action;related.setText(text);related.setVisible(true);related.setManaged(true);}
    public void setDetailsOrientation(Orientation orientation){split.setOrientation(orientation);split.setDividerPositions(.6);}
    public void setShowDetails(boolean show){split.getItems().setAll(show?List.of(table,detail):List.of(table));}
    public TableView<JsonNode> table(){return table;}
    public void setOnSelection(Consumer<JsonNode> action){onSelection=action;}
    public void setResolver(Resolver action){resolver=action;}
    public void setTimeRange(EvidenceTimelinePane.TimeRange range){timeRange=range;page=0;schedule();}
    public void focusEvidence(JsonNode row){focusIdentity=identity(row);search.clear();filters.clear();delay.stop();schedule();}

    public void setRows(JsonNode data) {
        if(data.equals(source))return;source=data;
        if(data.isEmpty()){generation++;if(pending!=null)pending.cancel(true);rows=List.of();table.getItems().clear();count.setText("暂无证据");previous.setDisable(true);next.setDisable(true);return;}
        schedule();
    }
    private void schedule() {
        if(closed)return;long version=++generation;if(pending!=null)pending.cancel(true);
        String query=search.getText().toLowerCase(Locale.ROOT);JsonNode snapshotSource=source;int requested=page;var criteria=filters.criteria();
        boolean reveal=focusIdentity!=null;String identity=focusIdentity!=null?focusIdentity:identity(table.getSelectionModel().getSelectedItem());focusIdentity=null;var interval=timeRange;
        List<Map.Entry<String,Boolean>> sorting=table.getSortOrder().stream().map(c->Map.entry(c.getUserData().toString(),c.getSortType()==TableColumn.SortType.DESCENDING)).toList();
        count.setText("正在筛选…");
        pending=worker.submit(()->{
            if(snapshotSource!=indexedSource){List<Row> prepared=new ArrayList<>();if(snapshotSource!=null&&snapshotSource.isArray())for(JsonNode value:snapshotSource){if(Thread.currentThread().isInterrupted())return;prepared.add(new Row(value,value.toString().toLowerCase(Locale.ROOT)));}rows=List.copyOf(prepared);indexedSource=snapshotSource;}
            List<Row> snapshot=rows;
            List<JsonNode> matched=new ArrayList<>();for(Row row:snapshot){if(Thread.currentThread().isInterrupted())return;if(row.search().contains(query)&&criteria.matches(row.value())&&(interval==null||interval.includes(row.value())))matched.add(row.value());}
            if(!sorting.isEmpty())matched.sort((a,b)->{for(var sort:sorting){int c=compareValues(a.path(sort.getKey()),b.path(sort.getKey()));if(c!=0)return sort.getValue()?-c:c;}return 0;});
            int requestedPage=requested;
            if(reveal&&!identity.isEmpty())for(int i=0;i<matched.size();i++)if(identity.equals(identity(matched.get(i)))){requestedPage=i/500;break;}
            int pages=Math.max(1,(matched.size()+499)/500),current=Math.min(requestedPage,pages-1),size=matched.size();
            List<JsonNode> values=List.copyOf(matched.subList(Math.min(current*500,size),Math.min((current+1)*500,size)));
            Platform.runLater(()->{if(closed||version!=generation)return;page=current;submitting=true;
                table.getItems().setAll(values);for(JsonNode row:values)if(!identity.isEmpty()&&identity.equals(identity(row))){table.getSelectionModel().select(row);break;}
                submitting=false;updateDetails(table.getSelectionModel().getSelectedItem());onSelection.accept(table.getSelectionModel().getSelectedItem());
                count.setText("匹配 "+size+" / 全部 "+snapshot.size()+" · 第 "+(current+1)+" / "+pages+" 页（全局搜索与排序）"+(interval==null?"":" · 时间范围已筛选；无时间戳证据不显示")+"");previous.setDisable(current==0);next.setDisable(current>=pages-1);
            });
        });
    }
    private void updateDetails(JsonNode row) {
        if(submitting)return;
        long version=++detailGeneration;
        if(row==null){raw.clear();summary.clear();return;}
        Resolver selectedResolver=resolver;
        if(selectedResolver!=null){summary.setText("正在读取并核验所选证据…");raw.clear();}
        worker.submit(()->{
            JsonNode resolved=row;
            if(selectedResolver!=null)try{resolved=selectedResolver.resolve(row);}catch(Exception error){var failed=row.deepCopy();if(failed.isObject())((com.fasterxml.jackson.databind.node.ObjectNode)failed).put("核验失败",Objects.toString(error.getMessage(),error.getClass().getSimpleName()));resolved=failed;}
            JsonNode value=resolved;
            String text=value.toPrettyString();StringBuilder out=new StringBuilder();value.fields().forEachRemaining(e->{if(!e.getValue().isNull())out.append(label(e.getKey())).append("：").append(format(e.getKey(),e.getValue())).append('\n');});
            String rawText=bounded(text),summaryText=bounded(out.toString());
            Platform.runLater(()->{if(closed||version!=detailGeneration)return;setTextPreserving(raw,rawText);setTextPreserving(summary,summaryText);});
        });
    }
    private static String bounded(String text){return text.length()<=131072?text:text.substring(0,131072)+"\n显示已截断；完整原值保留于会话文件。";}
    private static void setTextPreserving(TextArea area,String text){if(text.equals(area.getText()))return;double top=area.getScrollTop();area.setText(text);area.setScrollTop(top);}
    static int compareValues(JsonNode a,JsonNode b) {
        if(a.isNumber()&&b.isNumber())return a.decimalValue().compareTo(b.decimalValue());
        if(a.isTextual()&&b.isTextual()&&a.asText().matches("-?[0-9]+(?:\\.[0-9]+)?")&&b.asText().matches("-?[0-9]+(?:\\.[0-9]+)?"))return new java.math.BigDecimal(a.asText()).compareTo(new java.math.BigDecimal(b.asText()));
        if(a.isMissingNode()||a.isNull())return b.isMissingNode()||b.isNull()?0:-1;
        if(b.isMissingNode()||b.isNull())return 1;
        return a.asText().compareToIgnoreCase(b.asText());
    }
    public static String format(String field,JsonNode value) {
        if(value==null||value.isNull()||value.isMissingNode())return "—";
        if(value.isBoolean())return value.asBoolean()?"是":"否";
        if(field.toLowerCase(Locale.ROOT).contains("status")||field.equals("checkpoint"))return UiText.status(value.asText());
        if(value.isNumber()&&(field.equals("startedAtMillis")||field.equals("endedAtMillis")))return java.time.Instant.ofEpochMilli(value.asLong()).toString();
        if(value.isNumber()&&field.toLowerCase(Locale.ROOT).endsWith("nanos"))return duration(value.asDouble());
        if(value.isNumber()&&field.endsWith("Bytes"))return String.format(Locale.ROOT,"%.2f MiB",value.asDouble()/1048576);
        if(value.isArray()){List<String> lines=new ArrayList<>();value.forEach(v->lines.add(v.isTextual()?v.asText():v.toPrettyString()));return String.join("\n",lines);}
        return value.isValueNode()?value.asText():value.toPrettyString();
    }
    public static String duration(double nanos){if(Math.abs(nanos)<1000)return String.format(Locale.ROOT,"%.0f ns",nanos);if(Math.abs(nanos)<1e6)return String.format(Locale.ROOT,"%.2f μs",nanos/1e3);if(Math.abs(nanos)<1e9)return String.format(Locale.ROOT,"%.2f ms",nanos/1e6);return String.format(Locale.ROOT,"%.2f s",nanos/1e9);}
    private static String label(String key){return switch(key){case "stack","stackTrace"->"调用栈";case "lockOwnerName"->"锁持有者";case "lock"->"等待锁";case "method"->"方法";case "key"->"SQL / 聚合标识";case "observedCalls"->"观测次数";case "observedErrors"->"观测异常数";case "inclusiveNanos"->"累计耗时（包含子调用）";case "maxNanos"->"最大耗时";case "queueNanos"->"排队耗时";case "executionNanos"->"执行耗时";case "traceId"->"Trace ID";case "threadId"->"Java 线程 ID";default->key;};}
    private static String identity(JsonNode row){if(row==null)return "";for(String key:List.of("evidenceId","spanId","callPath","threadId","traceId","key","poolLabel","name","className"))if(row.has(key))return key+":"+(row.path(key).isContainerNode()?row.path(key).toString():row.path(key).asText())+(row.has("loader")?row.path("loader").asText():"");return row.toString();}
    @Override public void close(){closed=true;generation++;delay.stop();if(pending!=null)pending.cancel(true);worker.shutdownNow();}
}
