package com.agentmonitor.app.ui;

import com.fasterxml.jackson.databind.JsonNode;
import javafx.scene.control.*;
import javafx.scene.layout.FlowPane;
import java.util.*;

/** Display-only predicates shared by trace trees and evidence tables. */
public final class EvidenceFilters extends FlowPane {
    public record Criteria(double minimumNanos,boolean errorsOnly,String thread,String endpoint){
        public boolean matches(long duration,boolean error,String threadName,String route){return duration>=minimumNanos&&(!errorsOnly||error)&&threadName.toLowerCase(Locale.ROOT).contains(thread)&&route.toLowerCase(Locale.ROOT).contains(endpoint);}
        public boolean matches(JsonNode row){
            long nanos=row.path("durationNanos").asLong(row.path("maxNanos").asLong(row.path("inclusiveNanos").asLong(row.path("elapsedMillis").asLong()*1000000)));
            boolean error=row.path("error").asBoolean()||row.path("observedErrors").asLong()>0||row.path("errors").asLong()>0;
            String name=row.path("threadName").asText(row.path("thread").asText(row.path("name").asText()));
            String route=row.path("endpoint").asText(row.path("route").asText(row.path("key").asText()));return matches(nanos,error,name,route);
        }
    }
    private final TextField minimum=new TextField(),thread=new TextField(),endpoint=new TextField();
    private final CheckBox errors=new CheckBox("仅异常");
    private final Label feedback=new Label();
    private Criteria active=new Criteria(0,false,"","");
    private Runnable changed=()->{};
    private final String key;
    private TextField query;
    public void setQueryField(TextField field){query=field;}
    public EvidenceFilters(String key){
        this.key=key;setHgap(8);setVgap(6);minimum.setPromptText("耗时 ≥ ms");thread.setPromptText("指定线程");endpoint.setPromptText("指定接口 / 路由");minimum.setPrefWidth(115);thread.setPrefWidth(155);endpoint.setPrefWidth(175);
        if(key.equals("calls")){minimum.setPromptText("根调用 ≥ ms");thread.setPromptText("根调用线程");}
        minimum.setAccessibleText("最小耗时毫秒");thread.setAccessibleText("线程过滤");endpoint.setAccessibleText("接口过滤");errors.setAccessibleText("仅显示异常证据");
        Button reset=new Button("清除条件"),save=new Button("保存视图"),restore=new Button("恢复视图");
        getChildren().addAll(minimum,errors,thread,endpoint,reset,save,restore,feedback);
        minimum.textProperty().addListener((o,a,b)->update());thread.textProperty().addListener((o,a,b)->update());endpoint.textProperty().addListener((o,a,b)->update());errors.selectedProperty().addListener((o,a,b)->update());
        reset.setOnAction(e->clear());
        save.setOnAction(e->{if(update()){try{String value=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(List.of(minimum.getText(),Boolean.toString(errors.isSelected()),thread.getText(),endpoint.getText(),query==null?"":query.getText()));if(value.length()>32768){feedback.setText("视图内容过长，请缩短过滤条件后保存。");return;}DesktopPreferences.get().put("filters."+key,value);feedback.setText("视图已保存");}catch(Exception error){feedback.setText("视图保存失败："+error.getMessage());}}});
        restore.setOnAction(e->restore());
    }
    public void setOnChanged(Runnable action){changed=action;}
    public Criteria criteria(){return active;}
    public void clear(){minimum.clear();errors.setSelected(false);thread.clear();endpoint.clear();}
    private boolean update(){try{double value=minimum.getText().isBlank()?0:Double.parseDouble(minimum.getText());if(!Double.isFinite(value)||value<0)throw new NumberFormatException();active=new Criteria(value*1000000,errors.isSelected(),thread.getText().toLowerCase(Locale.ROOT),endpoint.getText().toLowerCase(Locale.ROOT));feedback.setText("");changed.run();return true;}catch(NumberFormatException invalid){feedback.setText("耗时请输入非负数字；保留上一组有效条件。");return false;}}
    private void restore(){String saved=DesktopPreferences.get().value("filters."+key,"");if(saved.isBlank()){feedback.setText("尚无已保存视图");return;}String[] fields;try{fields=saved.startsWith("[")?new com.fasterxml.jackson.databind.ObjectMapper().readValue(saved,String[].class):saved.split("\u001f",-1);}catch(Exception invalid){feedback.setText("已保存视图格式无效，请重新保存。");return;}if(fields.length>=4){minimum.setText(fields[0]);errors.setSelected(Boolean.parseBoolean(fields[1]));thread.setText(fields[2]);endpoint.setText(fields[3]);if(fields.length>4&&query!=null)query.setText(fields[4]);update();}}
}
