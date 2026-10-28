package com.agentmonitor.app.ui;

import com.agentmonitor.app.model.CallNode;
import com.agentmonitor.model.span.SpanAttribute;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import java.util.ArrayDeque;

/** Inspect a selected call without nested dialogs or truncating captured values. */
public final class CallDetailPane extends VBox {
    private final Label method=new Label("选择一条调用"),summary=new Label("在左侧选中方法，查看调用上下文。");
    private final Label requestValue=new Label(),routeValue=new Label(),statusValue=new Label(),threadValue=new Label(),traceValue=new Label(),spanValue=new Label(),captureNote=new Label();
    private final TextArea overview=readable(),payload=readable(),errors=readable(),attributes=readable();
    private CallNode displayed;
    public CallDetailPane(Runnable close) {
        setId("call-detail-pane");getStyleClass().add("call-detail-pane");setSpacing(10);setMinWidth(285);setPrefWidth(390);
        Label title=new Label("调用详情");title.getStyleClass().add("card-title");
        Button dismiss=new Button("收起");dismiss.setOnAction(e->close.run());dismiss.getStyleClass().add("ghost-btn");
        Region space=new Region();HBox.setHgrow(space,Priority.ALWAYS);HBox head=new HBox(8,title,space,dismiss);head.setAlignment(Pos.CENTER_LEFT);
        method.setId("call-detail-method");method.setWrapText(true);method.getStyleClass().add("call-detail-method");
        summary.setId("call-detail-summary");summary.setWrapText(true);summary.getStyleClass().add("hint-label");
        threadValue.setId("call-detail-thread");
        overview.setId("call-detail-overview");payload.setId("call-detail-payload");errors.setId("call-detail-errors");attributes.setId("call-detail-attributes");
        VBox request=section("请求与方法",row("入口",requestValue),row("路由",routeValue),row("状态",statusValue));
        VBox context=section("执行上下文",row("线程",threadValue),row("Trace ID",traceValue),row("Span ID",spanValue));
        captureNote.setWrapText(true);captureNote.getStyleClass().add("call-detail-note");
        overview.setPrefRowCount(7);
        TitledPane raw=new TitledPane("完整上下文",overview);raw.setExpanded(false);raw.getStyleClass().add("call-detail-raw");
        VBox overviewBody=new VBox(10,request,context,captureNote,raw);overviewBody.getStyleClass().add("call-detail-sections");
        ScrollPane overviewScroll=new ScrollPane(overviewBody);overviewScroll.setFitToWidth(true);overviewScroll.getStyleClass().add("call-detail-scroll");
        TabPane tabs=new TabPane(new Tab("概览",overviewScroll),tab("参数",payload),tab("异常",errors),tab("属性",attributes));tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);VBox.setVgrow(tabs,Priority.ALWAYS);
        Label hint=new Label("耗时包含子调用；占比相对本地根调用，不代表 CPU 占用。");hint.getStyleClass().add("hint-label");hint.setWrapText(true);
        getChildren().addAll(head,method,summary,tabs,hint);
    }
    public void show(CallNode node,long rootDuration) {
        String span=node.getAttributes().getOrDefault("agent.span.id","");
        boolean same=displayed==node || (displayed!=null && !span.isBlank() && !node.getTraceId().isBlank()
                && node.getTraceId().equals(displayed.getTraceId()) && span.equals(displayed.getAttributes().get("agent.span.id")));
        displayed=node;
        method.setText(node.getFullClassName()+"#"+node.getMethodName2());
        String ratio=rootDuration>0?String.format(java.util.Locale.ROOT," · 占根调用 %.1f%%",node.getDurationNanos()*100.0/rootDuration):"";
        String kind=switch(node.getKind()){case BUSINESS->"业务方法";case SQL->"SQL";case HTTP_SERVER->"HTTP 入口";case HTTP_CLIENT->"HTTP 出站";case REDIS->"Redis";case MESSAGING->"消息发布";default->node.getKind().toString();};
        summary.setText(node.getDurationDisplay()+ratio+" · "+(node.isError()?"异常":"未标记异常")+" · "+kind);
        String httpMethod=node.getAttributes().getOrDefault(SpanAttribute.HTTP_REQUEST_METHOD,"");
        String path=node.getAttributes().getOrDefault(SpanAttribute.URL_PATH,"");
        requestValue.setText(httpMethod.isBlank()&&path.isBlank()?node.getFullClassName()+"#"+node.getMethodName2():(httpMethod+" "+path).trim());
        routeValue.setText(orDash(node.getAttributes().get(SpanAttribute.HTTP_ROUTE)));
        statusValue.setText(orDash(node.getAttributes().get(SpanAttribute.HTTP_RESPONSE_STATUS_CODE)));
        rowVisible(routeValue,!"—".equals(routeValue.getText()));
        rowVisible(statusValue,!"—".equals(statusValue.getText()));
        threadValue.setText(orDash(node.getThread()));traceValue.setText(orDash(node.getTraceId()));spanValue.setText(orDash(span));
        CallNode child=node.getKind()==com.agentmonitor.model.span.SpanKind.HTTP_SERVER?firstBusinessArguments(node):null;
        String truncated=node.getAttributes().getOrDefault(SpanAttribute.CAPTURE_TRUNCATED,"");
        if(!truncated.isBlank())captureNote.setText("采集限制："+truncated+"。可在「属性」查看原始标记。");
        else if(node.getKind()==com.agentmonitor.model.span.SpanKind.HTTP_SERVER)captureNote.setText(child==null?
                "HTTP 入口只读取请求元数据，不读取请求体；可展开业务方法查看参数。":
                "HTTP 入口不读取请求体；下层业务方法的参数已列在「参数」。");
        else if(node.getArgs().isBlank())captureNote.setText(node.getSignature().startsWith("()")?"该方法没有入参。":"此 Span 没有参数快照；请核对采集配置和开销降级状态。");
        else captureNote.setText("参数与返回值快照可在「参数」查看。");
        StringBuilder info=new StringBuilder();field(info,"线程",node.getThread());field(info,"Trace ID",node.getTraceId());
        field(info,"Span ID",node.getAttributes().get("agent.span.id"));field(info,"父 Span",node.getAttributes().get("agent.span.parent_id"));
        field(info,"HTTP 状态",node.getAttributes().get(SpanAttribute.HTTP_RESPONSE_STATUS_CODE));field(info,"依赖类型",node.getAttributes().get(SpanAttribute.DEPENDENCY_TYPE));
        field(info,"数据源",node.getAttributes().get(SpanAttribute.DATASOURCE_NAME));field(info,"方法签名",CallTreePane.parseDescriptor(node.getSignature()));field(info,"JVM 签名",node.getSignature());update(overview,info.toString(),same);
        StringBuilder data=new StringBuilder();
        field(data,"此调用的参数",node.getArgs().isBlank()?(node.getKind()==com.agentmonitor.model.span.SpanKind.HTTP_SERVER?
                "HTTP 入口不读取请求体；路径参数和请求体通常由下层控制器接收。":
                node.getSignature().startsWith("()")?"该方法没有入参。":"未记录参数快照；请核对采集配置和开销降级标记。") :node.getArgs());
        if(child!=null)field(data,"下层业务参数 · "+child.getFullClassName()+"#"+child.getMethodName2(),child.getArgs());
        field(data,"此调用的返回值",node.getRetVal().isBlank()?(node.getKind()==com.agentmonitor.model.span.SpanKind.HTTP_SERVER?
                "HTTP 入口不读取响应体；可选择下层业务方法查看返回值。":"未记录返回值。"):node.getRetVal());
        field(data,"SQL",node.getAttributes().get(SpanAttribute.SQL));
        field(data,"SQL 参数",node.getAttributes().get(SpanAttribute.SQL_PARAMETERS));
        field(data,"HTTP query",node.getAttributes().get(SpanAttribute.HTTP_PARAMETERS));
        update(payload,data.toString(),same);
        String type=node.getAttributes().getOrDefault(SpanAttribute.ERROR_TYPE,"");update(errors,node.getStackTrace().isBlank()?(type.isBlank()?"当前调用未记录异常堆栈。":type):type+"\n\n"+node.getStackTrace(),same);
        try{update(attributes,new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(node.getAttributes()),same);}catch(Exception ignored){update(attributes,node.getAttributes().toString(),same);}
    }
    private static CallNode firstBusinessArguments(CallNode root){
        ArrayDeque<CallNode> queue=new ArrayDeque<>(root.getChildren());
        while(!queue.isEmpty()){
            CallNode next=queue.removeFirst();
            if(next.getKind()==com.agentmonitor.model.span.SpanKind.BUSINESS&&!next.getArgs().isBlank())return next;
            queue.addAll(next.getChildren());
        }
        return null;
    }
    private static VBox section(String title,HBox... rows){
        Label heading=new Label(title);heading.getStyleClass().add("call-detail-section-title");
        VBox card=new VBox(9,heading);card.getChildren().addAll(rows);card.getStyleClass().add("call-detail-card");return card;
    }
    private static HBox row(String name,Label value){
        Label key=new Label(name);key.getStyleClass().add("call-detail-key");key.setMinWidth(72);
        value.getStyleClass().add("call-detail-value");value.setWrapText(true);value.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(value,Priority.ALWAYS);
        HBox row=new HBox(8,key,value);row.setAlignment(Pos.TOP_LEFT);return row;
    }
    private static String orDash(String value){return value==null||value.isBlank()?"—":value;}
    private static void rowVisible(Label value,boolean visible){
        value.getParent().setVisible(visible);value.getParent().setManaged(visible);
    }
    private static void update(TextArea area,String value,boolean same){
        if(same&&value.equals(area.getText()))return;
        int anchor=area.getAnchor(),caret=area.getCaretPosition();double top=area.getScrollTop(),left=area.getScrollLeft();
        area.setText(value);
        if(same){area.selectRange(Math.min(anchor,value.length()),Math.min(caret,value.length()));area.setScrollTop(top);area.setScrollLeft(left);}
        else{area.positionCaret(0);area.setScrollTop(0);area.setScrollLeft(0);}
    }
    private static void field(StringBuilder out,String name,String value){
        if(value==null||value.isBlank())return;
        if(value.stripLeading().startsWith("{")||value.stripLeading().startsWith("["))try{value=CallTreePane.formatJsonText(value);}catch(Exception ignored){}
        out.append(name).append("\n").append(value).append("\n\n");
    }
    private static TextArea readable(){var area=new TextArea();area.setEditable(false);area.setWrapText(true);area.getStyleClass().add("call-detail-text");return area;}
    private static Tab tab(String name,TextArea text) {
        Button copy=new Button("复制内容");copy.getStyleClass().add("ghost-btn");copy.setOnAction(e->{var content=new ClipboardContent();content.putString(text.getText());Clipboard.getSystemClipboard().setContent(content);});
        HBox tools=new HBox(copy);tools.setAlignment(Pos.CENTER_RIGHT);VBox body=new VBox(8,tools,text);VBox.setVgrow(text,Priority.ALWAYS);return new Tab(name,body);
    }
}
