package com.agentmonitor.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.agentmonitor.app.model.CallNode;
import com.agentmonitor.model.span.SpanAttribute;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableCell;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableRow;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.cell.TreeItemPropertyValueFactory;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

public class CallTreePane extends VBox {

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    /** Keep the output dialog readable when a captured call tree is deeply nested. */
    private static final int DEFAULT_EXPANDED_CALL_DEPTH = 3;

    private final TreeTableView<CallNode> treeTable;
    private final TreeItem<CallNode> rootItem;
    private final Label titleLabel;
    private final TextField searchField;
    private final Button pauseOutputButton;
    private final VBox emptyState;
    private final List<TreeItem<CallNode>> allRoots = new ArrayList<>();
    private volatile long totalRoots = 0;
    private volatile boolean outputPaused = false;
    /** Online and offline data share the renderer, but the latter has no stream to pause. */
    private String titlePrefix = "调用链";

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
        treeTable.setColumnResizePolicy(TreeTableView.UNCONSTRAINED_RESIZE_POLICY);
        VBox.setVgrow(treeTable, Priority.ALWAYS);

        TreeTableColumn<CallNode, String> methodCol = new TreeTableColumn<>("方法");
        methodCol.setCellValueFactory(new TreeItemPropertyValueFactory<>("method"));
        methodCol.setCellFactory(col -> new MethodCell());
        methodCol.setPrefWidth(400);

        TreeTableColumn<CallNode, String> threadCol = new TreeTableColumn<>("线程");
        threadCol.setCellValueFactory(new TreeItemPropertyValueFactory<>("thread"));
        threadCol.setPrefWidth(140);

        TreeTableColumn<CallNode, Long> durCol = new TreeTableColumn<>("耗时");
        durCol.setCellValueFactory(c -> c.getValue().getValue().durationNanosProperty().asObject());
        durCol.setCellFactory(col -> new DurationCell());
        durCol.setPrefWidth(100);
        durCol.setSortType(TreeTableColumn.SortType.DESCENDING);

        TreeTableColumn<CallNode, Long> pctCol = new TreeTableColumn<>("百分比");
        pctCol.setCellValueFactory(c -> c.getValue().getValue().durationNanosProperty().asObject());
        pctCol.setCellFactory(col -> new PercentCell());
        pctCol.setPrefWidth(56);
        pctCol.setResizable(false);
        pctCol.getStyleClass().add("percent-col");

        TreeTableColumn<CallNode, CallNode> detailCol = new TreeTableColumn<>("");
        detailCol.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getValue()));
        detailCol.setCellFactory(col -> new ButtonCell(CallTreePane.this::showDetailDialog));
        detailCol.setPrefWidth(52);
        detailCol.setResizable(false);
        detailCol.setSortable(false);

        treeTable.setRowFactory(tv -> {
            TreeTableRow<CallNode> row = new TreeTableRow<>();
            Region empty = new Region();
            empty.setMinSize(0, 0);
            empty.setPrefSize(0, 0);
            empty.setMaxSize(0, 0);
            empty.setMouseTransparent(true);
            row.setDisclosureNode(empty);
            return row;
        });

        treeTable.getColumns().addAll(methodCol, threadCol, durCol, pctCol, detailCol);
        methodCol.prefWidthProperty().bind(
                treeTable.widthProperty()
                         .subtract(threadCol.widthProperty())
                         .subtract(durCol.widthProperty())
                         .subtract(pctCol.widthProperty())
                         .subtract(detailCol.widthProperty())
                         .subtract(20));
        treeTable.setPlaceholder(new Label(""));

        searchField = new TextField();
        searchField.setPromptText("搜索方法名、线程名...");
        searchField.getStyleClass().add("search-field");
        searchField.setPrefWidth(200);
        searchField.textProperty().addListener((obs, old, now) -> applyFilter(now));

        Button clearBtn = new Button("清空");
        clearBtn.getStyleClass().add("action-btn");
        clearBtn.setOnAction(e -> clear());

        pauseOutputButton = new Button("暂停输出");
        pauseOutputButton.getStyleClass().add("ghost-btn");
        pauseOutputButton.setOnAction(e -> {
            outputPaused = !outputPaused;
            pauseOutputButton.setText(outputPaused ? "恢复输出" : "暂停输出");
            updateTitle();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, titleLabel, spacer, searchField, pauseOutputButton, clearBtn);
        toolbar.setPadding(new Insets(4, 8, 4, 0));
        toolbar.setAlignment(Pos.CENTER_LEFT);

        Label emptyIcon  = new Label("◎");
        emptyIcon.getStyleClass().add("empty-state-icon");
        Label emptyTitle = new Label("暂无调用数据");
        emptyTitle.getStyleClass().add("empty-state-title");
        Label emptyDesc  = new Label("点击「开始监控」后，这里将显示调用链");
        emptyDesc.getStyleClass().add("empty-state-desc");
        emptyState = new VBox(8, emptyIcon, emptyTitle, emptyDesc);
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setMouseTransparent(true);

        StackPane treeArea = new StackPane(treeTable, emptyState);
        StackPane.setMargin(emptyState, new Insets(32, 0, 0, 0));
        VBox.setVgrow(treeArea, Priority.ALWAYS);

        getChildren().addAll(toolbar, treeArea);
    }

    public void addRootNode(CallNode node) {
        if (outputPaused) return;
        Platform.runLater(() -> {
            if (outputPaused) return;
            TreeItem<CallNode> item = buildTreeItem(node, 1);
            allRoots.add(0, item);
            totalRoots++;
            if (allRoots.size() > 500) allRoots.remove(allRoots.size() - 1);
            applyFilter(searchField.getText());
            emptyState.setVisible(false);
        });
    }

    /** A newly attached target must not inherit a manually paused or stale previous session view. */
    public void beginMonitoringSession() {
        outputPaused = false;
        titlePrefix = "调用链";
        pauseOutputButton.setText("暂停输出");
        pauseOutputButton.setDisable(false);
        clear();
    }

    /**
     * Replaces the view with a completed on-disk capture. Unlike the live stream's rolling
     * window, replay retains every supplied root so historical traces are never silently capped.
     * This method must be invoked from the JavaFX application thread.
     */
    public void showOfflineReplay(List<CallNode> roots) {
        outputPaused = false;
        titlePrefix = "离线调用链";
        pauseOutputButton.setText("暂停输出");
        pauseOutputButton.setDisable(true);
        allRoots.clear();
        rootItem.getChildren().clear();
        if (roots != null) {
            for (CallNode root : roots) {
                if (root != null) allRoots.add(0, buildTreeItem(root, 1));
            }
        }
        totalRoots = allRoots.size();
        applyFilter(searchField.getText());
        emptyState.setVisible(allRoots.isEmpty());
    }

    private TreeItem<CallNode> buildTreeItem(CallNode node, int depth) {
        TreeItem<CallNode> item = new TreeItem<>(node);
        // The root call is depth 1. Expanding only depths 1–2 makes the first
        // three call levels visible while keeping deeper branches on demand.
        item.setExpanded(depth < DEFAULT_EXPANDED_CALL_DEPTH);
        for (CallNode child : node.getChildren()) {
            item.getChildren().add(buildTreeItem(child, depth + 1));
        }
        return item;
    }


    private void showDetailDialog(CallNode node) {
        String sig    = parseDescriptor(node.getSignature());
        String raw    = node.getSignature();
        String status = node.isError() ? "\u5f02\u5e38" : "\u6b63\u5e38";

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));

        int r = 0;
        grid.add(bold("\u7c7b\u540d"),  0, r); grid.add(mono(node.getFullClassName()), 1, r++);
        grid.add(bold("\u65b9\u6cd5"),  0, r); grid.add(mono(node.getMethodName2()),   1, r++);
        grid.add(bold("\u7b7e\u540d"),  0, r); grid.add(mono(sig.isEmpty() ? raw : sig), 1, r++);
        grid.add(bold("\u7ebf\u7a0b"),  0, r); grid.add(mono(node.getThread()),          1, r++);
        grid.add(bold("\u8017\u65f6"),  0, r); grid.add(mono(node.getDurationDisplay()), 1, r++);
        grid.add(bold("\u72b6\u6001"),  0, r); grid.add(mono(status),                    1, r++);
        r = addAttributeRow(grid, node, SpanAttribute.ERROR_TYPE, "错误类型", r);
        r = addAttributeRow(grid, node, SpanAttribute.HTTP_RESPONSE_STATUS_CODE, "HTTP 状态", r);
        r = addAttributeRow(grid, node, SpanAttribute.DB_RESPONSE_STATUS_CODE, "数据库状态", r);
        r = addAttributeRow(grid, node, SpanAttribute.DEPENDENCY_TYPE, "依赖类型", r);
        r = addAttributeRow(grid, node, SpanAttribute.DATASOURCE_NAME, "数据源", r);
        r = addAttributeRow(grid, node, SpanAttribute.SQL, "SQL", r);
        r = addAttributeRow(grid, node, SpanAttribute.SQL_PARAMETERS, "SQL 参数", r);
        r = addAttributeRow(grid, node, SpanAttribute.HTTP_PARAMETERS, "HTTP 参数", r);
        if (!node.getAttributes().isEmpty()) {
            addValueRow(grid, "采集属性", formatAttributes(node.getAttributes()), r++);
        }
        if (!node.getArgs().isEmpty()) {
            addValueRow(grid, "\u53c2\u6570\u503c", node.getArgs(), r++);
        }
        String retVal = node.getRetVal();
        if (retVal != null && !retVal.isEmpty()) {
            addValueRow(grid, "\u8fd4\u56de\u503c", retVal, r++);
        } else if (node.isError()) {
            grid.add(bold("\u8fd4\u56de\u503c"), 0, r); grid.add(mono("\u5f02\u5e38\u9000\u51fa"), 1, r++);
        }
        if (node.isError() && node.getStackTrace() != null && !node.getStackTrace().isEmpty()) {
            grid.add(bold("异常堆栈"), 0, r);
            TextArea stackArea = readonlyTextArea(node.getStackTrace(), 780, 260);
            GridPane.setHgrow(stackArea, Priority.ALWAYS);
            grid.add(stackArea, 1, r++);
        }
        if (!raw.isEmpty()) {
            grid.add(bold("JVM \u63cf\u8ff0"), 0, r); grid.add(mono(raw), 1, r);
        }

        GridPane.setHgrow(grid.getChildren().get(1), Priority.ALWAYS);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("\u65b9\u6cd5\u8be6\u60c5");
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(new ScrollPane(grid));
        dialog.getDialogPane().setPrefSize(920, 680);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().getStylesheets().addAll(
                getScene() != null ? getScene().getStylesheets() : List.of());
        dialog.showAndWait();
    }

    private int addAttributeRow(GridPane grid, CallNode node, String attributeName, String label, int row) {
        String value = node.getAttributes().get(attributeName);
        if (value == null || value.isBlank()) return row;
        addValueRow(grid, label, value, row);
        return row + 1;
    }

    private static String formatAttributes(java.util.Map<String, String> attributes) {
        try {
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(attributes);
        } catch (Exception ignored) {
            return String.valueOf(attributes);
        }
    }

    private void addValueRow(GridPane grid, String labelText, String fullText, int row) {
        grid.add(bold(labelText), 0, row);
        String preview = fullText.length() > 80 ? fullText.substring(0, 80) + "…" : fullText;
        Label lbl = mono(preview);
        if (fullText.length() <= 80) {
            grid.add(lbl, 1, row);
            return;
        }
        Button viewBtn = new Button("查看");
        viewBtn.setStyle("-fx-font-size:11px; -fx-padding:1 6;");
        viewBtn.setOnAction(e -> {
            TextArea ta = readonlyTextArea(fullText, 780, 460);
            Button formatBtn = new Button("JSON 格式化");
            formatBtn.getStyleClass().add("ghost-btn");
            formatBtn.setOnAction(event -> {
                try {
                    ta.setText(formatJsonText(ta.getText()));
                    ta.positionCaret(0);
                } catch (Exception ex) {
                    Dialog<Void> error = new Dialog<>();
                    error.setTitle("JSON 格式化失败");
                    error.setHeaderText(null);
                    error.getDialogPane().setContent(new Label("当前内容不是合法 JSON，无法格式化。"));
                    error.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
                    error.getDialogPane().getStylesheets().addAll(
                            getScene() != null ? getScene().getStylesheets() : List.of());
                    error.showAndWait();
                }
            });
            HBox tools = new HBox(8, formatBtn);
            tools.setAlignment(Pos.CENTER_RIGHT);
            VBox content = new VBox(8, tools, ta);
            VBox.setVgrow(ta, Priority.ALWAYS);
            Dialog<Void> d = new Dialog<>();
            d.setTitle(labelText);
            d.setHeaderText(null);
            d.getDialogPane().setContent(content);
            d.getDialogPane().setPrefSize(840, 560);
            d.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            d.getDialogPane().getStylesheets().addAll(
                    getScene() != null ? getScene().getStylesheets() : List.of());
            d.showAndWait();
        });
        HBox box = new HBox(6, lbl, viewBtn);
        box.setAlignment(Pos.CENTER_LEFT);
        grid.add(box, 1, row);
    }

    private static Label bold(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-text-fill: #334155;");
        return l;
    }
    private static Label mono(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-family: 'Menlo', 'JetBrains Mono', monospace; -fx-text-fill: #111827;");
        l.setWrapText(true);
        l.setMaxWidth(720);
        return l;
    }

    private static TextArea readonlyTextArea(String text, double width, double height) {
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setWrapText(false);
        area.setPrefSize(width, height);
        area.setStyle("""
                -fx-font-family: 'Menlo', 'JetBrains Mono', monospace;
                -fx-font-size: 12px;
                -fx-text-fill: #111827;
                -fx-control-inner-background: #F8FAFC;
                -fx-background-color: #F8FAFC;
                -fx-border-color: #CBD5E1;
                -fx-border-radius: 8;
                -fx-background-radius: 8;
                """);
        return area;
    }

    private static String formatJsonText(String rawText) throws Exception {
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

    private static String parseDescriptor(String desc) {
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
        allRoots.clear();
        rootItem.getChildren().clear();
        totalRoots = 0;
        updateTitle();
        emptyState.setVisible(true);
    }

    private void applyFilter(String text) {
        rootItem.getChildren().clear();
        List<TreeItem<CallNode>> filtered = new ArrayList<>();
        for (TreeItem<CallNode> item : allRoots) {
            CallNode n = item.getValue();
            if (text == null || text.isEmpty()
                    || n.getMethod().toLowerCase().contains(text.toLowerCase())
                    || n.getThread().toLowerCase().contains(text.toLowerCase())) {
                filtered.add(item);
            }
        }
        rootItem.getChildren().setAll(filtered);
        // Rebuilding the visible roots replaces the TreeItem list. JavaFX keeps the
        // selected columns, but does not automatically reapply their comparator to
        // those replacement items. Preserve the user's active ordering as new calls
        // arrive; with no selected sort column, retain the original arrival order.
        if (!treeTable.getSortOrder().isEmpty()) {
            treeTable.sort();
        }
        updateTitle(filtered.size());
    }

    private void updateTitle() {
        updateTitle(rootItem.getChildren().size());
    }

    private void updateTitle(int visibleCount) {
        String suffix = outputPaused ? " · 已暂停" : "";
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
            nameLbl.setStyle("-fx-text-fill: #93C5FD; -fx-font-size: 13px;");
            tip.setStyle("-fx-font-size: 12px;");
            Tooltip.install(nameLbl, tip);
            content = new HBox(0, indentRegion, toggleLbl, nameLbl);
            content.setAlignment(Pos.CENTER_LEFT);

            toggleLbl.setOnMouseClicked(e -> {
                TreeItem<CallNode> ti = getTreeTableRow().getTreeItem();
                if (ti != null && !ti.getChildren().isEmpty()) {
                    ti.setExpanded(!ti.isExpanded());
                    toggleLbl.setText(ti.isExpanded() ? "\u25BC" : "\u25B6");
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
                    toggleLbl.setText(ti.isExpanded() ? "\u25BC" : "\u25B6");
                } else {
                    toggleLbl.setText("");
                }
                nameLbl.setText(item);
                tip.setText(item);
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
                    setText(node.getDurationDisplay() + "  \u25CF");
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
            setText(String.format("%.0f%%", Math.min(pct, 100.0)));
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

    private static class ButtonCell extends TreeTableCell<CallNode, CallNode> {
        private final Button btn = new Button("详情");

        ButtonCell(Consumer<CallNode> action) {
            btn.getStyleClass().add("detail-btn");
            btn.setOnAction(e -> {
                if (getItem() != null) action.accept(getItem());
            });
        }

        @Override
        protected void updateItem(CallNode item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(empty || item == null ? null : btn);
        }
    }
}
