import com.agentmonitor.app.controller.MainController;
import com.agentmonitor.app.ui.ClassSelectionPane;
import com.agentmonitor.core.service.JvmService;
import javafx.application.*;
import javafx.animation.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.util.Duration;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** Real controller/classpath check against an explicitly named development JVM. Does not attach Agent. */
public class ScopeSelectionSmoke extends Application {
    MainController controller;Timeline poll;int phase;long deadline;Path output;
    com.agentmonitor.model.config.MonitoringConfig beforeAdvanced;
    public void start(Stage stage)throws Exception {
        String pid=getParameters().getRaw().get(0);output=Path.of(getParameters().getRaw().get(1));Files.createDirectories(output);
        var target=JvmService.listProcesses().stream().filter(j->j.getPid().equals(pid)).findFirst().orElseThrow();
        controller=new MainController(target);var scene=new Scene(controller.getRoot(),1520,920);scene.getStylesheets().add(getClass().getResource("/com/agentmonitor/app/main.css").toExternalForm());stage.setTitle("真实配置联动回归");stage.setScene(scene);stage.show();
        deadline=System.currentTimeMillis()+60000;
        poll=new Timeline(new KeyFrame(Duration.millis(100),event->{try{
            if(System.currentTimeMillis()>deadline)throw new AssertionError("UI state timeout, phase="+phase);
            var scope=(ClassSelectionPane)scene.lookup(".scope-editor");
            @SuppressWarnings("unchecked") var tree=(TreeView<String>)scope.lookup("#scope-package-tree");
            var start=scene.getRoot().lookupAll(".button").stream().filter(n->n instanceof Button b&&b.getText().equals("开始监控")).map(n->(Button)n).findFirst().orElseThrow();
            String yaml=com.agentmonitor.core.config.MonitoringConfigYaml.write(controller.getConfiguration());
            if(phase==0&&!scope.isLoadingClasses()&&find(tree.getRoot(),"dev.perflab")!=null){
                tree.getSelectionModel().select(find(tree.getRoot(),"dev.perflab"));((Button)scope.lookup("#scope-include-package")).fire();phase=1;
            }else if(phase==1&&!start.isDisabled()&&yaml.contains("dev.perflab")){
                tree.getSelectionModel().select(find(tree.getRoot(),"dev.perflab.work"));((Button)scope.lookup("#scope-exclude-package")).fire();phase=2;
            }else if(phase==2&&!start.isDisabled()&&yaml.contains("pkg:dev.perflab.work")&&!scope.lookup("#scope-class-table").isDisabled()){
                if(!scope.getIncludedPackages().equals(java.util.List.of("dev.perflab")))throw new AssertionError("Parent package lost");
                if(scene.lookup(".yaml-preview")!=null||scene.lookup(".jvm-metrics-pane")==null)throw new AssertionError("YAML must be advanced, metrics must be visible");
                capture(scene,"parent-child-controller.png");beforeAdvanced=controller.getConfiguration();phase=3;
                openAdvanced(scene,dialog->{
                    var editor=(TextArea)dialog.lookup("#advanced-yaml-editor");editor.setText("version: 2\nscope: [invalid\n");
                    var apply=dialog.getButtonTypes().stream().filter(t->t.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
                    ((Button)dialog.lookupButton(apply)).fire();
                    if(!dialog.getScene().getWindow().isShowing()||!dialog.lookup("#advanced-config-error").isVisible())throw new AssertionError("Invalid YAML closed the editor");
                    capture(dialog.getScene(),"advanced-inline-error.png");
                    ((Button)dialog.lookupButton(ButtonType.CANCEL)).fire();phase=4;
                });
            }else if(phase==4){
                if(!controller.getConfiguration().equals(beforeAdvanced))throw new AssertionError("Cancel changed active settings");phase=5;
                openAdvanced(scene,dialog->{
                    var editor=(TextArea)dialog.lookup("#advanced-yaml-editor");editor.setText(com.agentmonitor.core.config.MonitoringConfigYaml.write(beforeAdvanced).replace("ratePercent: 10","ratePercent: 37"));
                    var apply=dialog.getButtonTypes().stream().filter(t->t.getButtonData()==ButtonBar.ButtonData.OK_DONE).findFirst().orElseThrow();
                    ((Button)dialog.lookupButton(apply)).fire();phase=6;
                });
            }else if(phase==6&&controller.getConfiguration().sampling().ratePercent()==37&&!start.isDisabled()){
                ((Button)scope.lookup("#scope-inherit-package")).fire();phase=7;
            }else if(phase==7&&!start.isDisabled()&&!scope.lookup("#scope-class-table").isDisabled()){
                if(controller.getConfiguration().sampling().ratePercent()!=37||scope.getExcludedClassConditionList().contains("pkg:dev.perflab.work"))throw new AssertionError("Graphical scope overwrote advanced sampling");
                capture(scene,"monitor-main.png");
                Files.writeString(output.resolve("verified.json"),"{\"pid\":\""+pid+"\",\"parentIncluded\":true,\"childExcluded\":true,\"yamlSynchronized\":true,\"startEnabled\":true,\"advancedInvalidStaysOpen\":true,\"cancelPreservesConfiguration\":true,\"advancedSamplingSurvivesScopeEdit\":true,\"mainHasNoYaml\":true,\"agentAttached\":false}\n");
                System.out.println("VERIFIED real controller: scope, advanced validation/cancel/apply, custom sampling preserved, main YAML removed");poll.stop();controller.onWindowClose();stage.close();Platform.exit();
            }
        }catch(Throwable failure){failure.printStackTrace();poll.stop();if(controller!=null)controller.onWindowClose();Platform.exit();System.exit(1);}}));poll.setCycleCount(Timeline.INDEFINITE);poll.play();
    }
    void openAdvanced(Scene scene,CheckedDialog action){
        Platform.runLater(()->{
            Platform.runLater(()->{try{
                var dialog=javafx.stage.Window.getWindows().stream().filter(w->w.isShowing()&&w.getScene()!=null&&w.getScene().lookup("#advanced-yaml-editor")!=null).map(w->(DialogPane)w.getScene().lookup(".dialog-pane")).findFirst().orElseThrow();
                action.run(dialog);
            }catch(Throwable failure){failure.printStackTrace();controller.onWindowClose();Platform.exit();System.exit(1);}});
            var menu=(MenuButton)scene.lookup("#workspace-config-menu");menu.getItems().stream().filter(i->"workspace-advanced-config".equals(i.getId())).findFirst().orElseThrow().fire();
        });
    }
    interface CheckedDialog{void run(DialogPane dialog)throws Exception;}
    void capture(Scene scene,String file)throws Exception{
        var image=scene.snapshot(null);var pixels=new BufferedImage((int)image.getWidth(),(int)image.getHeight(),BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<pixels.getHeight();y++)for(int x=0;x<pixels.getWidth();x++)pixels.setRGB(x,y,image.getPixelReader().getArgb(x,y));
        ImageIO.write(pixels,"png",output.resolve(file).toFile());
    }
    static TreeItem<String> find(TreeItem<String> item,String path){if(item==null)return null;if(path.equals(item.getValue()))return item;for(var child:item.getChildren()){var match=find(child,path);if(match!=null)return match;}return null;}
    public static void main(String[]args){launch(args);}
}
