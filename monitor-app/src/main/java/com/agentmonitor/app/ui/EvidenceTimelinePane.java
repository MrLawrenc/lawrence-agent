package com.agentmonitor.app.ui;

import com.fasterxml.jackson.databind.*;
import javafx.scene.canvas.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import java.util.*;
import java.util.function.Consumer;

/** Timestamp-based evidence lanes. Width never represents per-request CPU or inferred causality. */
public final class EvidenceTimelinePane extends VBox {
    private record Event(double from,double to,String label,String lane,JsonNode source){ }
    private final Canvas canvas=new Canvas(900,260);
    private final Label range=new Label(),boundary=new Label("按实际时间戳绘制；毫秒时间戳不能精确拆分亚毫秒调用。重叠表示关联，不能证明因果或关键路径。");
    private final TextArea details=new TextArea();
    private final ScrollBar pan=new ScrollBar();
    private List<Event> events=List.of();
    private double start,end,from,to,drag=-1;
    private final List<double[]> hitboxes=new ArrayList<>();
    public record TimeRange(double fromMillis,double toMillis) {
        public boolean includes(JsonNode row){double begin=row.path("startedAtMillis").asDouble(row.path("startedAtEpochMillis").asDouble());double finish=row.path("endedAtMillis").asDouble(begin+row.path("durationNanos").asDouble()/1000000);return begin>0&&finish>=fromMillis&&begin<=toMillis;}
    }
    private Consumer<JsonNode> onSelect=ignored->{};
    private Consumer<TimeRange> onRange=ignored->{};
    private JsonNode selectedEvidence;
    private boolean drawing;

    public EvidenceTimelinePane(){
        javafx.collections.ListChangeListener<String> styles=change->draw();sceneProperty().addListener((o,before,scene)->{if(before!=null)before.getStylesheets().removeListener(styles);if(scene!=null){scene.getStylesheets().addListener(styles);draw();}});
        setSpacing(8);range.setId("evidence-timeline-range");canvas.setId("evidence-timeline-canvas");Button reset=new Button("重置视窗"),in=new Button("放大"),out=new Button("缩小");reset.setId("evidence-timeline-reset");reset.setOnAction(e->{from=start;to=end;draw();onRange.accept(null);});in.setOnAction(e->zoom(.5));out.setOnAction(e->zoom(2));
        boundary.setWrapText(true);details.setEditable(false);details.setWrapText(true);details.setPrefRowCount(4);details.setAccessibleText("选中时间线证据详情");canvas.setAccessibleRole(javafx.scene.AccessibleRole.IMAGE_VIEW);canvas.setAccessibleText("证据时间线；放大、缩小与框选同步筛选下方证据表。可用键盘在证据表中查看相同数据。");
        ScrollPane scroll=new ScrollPane(canvas);scroll.setMinHeight(120);scroll.setFitToWidth(true);scroll.viewportBoundsProperty().addListener((o,a,b)->{canvas.setWidth(Math.max(400,b.getWidth()-4));draw();});VBox.setVgrow(scroll,Priority.ALWAYS);
        pan.setMin(0);pan.setMax(1);pan.setAccessibleText("时间线平移");pan.valueProperty().addListener((o,a,b)->{double width=to-from;if(!drawing&&width<end-start){from=start+(end-start-width)*b.doubleValue();to=from+width;draw();onRange.accept(new TimeRange(from,to));}});
        Tooltip hint=new Tooltip();Tooltip.install(canvas,hint);canvas.setOnMouseMoved(e->{for(int i=hitboxes.size()-1;i>=0;i--){double[] box=hitboxes.get(i);if(e.getX()>=box[0]&&e.getX()<=box[0]+box[2]&&e.getY()>=box[1]&&e.getY()<=box[1]+box[3]){Event event=events.get((int)box[4]);hint.setText(event.label()+String.format(Locale.ROOT," · %.3f ms",event.to()-event.from()));return;}}hint.setText("拖拽框选时间区间，点击证据查看原值。");});
        canvas.setOnMousePressed(e->drag=e.getX());canvas.setOnMouseReleased(e->{double first=drag;drag=-1;if(Math.abs(e.getX()-first)>8){double width=canvas.getWidth()-200;double a=Math.max(0,Math.min(1,(Math.min(first,e.getX())-190)/width)),b=Math.max(0,Math.min(1,(Math.max(first,e.getX())-190)/width));if(b>a){double old=from;double duration=to-from;from=old+a*duration;to=old+b*duration;draw();onRange.accept(new TimeRange(from,to));}}else select(e.getX(),e.getY());});
        getChildren().addAll(new FlowPane(8,6,reset,in,out,range),pan,scroll,boundary,details);
    }
    public void setOnSelect(Consumer<JsonNode> action){onSelect=action;}
    public void setOnRangeChanged(Consumer<TimeRange> action){onRange=action;}
    public void setShowRawDetails(boolean show){details.setVisible(show);details.setManaged(show);}
    public void highlightEvidence(JsonNode row){selectedEvidence=row;draw();}
    public void setWindow(TimeRange range){from=Math.max(start,range.fromMillis());to=Math.min(end,range.toMillis());if(to<=from){from=start;to=end;}draw();onRange.accept(new TimeRange(from,to));}
    private static boolean sameEvidence(JsonNode a,JsonNode b){
        if(a==null||b==null)return false;
        for(String key:List.of("evidenceId","callPath"))if(a.has(key)&&b.has(key))return a.path(key).equals(b.path(key));
        return a.equals(b);
    }
    public void setEvents(JsonNode rows){
        List<Event> parsed=new ArrayList<>();int omitted=0;
        for(JsonNode row:rows){double begin=row.path("startedAtMillis").asDouble(row.path("startedAtEpochMillis").asDouble());double finish=row.path("endedAtMillis").asDouble(begin+row.path("durationNanos").asDouble()/1000000);
            if(begin<=0){omitted++;continue;}String kind=row.path("kind").asText("span"),thread=row.path("threadName").asText(row.path("threadId").asText());
            String lane=row.has("callDepth")?"  ".repeat(Math.min(12,row.path("callDepth").asInt()))+row.path("method").asText():Set.of("span","BUSINESS","HTTP_SERVER","HTTP_CLIENT","SQL","REDIS","MESSAGING").contains(kind)?(kind.equals("span")?"调用":kind)+" · "+thread:kind+" · "+thread;
            parsed.add(new Event(begin,Math.max(begin,finish),row.path("method").asText(row.path("eventType").asText(kind)),lane,row));if(parsed.size()>=5000)break;
        }
        events=List.copyOf(parsed);start=events.stream().mapToDouble(Event::from).min().orElse(0);end=events.stream().mapToDouble(Event::to).max().orElse(1);if(end<=start)end=start+1;from=start;to=end;
        boundary.setText("已绘制 "+events.size()+" 条证据 · 无时间戳跳过 "+omitted+" 条；最多绘制 5000 条、100 条轨道。时间重叠只表示关联；原始表格保留完整查询结果。毫秒时间戳不能精确拆分亚毫秒调用。");draw();
    }
    private void zoom(double ratio){double width=Math.min(end-start,Math.max(.01,(to-from)*ratio)),middle=(from+to)/2;from=Math.max(start,Math.min(end-width,middle-width/2));to=from+width;draw();onRange.accept(new TimeRange(from,to));}
    private void draw(){
        LinkedHashMap<String,Integer> lanes=new LinkedHashMap<>();for(Event event:events)if(!lanes.containsKey(event.lane())&&lanes.size()<100)lanes.put(event.lane(),lanes.size());
        canvas.setHeight(Math.max(220,lanes.size()*34+50));GraphicsContext g=canvas.getGraphicsContext2D();boolean dark=DesktopPreferences.get().value("theme","light").equals("dark");g.setFill(Color.web(dark?"#17232A":"#FFFFFF"));g.fillRect(0,0,canvas.getWidth(),canvas.getHeight());g.setFill(Color.web(dark?"#E4EDF1":"#202B30"));
        g.setFont(javafx.scene.text.Font.font(12*Double.parseDouble(DesktopPreferences.get().value("fontScale","1.0"))));
        double width=Math.max(1,canvas.getWidth()-200),duration=Math.max(.001,to-from);hitboxes.clear();
        for(var lane:lanes.entrySet()){g.fillText(lane.getKey(),8,48+lane.getValue()*34,175);}
        for(int i=0;i<events.size();i++){Event event=events.get(i);Integer lane=lanes.get(event.lane());if(lane==null||event.to()<from||event.from()>to)continue;double x=190+Math.max(0,(event.from()-from)/duration)*width,w=Math.max(2,(Math.min(to,event.to())-Math.max(from,event.from()))/duration*width),y=32+lane*34;
            g.setFill(Color.web(event.source().path("error").asBoolean()?"#B84B55":event.lane().startsWith("span")||event.lane().startsWith("调用")?"#287B6A":event.lane().startsWith("jfr")?"#8C6E34":"#426F99"));g.fillRoundRect(x,y,w,23,3,3);if(sameEvidence(event.source(),selectedEvidence)){g.setStroke(Color.web(dark?"#FFFFFF":"#202B30"));g.setLineWidth(2);g.strokeRoundRect(x-1,y-1,w+2,25,3,3);}if(w>85){g.setFill(Color.WHITE);g.fillText(event.label(),x+5,y+16,w-10);}hitboxes.add(new double[]{x,y,w,23,i});
        }
        drawing=true;try{pan.setValue(end-start<=to-from?0:(from-start)/Math.max(.001,end-start-(to-from)));pan.setDisable(to-from>=end-start);}finally{drawing=false;}
        range.setText(events.isEmpty()?"暂无带时间戳的证据":String.format(Locale.ROOT,"相对首条 %.3f — %.3f ms · 可拖拽框选",from-start,to-start));
    }
    private void select(double x,double y){for(int i=hitboxes.size()-1;i>=0;i--){double[] box=hitboxes.get(i);if(x>=box[0]&&x<=box[0]+box[2]&&y>=box[1]&&y<=box[1]+box[3]){Event selected=events.get((int)box[4]);selectedEvidence=selected.source();String text=selected.label()+"\n"+selected.source().toPrettyString();details.setText(text.length()>131072?text.substring(0,131072)+"\n显示已截断；完整原值请查看证据表。":text);draw();onSelect.accept(selected.source());return;}}}
    public static JsonNode fromTrace(com.agentmonitor.model.trace.TraceNode root){
        ObjectMapper json=new ObjectMapper();var rows=json.createArrayNode();record Visit(com.agentmonitor.model.trace.TraceNode node,int depth,List<Integer> path) { }
        var pending=new ArrayDeque<Visit>();pending.add(new Visit(root,0,List.of()));
        while(!pending.isEmpty()&&rows.size()<5000){var visit=pending.removeFirst();var node=visit.node();var row=rows.addObject();row.put("callDepth",visit.depth());row.set("callPath",json.valueToTree(visit.path()));row.put("spanId",node.getAttributes().getOrDefault("agent.span.id",""));row.put("method",node.getFullClassName()+"#"+node.getMethodName2());row.put("kind",node.getKind().name());row.put("threadName",node.getThread());row.put("traceId",node.getTraceId());row.put("durationNanos",node.getDurationNanos());row.put("error",node.isError());try{row.put("startedAtEpochMillis",Long.parseLong(node.getAttributes().getOrDefault("agent.span.started_at_epoch_millis","0")));}catch(NumberFormatException ignored){}for(int i=0;i<node.getChildren().size()&&pending.size()+rows.size()<5000;i++){var path=new ArrayList<>(visit.path());path.add(i);pending.add(new Visit(node.getChildren().get(i),visit.depth()+1,List.copyOf(path)));}}
        return rows;
    }
}
