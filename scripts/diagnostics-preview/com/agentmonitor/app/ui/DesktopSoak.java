package com.agentmonitor.app.ui;

import javafx.application.*;
import javafx.animation.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import com.agentmonitor.core.model.MonitoringSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Read-only production-control soak against an explicitly supplied owned capture. */
public final class DesktopSoak extends Application {
    private final ObjectMapper json=new ObjectMapper();
    private DiagnosticsPane pane;
    private AnimationTimer pulse;
    private Timeline refresh;
    private long previous,maxGap,started,lastWrite;
    private int samples;
    private Path output;
    private long seconds;
    private boolean completed;
    @Override public void start(Stage stage)throws Exception {
        Path session=Path.of(getParameters().getRaw().get(0));seconds=Long.parseLong(getParameters().getRaw().get(1));
        output=Path.of("build/ui-soak",Instant.now().toString().replace(':','-'));Files.createDirectories(output);
        var marker=json.readTree(session.resolve("session.json").toFile());
        var identity=new MonitoringSession(marker.path("sessionId").asText(),session.getParent(),session,Instant.parse(marker.path("startedAt").asText()));
        pane=new DiagnosticsPane("soak-owned-target",()->identity,()->null);Scene scene=new Scene(pane,1080,720);scene.getStylesheets().add(getClass().getResource("/com/agentmonitor/app/main.css").toExternalForm());
        DesktopPreferences.get().install(scene);
        stage.setTitle("Agent Monitor · 自建 JVM 稳定性验证");stage.setScene(scene);stage.show();started=System.nanoTime();
        pulse=new AnimationTimer(){@Override public void handle(long now){if(previous>0)maxGap=Math.max(maxGap,now-previous);previous=now;if(now-lastWrite>5_000_000_000L){lastWrite=now;record(now);}}};pulse.start();
        refresh=new Timeline(new KeyFrame(Duration.seconds(2),e->{pane.refreshEvidence();var sections=(ListView<?>)pane.lookup("#diagnostics-sections");sections.getSelectionModel().select(samples%2==0?0:2);}));refresh.setCycleCount(Timeline.INDEFINITE);refresh.play();
        var finish=new PauseTransition(Duration.seconds(seconds));finish.setOnFinished(e->{record(System.nanoTime());try{Files.writeString(output.resolve("summary.json"),json.writeValueAsString(Map.of("status","COMPLETED","elapsedSeconds",(System.nanoTime()-started)/1e9,"samples",samples,"maxPulseGapMillis",maxGap/1e6,"sessionDirectory",session.toString(),"boundary","Read-only UI against the owned capture; no extra Attach, no cross-platform claim")));Files.copy(output.resolve("summary.json"),output.resolve("status.json"),StandardCopyOption.REPLACE_EXISTING);completed=true;}catch(Exception error){throw new RuntimeException(error);}stage.close();Platform.exit();});finish.play();
        stage.setOnCloseRequest(e->stop());System.out.println("UI_SOAK_OUTPUT="+output.toAbsolutePath());
    }
    private void record(long now){try{samples++;Files.writeString(output.resolve("pulses.jsonl"),json.writeValueAsString(Map.of("timestampMillis",System.currentTimeMillis(),"elapsedSeconds",(now-started)/1e9,"maxPulseGapMillis",maxGap/1e6,"heapUsedBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()))+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);Files.writeString(output.resolve("status.json"),json.writeValueAsString(Map.of("status","RUNNING","elapsedSeconds",(now-started)/1e9,"plannedSeconds",seconds)));}catch(Exception error){throw new RuntimeException(error);}}
    @Override public void stop(){if(!completed&&output!=null)try{Files.writeString(output.resolve("status.json"),json.writeValueAsString(Map.of("status","INTERRUPTED","elapsedSeconds",(System.nanoTime()-started)/1e9,"plannedSeconds",seconds)));}catch(Exception ignored){}if(pulse!=null)pulse.stop();if(refresh!=null)refresh.stop();if(pane!=null)pane.close();}
    public static void main(String[] args){launch(args);}
}
