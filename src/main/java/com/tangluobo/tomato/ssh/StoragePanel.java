package com.tangluobo.tomato.ssh;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Linux storage inventory and guarded LVM/mount operations for an SSH host. */
public class StoragePanel extends BorderPane {
    @FunctionalInterface public interface CommandExecutor { String execute(String command) throws Exception; }

    private static final Pattern LSBLK_FIELD = Pattern.compile("([A-Z]+)=\"((?:\\\\.|[^\"])*)\"");
    private static final Pattern DEVICE = Pattern.compile("/dev/[A-Za-z0-9._/+:-]+");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._+.-]+");
    private static final Pattern MOUNT = Pattern.compile("/[A-Za-z0-9._/+ -]*");
    private final CommandExecutor executor;
    private final TableView<StorageItem> table = new TableView<>();
    private final javafx.collections.ObservableList<StorageItem> rows = FXCollections.observableArrayList();
    private final TreeView<LvmNode> lvmTree = new TreeView<>();
    private final Label status = new Label("就绪");

    public StoragePanel(CommandExecutor executor) {
        this.executor = executor;
        setPadding(new Insets(8));
        table.setItems(rows);
        addColumn("设备", "path", 170);
        addColumn("类型", "type", 75);
        addColumn("容量", "size", 100);
        addColumn("分区表", "pttype", 80);
        addColumn("文件系统", "fstype", 100);
        addColumn("挂载点", "mountpoint", 190);
        addColumn("状态", "state", 130);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        Button refresh = new Button("刷新");
        Button initPv = new Button("新硬盘初始化为 PV");
        Button vg = new Button("创建/扩展 VG");
        Button extend = new Button("扩展 LV");
        Button mount = new Button("格式化/挂载");
        refresh.setOnAction(e -> refresh());
        initPv.setOnAction(e -> initializePv());
        vg.setOnAction(e -> createOrExtendVg());
        extend.setOnAction(e -> extendLv());
        mount.setOnAction(e -> formatOrMount());
        HBox toolbar = new HBox(8, refresh, initPv, vg, extend, mount);
        toolbar.setPadding(new Insets(0, 0, 8, 0));
        setTop(toolbar);

        lvmTree.setShowRoot(false);
        lvmTree.setPrefHeight(220);
        lvmTree.setCellFactory(v -> new TreeCell<>() {
            @Override protected void updateItem(LvmNode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.label());
            }
        });
        lvmTree.setOnMouseClicked(e -> {
            if (e.getClickCount() != 2) return;
            LvmNode selected = selectedLvm();
            if (selected == null) return;
            if ("PV".equals(selected.kind())) createOrExtendVg();
            else if ("LV".equals(selected.kind())) extendLv();
        });
        lvmTree.setContextMenu(createLvmContextMenu());
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() != 2 || table.getSelectionModel().getSelectedItem() == null) return;
            StorageItem selected = table.getSelectionModel().getSelectedItem();
            if ("未分区".equals(selected.value("state"))) initializePv(); else formatOrMount();
        });
        Label hint = new Label("提示：双击未分区磁盘可初始化；双击 PV 可加入 VG；双击 LV 可扩容");
        hint.setStyle("-fx-text-fill: #666; -fx-font-size: 11px;");
        VBox center = new VBox(8, table, new Label("LVM 关系（VG → PV / LV）"), lvmTree, hint);
        VBox.setVgrow(table, Priority.ALWAYS);
        setCenter(center);
        status.setPadding(new Insets(6, 2, 0, 2));
        setBottom(status);
    }

    private ContextMenu createLvmContextMenu() {
        MenuItem vg = new MenuItem("创建/扩展 VG");
        vg.setOnAction(e -> createOrExtendVg());
        MenuItem lv = new MenuItem("扩展 LV");
        lv.setOnAction(e -> extendLv());
        MenuItem mount = new MenuItem("格式化/挂载");
        mount.setOnAction(e -> formatOrMount());
        ContextMenu menu = new ContextMenu(vg, lv, mount);
        menu.setOnShowing(e -> {
            LvmNode selected = selectedLvm();
            vg.setDisable(selected == null || !"PV".equals(selected.kind()));
            lv.setDisable(selected == null || !"LV".equals(selected.kind()));
            mount.setDisable(selected == null || !("LV".equals(selected.kind()) || "PV".equals(selected.kind())));
        });
        return menu;
    }

    private void addColumn(String title, String property, double width) {
        TableColumn<StorageItem, String> column = new TableColumn<>(title);
        column.setCellValueFactory(v -> new SimpleStringProperty(v.getValue().value(property)));
        column.setPrefWidth(width);
        table.getColumns().add(column);
    }

    public void refresh() {
        status.setText("正在读取存储信息...");
        new Thread(() -> {
            try {
                String output = executor.execute(
                        "LC_ALL=C lsblk -P -b -o PATH,TYPE,SIZE,PTTYPE,FSTYPE,MOUNTPOINT,PKNAME 2>&1; " +
                        "echo __TOMATO_PVS__; sudo -n pvs --noheadings --separator '|' --units b --nosuffix -o pv_name,vg_name,pv_size,pv_free 2>&1; " +
                        "echo __TOMATO_VGS__; sudo -n vgs --noheadings --separator '|' --units b --nosuffix -o vg_name,vg_size,vg_free 2>&1; " +
                        "echo __TOMATO_LVS__; sudo -n lvs --noheadings --separator '|' --units b --nosuffix -o lv_path,vg_name,lv_name,lv_size 2>&1");
                int marker = output.indexOf("__TOMATO_PVS__");
                String block = marker >= 0 ? output.substring(0, marker) : output;
                List<StorageItem> parsed = parseLsblk(block);
                TreeItem<LvmNode> lvmRoot = parseLvmTree(output);
                Platform.runLater(() -> {
                    rows.setAll(parsed);
                    lvmTree.setRoot(lvmRoot);
                    status.setText("已读取 " + parsed.size() + " 个块设备；写操作要求远程 sudo -n 权限");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> showError("读取存储信息失败", ex.getMessage()));
            }
        }, "Storage-Refresh").start();
    }

    private TreeItem<LvmNode> parseLvmTree(String output) {
        TreeItem<LvmNode> root = new TreeItem<>(new LvmNode("ROOT", "LVM", "", "", ""));
        Map<String, TreeItem<LvmNode>> groups = new LinkedHashMap<>();
        String vgText = section(output, "__TOMATO_VGS__", "__TOMATO_LVS__");
        for (String line : vgText.split("\\R")) {
            String[] p = splitFields(line, 3); if (p == null) continue;
            LvmNode node = new LvmNode("VG", p[0], "", p[0], "容量 " + humanSize(p[1]) + "，空闲 " + humanSize(p[2]));
            TreeItem<LvmNode> item = new TreeItem<>(node); item.setExpanded(true); groups.put(p[0], item); root.getChildren().add(item);
        }
        String pvText = section(output, "__TOMATO_PVS__", "__TOMATO_VGS__");
        TreeItem<LvmNode> freePvs = new TreeItem<>(new LvmNode("GROUP", "未加入 VG 的 PV", "", "", ""));
        for (String line : pvText.split("\\R")) {
            String[] p = splitFields(line, 4); if (p == null || !p[0].startsWith("/dev/")) continue;
            LvmNode node = new LvmNode("PV", p[0], p[0], p[1], "容量 " + humanSize(p[2]) + "，空闲 " + humanSize(p[3]));
            TreeItem<LvmNode> parent = groups.getOrDefault(p[1], freePvs); parent.getChildren().add(new TreeItem<>(node));
        }
        if (!freePvs.getChildren().isEmpty()) { freePvs.setExpanded(true); root.getChildren().add(freePvs); }
        String lvText = section(output, "__TOMATO_LVS__", null);
        for (String line : lvText.split("\\R")) {
            String[] p = splitFields(line, 4); if (p == null || !p[0].startsWith("/dev/")) continue;
            TreeItem<LvmNode> parent = groups.get(p[1]);
            if (parent != null) parent.getChildren().add(new TreeItem<>(new LvmNode("LV", p[2], p[0], p[1], "容量 " + humanSize(p[3]))));
        }
        return root;
    }

    private String section(String text, String start, String end) {
        int from = text.indexOf(start); if (from < 0) return ""; from += start.length();
        int to = end == null ? text.length() : text.indexOf(end, from);
        return text.substring(from, to < 0 ? text.length() : to);
    }

    private String[] splitFields(String line, int count) {
        String[] raw = line.split("\\|", -1); if (raw.length < count) return null;
        String[] result = new String[count]; for (int i = 0; i < count; i++) result[i] = raw[i].trim();
        return result;
    }

    private List<StorageItem> parseLsblk(String text) {
        List<StorageItem> result = new ArrayList<>();
        for (String line : text.split("\\R")) {
            Matcher matcher = LSBLK_FIELD.matcher(line);
            Map<String, String> fields = new LinkedHashMap<>();
            while (matcher.find()) fields.put(matcher.group(1), matcher.group(2).replace("\\\"", "\"").replace("\\\\", "\\"));
            if (!fields.containsKey("PATH")) continue;
            String type = fields.getOrDefault("TYPE", "");
            String fs = fields.getOrDefault("FSTYPE", "");
            String mount = fields.getOrDefault("MOUNTPOINT", "");
            String pttype = fields.getOrDefault("PTTYPE", "");
            String state = !mount.isBlank() ? "已挂载" : !fs.isBlank() ? "已格式化" :
                    ("disk".equals(type) && pttype.isBlank() ? "未分区" : "未格式化");
            fields.put("STATE", state);
            fields.put("SIZE", humanSize(fields.getOrDefault("SIZE", "0")));
            result.add(new StorageItem(fields));
        }
        return result;
    }

    private String humanSize(String raw) {
        try {
            double value = Double.parseDouble(raw);
            String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
            int unit = 0;
            while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit++; }
            return String.format(java.util.Locale.ROOT, unit == 0 ? "%.0f %s" : "%.2f %s", value, units[unit]);
        } catch (Exception ignored) { return raw; }
    }

    private void initializePv() {
        String disk = ask("新硬盘初始化为 PV", "整盘设备（例如 /dev/sdb）", selectedPath());
        if (disk == null || !DEVICE.matcher(disk).matches()) return;
        String q = shellQuote(disk);
        String command = "sudo -n sh -c 'set -eu; d=" + q + "; " +
                "test -b \"$d\"; test -z \"$(lsblk -nr -o MOUNTPOINT \"$d\" | grep -v \"^$\" || true)\"; " +
                "wipefs -a \"$d\"; parted -s \"$d\" mklabel gpt mkpart primary 1MiB 100%; partprobe \"$d\"; sleep 2; " +
                "case \"$d\" in *[0-9]) p=\"${d}p1\";; *) p=\"${d}1\";; esac; pvcreate -ff -y \"$p\"'";
        runDangerous("初始化磁盘会清除其全部数据", command);
    }

    private void createOrExtendVg() {
        LvmNode selected = selectedLvm();
        String initialPv = selected != null && "PV".equals(selected.kind()) ? selected.path() : "/dev/sdb1";
        String initialVg = selected != null && !selected.vg().isBlank() ? selected.vg() : "vg_data";
        List<String> values = askTwo("创建/扩展 VG", "PV 设备", initialPv, "VG 名称", initialVg);
        if (values == null || !DEVICE.matcher(values.get(0)).matches() || !NAME.matcher(values.get(1)).matches()) return;
        String pv = shellQuote(values.get(0));
        String vg = shellQuote(values.get(1));
        String command = "sudo -n sh -c 'set -eu; pv=" + pv + "; vg=" + vg + "; test -b \"$pv\"; " +
                "pvs \"$pv\" >/dev/null 2>&1 || pvcreate \"$pv\"; " +
                "if vgs \"$vg\" >/dev/null 2>&1; then vgextend \"$vg\" \"$pv\"; else vgcreate \"$vg\" \"$pv\"; fi'";
        runDangerous("将 PV 加入卷组", command);
    }

    private void extendLv() {
        LvmNode selected = selectedLvm();
        String initialLv = selected != null && "LV".equals(selected.kind()) ? selected.path() : "/dev/vg_data/lv_data";
        List<String> values = askTwo("扩展 LV", "LV 路径", initialLv, "增加容量", "+10G 或 +100%FREE");
        if (values == null || !DEVICE.matcher(values.get(0)).matches()) return;
        String amount = values.get(1).trim().toUpperCase(java.util.Locale.ROOT);
        if (!amount.matches("\\+[0-9]+[KMGT]B?") && !amount.matches("\\+[0-9]+%FREE")) {
            showError("参数错误", "容量格式应为 +10G 或 +100%FREE"); return;
        }
        String option = amount.endsWith("%FREE") ? "-l" : "-L";
        String command = "sudo -n lvextend -r " + option + " " + shellQuote(amount) + " " + shellQuote(values.get(0));
        runDangerous("扩展逻辑卷及其文件系统", command);
    }

    private void formatOrMount() {
        Dialog<List<String>> dialog = new Dialog<>();
        dialog.setTitle("格式化/挂载");
        TextField device = new TextField(selectedPath());
        TextField mount = new TextField("/data");
        ComboBox<String> fs = new ComboBox<>(FXCollections.observableArrayList("不格式化", "ext4", "xfs"));
        fs.getSelectionModel().selectFirst();
        CheckBox fstab = new CheckBox("写入 /etc/fstab"); fstab.setSelected(true);
        GridPane grid = grid();
        grid.addRow(0, new Label("设备"), device); grid.addRow(1, new Label("挂载点"), mount);
        grid.addRow(2, new Label("文件系统"), fs); grid.add(fstab, 1, 3);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == ButtonType.OK ? List.of(device.getText().trim(), mount.getText().trim(), fs.getValue(), Boolean.toString(fstab.isSelected())) : null);
        dialog.showAndWait().ifPresent(v -> {
            if (!DEVICE.matcher(v.get(0)).matches() || !MOUNT.matcher(v.get(1)).matches()) return;
            String dev = shellQuote(v.get(0)), dir = shellQuote(v.get(1));
            String mkfs = "不格式化".equals(v.get(2)) ? "" : "mkfs." + v.get(2) + " -f \"$dev\"; ";
            String persist = Boolean.parseBoolean(v.get(3))
                    ? "uuid=$(blkid -s UUID -o value \"$dev\"); grep -q \"UUID=$uuid \" /etc/fstab || echo \"UUID=$uuid $dir auto defaults,nofail 0 2\" >> /etc/fstab; " : "";
            String command = "sudo -n sh -c 'set -eu; dev=" + dev + "; dir=" + dir + "; " + mkfs +
                    "mkdir -p \"$dir\"; mount \"$dev\" \"$dir\"; " + persist + "'";
            runDangerous("不格式化时会保留数据；选择 ext4/xfs 会清除设备数据", command);
        });
    }

    private void runDangerous(String warning, String command) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("确认存储操作");
        confirm.setHeaderText(warning);
        TextArea preview = new TextArea(command); preview.setEditable(false); preview.setWrapText(true); preview.setPrefRowCount(5);
        confirm.getDialogPane().setContent(new VBox(6, new Label("将在远程主机执行："), preview));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        status.setText("正在执行存储操作...");
        new Thread(() -> {
            try {
                String result = executor.execute(command + "; code=$?; printf '\\n__TOMATO_EXIT__%s' \"$code\"");
                int marker = result.lastIndexOf("__TOMATO_EXIT__");
                String detail = marker >= 0 ? result.substring(0, marker).trim() : result.trim();
                String code = marker >= 0 ? result.substring(marker + "__TOMATO_EXIT__".length()).trim() : "1";
                Platform.runLater(() -> {
                    if (!"0".equals(code)) {
                        showError("存储操作失败", detail.isBlank() ? "远程命令退出码: " + code : detail);
                    } else {
                        status.setText(detail.isBlank() ? "操作完成" : detail);
                        refresh();
                    }
                });
            } catch (Exception ex) { Platform.runLater(() -> showError("存储操作失败", ex.getMessage())); }
        }, "Storage-Action").start();
    }

    private String selectedPath() {
        StorageItem item = table.getSelectionModel().getSelectedItem();
        if (item != null) return item.value("path");
        LvmNode lvm = selectedLvm();
        return lvm == null || lvm.path().isBlank() ? "/dev/" : lvm.path();
    }

    private LvmNode selectedLvm() {
        TreeItem<LvmNode> item = lvmTree.getSelectionModel().getSelectedItem();
        return item == null ? null : item.getValue();
    }

    private String ask(String title, String label, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.setTitle(title); dialog.setHeaderText(label);
        return dialog.showAndWait().map(String::trim).orElse(null);
    }

    private List<String> askTwo(String title, String label1, String value1, String label2, String value2) {
        Dialog<List<String>> dialog = new Dialog<>(); dialog.setTitle(title);
        TextField first = new TextField(value1), second = new TextField(value2);
        GridPane grid = grid(); grid.addRow(0, new Label(label1), first); grid.addRow(1, new Label(label2), second);
        dialog.getDialogPane().setContent(grid); dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(b -> b == ButtonType.OK ? List.of(first.getText().trim(), second.getText().trim()) : null);
        return dialog.showAndWait().orElse(null);
    }

    private GridPane grid() { GridPane grid = new GridPane(); grid.setHgap(8); grid.setVgap(8); grid.setPadding(new Insets(10)); return grid; }
    private String shellQuote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
    private void showError(String title, String message) { status.setText(title); new Alert(Alert.AlertType.ERROR, message == null ? title : message, ButtonType.OK).showAndWait(); }

    private record StorageItem(Map<String, String> fields) {
        String value(String property) { return fields.getOrDefault(property.toUpperCase(java.util.Locale.ROOT), ""); }
    }
    private record LvmNode(String kind, String name, String path, String vg, String detail) {
        String label() { return kind + "  " + name + (detail.isBlank() ? "" : "  （" + detail + "）"); }
    }
}
