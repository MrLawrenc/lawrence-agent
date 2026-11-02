package com.agentmonitor.app.ui;

import com.fasterxml.jackson.databind.*;
import com.agentmonitor.core.util.AtomicFiles;
import javafx.application.Platform;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** Personal desktop state, separate from capture configuration and evidence. */
public final class DesktopPreferences {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final DesktopPreferences INSTANCE=new DesktopPreferences();
    private final Map<String,String> values=new HashMap<>();
    private final Set<Scene> scenes=Collections.newSetFromMap(new WeakHashMap<>());
    private final ScheduledExecutorService writer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"desktop-preferences");t.setDaemon(true);return t;});
    private final Path file=Path.of(System.getProperty("agentmonitor.ui.preferences",Path.of(System.getProperty("user.home"),".agent-monitor","desktop-preferences.json").toString()));
    private String generated;
    private ScheduledFuture<?> pendingSave;
    private DesktopPreferences(){try{if(Files.isRegularFile(file)&&Files.size(file)<1024*1024){JsonNode root=JSON.readTree(file.toFile());root.fields().forEachRemaining(e->{if(e.getValue().isTextual())values.put(e.getKey(),e.getValue().asText());});}}catch(Exception ignored){}}
    public static DesktopPreferences get(){return INSTANCE;}
    public synchronized String value(String key,String fallback){return values.getOrDefault(key,fallback);}
    public synchronized void put(String key,String value){if(key.length()>512||value.length()>32768)return;if(value.equals(values.get(key)))return;values.put(key,value);if(pendingSave!=null)pendingSave.cancel(false);pendingSave=writer.schedule(this::save,300,TimeUnit.MILLISECONDS);}
    private void save(){Map<String,String> copy;synchronized(this){copy=Map.copyOf(values);}try{Files.createDirectories(file.toAbsolutePath().getParent());AtomicFiles.write(file,JSON.writeValueAsString(copy));}catch(Exception error){com.agentmonitor.core.util.AppLog.warn("界面偏好未保存："+error.getMessage());}}
    public void flush(){try{writer.submit(this::save).get(2,TimeUnit.SECONDS);}catch(Exception error){com.agentmonitor.core.util.AppLog.warn("界面偏好未完成保存："+error.getMessage());}}
    public double cellSize(){return value("density","compact").equals("comfortable")?46:38;}
    public void install(Scene scene){if(!scenes.add(scene))return;apply(scene);scene.rootProperty().addListener((o,a,b)->apply(scene));}
    public void applyAll(){generated=null;for(Scene scene:new ArrayList<>(scenes))apply(scene);}
    private void apply(Scene scene){
        try{
            if(generated==null){String css;try(var input=getClass().getResourceAsStream("/com/agentmonitor/app/main.css")){css=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
                double scale=Double.parseDouble(value("fontScale","1.0"));scale=Math.max(.85,Math.min(1.5,scale));Matcher fonts=Pattern.compile("(-fx-font-size:\\s*)([0-9.]+)px").matcher(css);StringBuffer out=new StringBuffer();while(fonts.find())fonts.appendReplacement(out,Matcher.quoteReplacement(fonts.group(1)+String.format(Locale.ROOT,"%.2fpx",Double.parseDouble(fonts.group(2))*scale)));fonts.appendTail(out);css=out.toString();
                if(value("theme","light").equals("dark"))css=dark(css);
                if(value("density","compact").equals("comfortable"))css+="\n.table-row-cell,.tree-table-row-cell{-fx-cell-size:46px;} .button,.menu-button{-fx-padding:10 14;}";
                Path sheet=Files.createTempFile("agent-monitor-desktop-",".css");Files.writeString(sheet,css);sheet.toFile().deleteOnExit();generated=sheet.toUri().toString();
            }
            scene.getStylesheets().removeIf(url->url.contains("/main.css")||url.contains("agent-monitor-desktop-"));scene.getStylesheets().add(generated);
            styleTables(scene.getRoot());
        }catch(Exception error){com.agentmonitor.core.util.AppLog.warn("界面偏好应用失败："+error.getMessage());}
    }
    private void styleTables(Node node){double height=value("density","compact").equals("comfortable")?46:38;if(node instanceof TableView<?> table)table.setFixedCellSize(height);if(node instanceof TreeTableView<?> tree)tree.setFixedCellSize(height);if(node instanceof Parent parent)parent.getChildrenUnmodifiable().forEach(this::styleTables);}
    private static String dark(String css){
        Pattern declarations=Pattern.compile("(-fx-(text-fill|stroke|background-color|border-color):\\s*)([^;]+)");Matcher matches=declarations.matcher(css);StringBuffer out=new StringBuffer();
        while(matches.find()){
            String property=matches.group(2),body=matches.group(3);Matcher colors=Pattern.compile("#[0-9a-fA-F]{6}").matcher(body);StringBuffer transformed=new StringBuffer();
            while(colors.find()){Color color=Color.web(colors.group());String replacement=colors.group();
                if(property.equals("text-fill")||property.equals("stroke")){if(color.getBrightness()<.7)replacement=hex(Color.hsb(color.getHue(),Math.min(.5,color.getSaturation()),.9));}
                else if(property.equals("background-color")&&color.getBrightness()>.65){replacement=color.getSaturation()<.15?"#1D2930":color.getHue()>70&&color.getHue()<190?"#193D34":color.getHue()<40?"#422D28":"#26394B";}
                else if(property.equals("border-color")&&color.getBrightness()>.6)replacement="#4A606B";
                colors.appendReplacement(transformed,Matcher.quoteReplacement(replacement));
            }colors.appendTail(transformed);matches.appendReplacement(out,Matcher.quoteReplacement(matches.group(1)+transformed));
        }matches.appendTail(out);
        return out+"\n.root{-ui-canvas:#10191E;-ui-surface:#17232A;-ui-subtle:#1D2B33;-ui-border:#435660;-ui-ink:#E4EDF1;-ui-muted:#ACBEC7;-ui-accent:#72D3BD;-ui-accent-soft:#244A40;} .primary-btn,.start-btn{-fx-background-color:#176B60;-fx-text-fill:#FFFFFF;} .text-area .content{-fx-background-color:-ui-surface;} .chart-plot-background{-fx-background-color:-ui-surface;}";
    }
    private static String hex(Color color){return String.format(Locale.ROOT,"#%02X%02X%02X",(int)(color.getRed()*255),(int)(color.getGreen()*255),(int)(color.getBlue()*255));}
    public Node pane(){
        ComboBox<String> theme=new ComboBox<>();theme.getItems().setAll("浅色","深色");theme.getSelectionModel().select(value("theme","light").equals("dark")?1:0);theme.setId("preferences-theme");theme.setAccessibleText("界面主题");
        ComboBox<String> font=new ComboBox<>();font.getItems().setAll("85%","100%","115%","130%","150%");List<String> scales=List.of("0.85","1.0","1.15","1.3","1.5");font.getSelectionModel().select(Math.max(0,scales.indexOf(value("fontScale","1.0"))));font.setId("preferences-font");font.setAccessibleText("字号缩放");
        ComboBox<String> density=new ComboBox<>();density.getItems().setAll("紧凑","舒适");density.getSelectionModel().select(value("density","compact").equals("comfortable")?1:0);density.setAccessibleText("表格密度");
        Label feedback=new Label("设置自动保存；采集配置独立维护。");feedback.setWrapText(true);
        theme.setOnAction(e->{put("theme",theme.getSelectionModel().getSelectedIndex()==1?"dark":"light");applyAll();feedback.setText("主题已应用并保存。");});
        font.setOnAction(e->{put("fontScale",scales.get(font.getSelectionModel().getSelectedIndex()));applyAll();feedback.setText("字号已应用并保存。");});
        density.setOnAction(e->{put("density",density.getSelectionModel().getSelectedIndex()==1?"comfortable":"compact");applyAll();feedback.setText("密度已应用；新打开的数据表使用相同偏好。");});
        VBox content=new VBox(16,new Label("主题"),theme,new Label("字号"),font,new Label("数据密度"),density,feedback);content.setPadding(new javafx.geometry.Insets(12));ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);return scroll;
    }
    public void remember(SplitPane split,String key,double initial){double stored;try{stored=Double.parseDouble(value(key,Double.toString(initial)));}catch(Exception invalid){stored=initial;}split.setDividerPositions(Math.max(.1,Math.min(.9,stored)));if(!split.getDividers().isEmpty())split.getDividers().get(0).positionProperty().addListener((o,a,b)->put(key,Double.toString(b.doubleValue())));}
}
