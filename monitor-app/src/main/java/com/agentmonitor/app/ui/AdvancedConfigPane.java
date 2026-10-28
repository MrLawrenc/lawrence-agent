package com.agentmonitor.app.ui;

import com.agentmonitor.core.config.MonitoringConfigYaml;
import com.agentmonitor.model.config.MonitoringConfig;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import java.util.Optional;
import java.util.function.Supplier;

/** Edits a complete draft derived from the current model; invalid drafts never replace it. */
public final class AdvancedConfigPane extends VBox {
    private final Supplier<MonitoringConfig> current;
    private final TextArea editor=new TextArea();
    private final Label error=new Label();
    private final Label draftState=new Label("与当前配置一致");
    private final SimpleBooleanProperty dirty=new SimpleBooleanProperty(false);
    private MonitoringConfig base;
    private String baseText;
    public AdvancedConfigPane(Supplier<MonitoringConfig> current,boolean monitoring) {
        this.current=current;setSpacing(12);getStyleClass().add("advanced-config-pane");
        Label title=new Label("在当前配置上自定义 YAML");title.getStyleClass().add("card-title");
        Label hint=new Label(monitoring?"当前采集继续使用启动时配置。应用后同步范围选择，下一次采集生效。":"已载入当前范围和全部采集设置。校验通过后应用，包选择会一起同步。");
        hint.setWrapText(true);hint.getStyleClass().add("hint-label");
        editor.setId("advanced-yaml-editor");editor.getStyleClass().add("yaml-preview");editor.setWrapText(false);editor.setPrefColumnCount(80);editor.setMinHeight(260);
        base=current.get();baseText=MonitoringConfigYaml.write(base);editor.setText(baseText);VBox.setVgrow(editor,Priority.ALWAYS);
        editor.textProperty().addListener((ignored,oldText,newText)->{
            dirty.set(!newText.equals(baseText));
            showDraftState(dirty.get()?"有未应用修改":"与当前配置一致");
            showError("");
        });
        sceneProperty().addListener((o,before,scene)->{
            if(scene!=null && editor.getText().equals(baseText)) reloadCurrent();
        });
        error.setId("advanced-config-error");error.setWrapText(true);error.getStyleClass().add("inline-validation-error");showError("");
        Button reset=new Button("恢复当前配置");reset.setId("advanced-config-reset");reset.setTooltip(new Tooltip("放弃此处草稿，重新载入主界面当前配置；不会恢复出厂设置。"));
        reset.setOnAction(e->reloadCurrent());
        Button copy=new Button("复制 YAML");UiIcons.decorate(copy,"copy");copy.setOnAction(e->{var content=new ClipboardContent();content.putString(editor.getText());Clipboard.getSystemClipboard().setContent(content);});
        draftState.setId("advanced-config-state");draftState.getStyleClass().add("draft-state");
        Region space=new Region();HBox.setHgrow(space,Priority.ALWAYS);HBox tools=new HBox(12,reset,draftState,space,copy);tools.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(title,hint,tools,editor,error);
    }
    public Optional<MonitoringConfig> validateConfiguration() {
        try {
            if(!base.equals(current.get()))throw new IllegalArgumentException("采集范围已在其他页面修改。请先复制需要保留的草稿，再点“恢复当前配置”载入最新设置。");
            if(editor.getText().length()>1_048_576||editor.getText().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>1_048_576)throw new IllegalArgumentException("配置超过 1MiB，请缩小内容。");
            var parsed=MonitoringConfigYaml.parse(editor.getText());showError("");return Optional.of(parsed);
        }catch(Exception failure){
            String message=String.valueOf(failure.getMessage());var position=java.util.regex.Pattern.compile("line[: ]+(\\d+).*?column[: ]+(\\d+)",java.util.regex.Pattern.CASE_INSENSITIVE|java.util.regex.Pattern.DOTALL).matcher(message);
            showError(position.find()?"无法应用：第 "+position.group(1)+" 行、第 "+position.group(2)+" 列附近格式有误，请检查缩进和括号。":"无法应用："+message.lines().findFirst().orElse("配置无效"));
            error.setTooltip(new Tooltip(message));return Optional.empty();
        }
    }
    public ReadOnlyBooleanProperty dirtyProperty(){return dirty;}
    private void reloadCurrent(){base=current.get();baseText=MonitoringConfigYaml.write(base);editor.setText(baseText);dirty.set(false);showDraftState("与当前配置一致");showError("");}
    public void markApplied(MonitoringConfig configuration){base=configuration;baseText=editor.getText();dirty.set(false);showDraftState("已应用到当前配置");}
    private void showDraftState(String value){draftState.setText(value);draftState.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("dirty"),dirty.get());}
    private void showError(String value){error.setText(value);error.setVisible(!value.isBlank());error.setManaged(!value.isBlank());}
}
