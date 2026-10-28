package com.agentmonitor.app.ui;
import javafx.application.*;
import javafx.animation.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.chart.*;
import javafx.stage.*;
import javafx.util.Duration;
import com.agentmonitor.core.model.MonitoringSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
public final class DiagnosticsPreview extends Application {
    private static int failures;
    public void start(Stage owner)throws Exception{
        Path marker=Path.of("../monitor-core/build/diagnostic-verification/latest.txt");Path session=Path.of(Files.readString(marker).trim());
        var metadata=new ObjectMapper().readTree(session.resolve("session.json").toFile());
        var identity=new MonitoringSession(metadata.path("sessionId").asText(),session.getParent(),session,Instant.parse(metadata.path("startedAt").asText()));
        owner.setScene(new Scene(new javafx.scene.layout.StackPane(),100,100));owner.getScene().getStylesheets().add(getClass().getResource("/com/agentmonitor/app/main.css").toExternalForm());
        var active=new java.util.concurrent.atomic.AtomicReference<MonitoringSession>(identity);
        var window=new DiagnosticsWindow(owner,"离线验证",active::get,()->null);window.show();
        after(2500,()->{
            try{
                var tabs=(TabPane)window.getScene().lookup(".tab-pane");require(tabs.getTabs().size()==10,"Ten diagnostic evidence tabs");
                var chart=(LineChart<?,?>)window.getScene().lookup(".chart");require(!chart.getData().isEmpty()&&chart.getData().get(0).getData().size()>1,"Real timestamped metrics render");
                capture(window.getScene(),"diagnostics-wide");
                tabs.getSelectionModel().select(1);window.getScene().getRoot().applyCss();window.getScene().getRoot().layout();
                var live=(TableView<?>)tabs.getTabs().get(1).getContent().lookup(".table-view");require(live.getItems().size()>0,"Unfinished invocation evidence renders");live.getSelectionModel().selectFirst();
                capture(window.getScene(),"diagnostics-live");
                tabs.getSelectionModel().select(2);window.getScene().getRoot().applyCss();window.getScene().getRoot().layout();
                var threads=(TableView<?>)tabs.getTabs().get(2).getContent().lookup(".table-view");int total=threads.getItems().size();require(total>1,"Thread evidence renders");
                var search=(TextField)tabs.getTabs().get(2).getContent().lookup(".text-field");search.setText("unfinished-business");require(threads.getItems().size()==1,"Thread search filters actual evidence");threads.getSelectionModel().selectFirst();
                window.setWidth(820);window.setHeight(640);
                after(300,()->{try{
                    capture(window.getScene(),"diagnostics-compact");
                    var refresh=(Button)window.getScene().lookup("#diagnostics-refresh");refresh.fire();
                    var capture=(MenuButton)window.getScene().lookup("#diagnostics-capture");require(!capture.isDisabled(),"Evidence refresh keeps diagnostic actions visually stable");
                    after(900,()->{try{
                        require(threads.getSelectionModel().getSelectedItem()!=null,"Refresh preserves thread selection");
                        require(((TextArea)tabs.getTabs().get(2).getContent().lookup(".text-area")).getText().contains("unfinished-business"),"Refresh preserves selected details");
                        tabs.getSelectionModel().select(8);window.getScene().getRoot().applyCss();window.getScene().getRoot().layout();
                        var classes=(TableView<?>)tabs.getTabs().get(8).getContent().lookup(".table-view");
                        ((TextField)tabs.getTabs().get(8).getContent().lookup(".text-field")).setText("DiagnosticTarget");require(!classes.getItems().isEmpty(),"Class search covers full evidence before pagination");
                        var pane=(DiagnosticsPane)window.getScene().getRoot();
                        Path invalid=Files.createTempDirectory(Path.of("build"),"ui-invalid-session-");
                        Files.createDirectories(invalid.resolve("diagnostics"));Files.writeString(invalid.resolve("diagnostics/threads-latest.json"),"{invalid");
                        active.set(new MonitoringSession("invalid-preview",invalid.getParent(),invalid,Instant.now()));pane.refreshEvidence();
                        require(threads.getItems().isEmpty()&&classes.getItems().isEmpty()&&chart.getData().isEmpty(),"Changing evidence source clears the previous source before loading");
                        after(700,()->{try{
                            require(((Label)pane.lookup("#diagnostics-status")).getText().startsWith("操作失败"),"An unreadable new session reports a load failure without old evidence");
                            capture(window.getScene(),"diagnostics-source-error");
                            active.set(null);pane.refreshEvidence();
                            require(threads.getItems().isEmpty()&&chart.getData().isEmpty(),"Returning to no current session clears historical rows and chart");
                            require(((Label)pane.lookup("#diagnostics-status")).getText().equals("等待采集或选择历史会话"),"No-session return clears the previous diagnostic status");
                            capture(window.getScene(),"diagnostics-no-session");
                            System.out.println("DIAGNOSTICS_UI_FAILURES="+failures);
                        }catch(Exception error){error.printStackTrace();failures++;}window.close();Platform.exit();if(failures>0)System.exit(1);});
                    }catch(Exception error){error.printStackTrace();failures++;window.close();Platform.exit();System.exit(1);}});
                }catch(Exception e){e.printStackTrace();window.close();Platform.exit();System.exit(1);}});
            }catch(Throwable e){e.printStackTrace();window.close();Platform.exit();System.exit(1);}
        });
    }
    private static void require(boolean condition,String message){if(!condition){failures++;System.err.println("FAIL "+message);}else System.out.println("PASS "+message);}
    private static void after(int millis,Runnable action){var pause=new PauseTransition(Duration.millis(millis));pause.setOnFinished(e->action.run());pause.play();}
    private static void capture(Scene scene,String name)throws Exception{scene.getRoot().applyCss();scene.getRoot().layout();var image=scene.getRoot().snapshot(null,null);BufferedImage png=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);for(int y=0;y<png.getHeight();y++)for(int x=0;x<png.getWidth();x++)png.setRGB(x,y,image.getPixelReader().getArgb(x,y));Path output=Path.of("build/ui-preview");Files.createDirectories(output);ImageIO.write(png,"png",output.resolve(name+".png").toFile());}
    public static void main(String[] args){launch(args);}
}
