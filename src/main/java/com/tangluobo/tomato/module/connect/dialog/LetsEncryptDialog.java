package com.tangluobo.tomato.module.connect.dialog;

import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import com.tangluobo.tomato.module.connect.service.OssService;
import com.tangluobo.tomato.module.connect.service.S3Service;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** Let's Encrypt 申请参数对话框。 */
public final class LetsEncryptDialog {
    private LetsEncryptDialog() {}

    public record Result(String email, boolean wildcard, ConnectionConfig storage,
                         String bucket, String prefix, List<String> serverTypes,
                         boolean zipPackage, String keystorePassword, String certificateAuthority,
                         String eabKid, String eabHmac) {}

    public static Result show(String domain, List<ConnectionConfig> connections) {
        return show(null, domain, connections, !domain.startsWith("*."));
    }

    public static Result show(String domain, List<ConnectionConfig> connections, boolean allowWildcard) {
        return show(null, domain, connections, allowWildcard);
    }

    public static Result show(Node owner, String domain, List<ConnectionConfig> connections,
                              boolean allowWildcard) {
        return show(owner, domain, connections, allowWildcard, "");
    }

    public static Result show(Node owner, String domain, List<ConnectionConfig> connections,
                              boolean allowWildcard, String defaultEmail) {
        List<ConnectionConfig> stores = connections.stream()
                .filter(c -> c.getType() == ConnectType.S3 || c.getType() == ConnectType.ALIYUN_OSS)
                .toList();
        if (stores.isEmpty()) {
            Alert warning = new Alert(Alert.AlertType.WARNING,
                    "请先创建一个 S3 或阿里云 OSS 连接。", ButtonType.OK);
            DialogPositionUtil.centerOnOwner(warning, owner);
            warning.showAndWait();
            return null;
        }

        Dialog<Result> dialog = new Dialog<>();
        dialog.setTitle("申请 TLS 证书");
        dialog.setHeaderText("为 " + domain + " 申请证书（阿里云 DNS-01 自动验证）");
        DialogPositionUtil.centerOnOwner(dialog, owner);
        ButtonType apply = new ButtonType("申请", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(apply, ButtonType.CANCEL);

        TextField email = new TextField();
        email.setPromptText("证书到期通知邮箱");
        email.setText(defaultEmail == null ? "" : defaultEmail.trim());
        ComboBox<String> certificateAuthority = new ComboBox<>();
        certificateAuthority.getItems().addAll(
                "Let's Encrypt（90 天）", "ZeroSSL（90 天，需 EAB）",
                "Google Trust Services（90 天，需 EAB）", "SSL.com（最长 200 天，需 EAB）");
        certificateAuthority.getSelectionModel().selectFirst();
        TextField eabKid = new TextField();
        eabKid.setPromptText("外部账户绑定 KID");
        PasswordField eabHmac = new PasswordField();
        eabHmac.setPromptText("外部账户绑定 HMAC Key");
        CheckBox wildcard = new CheckBox("同时包含 *." + domain);
        wildcard.setVisible(allowWildcard);
        wildcard.setManaged(allowWildcard);
        ComboBox<ConnectionConfig> storage = new ComboBox<>();
        storage.getItems().addAll(stores);
        storage.setMaxWidth(Double.MAX_VALUE);
        storage.setCellFactory(v -> storageCell());
        storage.setButtonCell(storageCell());
        ComboBox<String> bucket = new ComboBox<>();
        bucket.setEditable(true);
        bucket.setMaxWidth(Double.MAX_VALUE);
        TextField prefix = new TextField("certificates/" + domain + "/");
        CheckBox nginx = selectedCheckBox("Nginx");
        CheckBox apache = selectedCheckBox("Apache");
        CheckBox tomcat = selectedCheckBox("Tomcat (PFX)");
        CheckBox iis = selectedCheckBox("IIS (PFX)");
        CheckBox jks = selectedCheckBox("Java JKS");
        CheckBox pem = selectedCheckBox("通用 PEM");
        VBox serverTypes = new VBox(5,
                new HBox(12, nginx, apache, tomcat),
                new HBox(12, iis, jks, pem));
        ToggleGroup outputGroup = new ToggleGroup();
        RadioButton fileOutput = new RadioButton("文件（ZIP 压缩包）");
        RadioButton directoryOutput = new RadioButton("目录");
        fileOutput.setToggleGroup(outputGroup);
        directoryOutput.setToggleGroup(outputGroup);
        fileOutput.setSelected(true);
        HBox outputMode = new HBox(16, fileOutput, directoryOutput);
        PasswordField keystorePassword = new PasswordField();
        keystorePassword.setPromptText("PFX/JKS 密码（其他格式无需填写）");
        CheckBox tos = new CheckBox("我同意所选证书颁发机构的服务条款");
        tos.setSelected(true);
        Label status = new Label();
        status.setStyle("-fx-text-fill: #777;");
        Runnable updateEabState = () -> {
            boolean required = certificateAuthority.getSelectionModel().getSelectedIndex() > 0;
            eabKid.setDisable(!required);
            eabHmac.setDisable(!required);
        };
        certificateAuthority.valueProperty().addListener((obs, old, value) -> updateEabState.run());
        updateEabState.run();

        storage.valueProperty().addListener((obs, old, selected) -> {
            bucket.getItems().clear();
            if (selected == null) return;
            status.setText("正在读取 Bucket…");
            new Thread(() -> {
                try {
                    List<String> names = selected.getType() == ConnectType.ALIYUN_OSS
                            ? OssService.listBuckets(selected) : S3Service.listBuckets(selected);
                    Platform.runLater(() -> {
                        bucket.getItems().setAll(names);
                        if (!names.isEmpty()) bucket.getSelectionModel().selectFirst();
                        status.setText("");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> status.setText("读取 Bucket 失败，可手动输入：" + ex.getMessage()));
                }
            }, "Load-Certificate-Buckets").start();
        });

        GridPane grid = new GridPane();
        grid.setHgap(10); grid.setVgap(10); grid.setPadding(new Insets(10));
        grid.addRow(0, new Label("证书颁发机构："), certificateAuthority);
        grid.addRow(1, new Label("邮箱："), email);
        grid.addRow(2, new Label("EAB KID："), eabKid);
        grid.addRow(3, new Label("EAB HMAC："), eabHmac);
        grid.addRow(4, new Label("域名："), wildcard);
        grid.addRow(5, new Label("S3/OSS："), storage);
        grid.addRow(6, new Label("Bucket："), bucket);
        grid.addRow(7, new Label("保存目录："), prefix);
        grid.addRow(8, new Label("服务器类型："), serverTypes);
        grid.addRow(9, new Label("输出方式："), outputMode);
        grid.addRow(10, new Label("证书密码："), keystorePassword);
        grid.add(tos, 1, 11);
        grid.add(status, 1, 12);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(560);

        Button applyButton = (Button) dialog.getDialogPane().lookupButton(apply);
        BooleanBinding noServerType = nginx.selectedProperty().not()
                .and(apache.selectedProperty().not())
                .and(tomcat.selectedProperty().not())
                .and(iis.selectedProperty().not())
                .and(jks.selectedProperty().not())
                .and(pem.selectedProperty().not());
        BooleanBinding eabMissing = Bindings.createBooleanBinding(
                () -> certificateAuthority.getSelectionModel().getSelectedIndex() > 0
                        && (eabKid.getText().trim().isEmpty() || eabHmac.getText().trim().isEmpty()),
                certificateAuthority.valueProperty(), eabKid.textProperty(), eabHmac.textProperty());
        applyButton.disableProperty().bind(email.textProperty().isEmpty()
                .or(storage.valueProperty().isNull())
                .or(bucket.getEditor().textProperty().isEmpty())
                .or(noServerType)
                .or(eabMissing)
                .or(tos.selectedProperty().not()));
        storage.getSelectionModel().selectFirst();

        dialog.setResultConverter(button -> button == apply
                ? new Result(email.getText().trim(), wildcard.isSelected(), storage.getValue(),
                    bucket.getEditor().getText().trim(), normalizePrefix(prefix.getText()),
                    selectedServerTypes(nginx, apache, tomcat, iis, jks, pem),
                    fileOutput.isSelected(),
                    keystorePassword.getText(), certificateAuthority.getValue(),
                    eabKid.getText().trim(), eabHmac.getText().trim())
                : null);
        return dialog.showAndWait().orElse(null);
    }

    private static CheckBox selectedCheckBox(String text) {
        CheckBox checkBox = new CheckBox(text);
        checkBox.setSelected(true);
        return checkBox;
    }

    private static List<String> selectedServerTypes(CheckBox... checkBoxes) {
        List<String> selected = new ArrayList<>();
        for (CheckBox checkBox : checkBoxes) {
            if (checkBox.isSelected()) selected.add(checkBox.getText());
        }
        return List.copyOf(selected);
    }

    private static ListCell<ConnectionConfig> storageCell() {
        return new ListCell<>() {
            @Override protected void updateItem(ConnectionConfig item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getType().getDisplayName() + ")");
            }
        };
    }

    private static String normalizePrefix(String value) {
        String p = value == null ? "" : value.trim().replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        return p.isEmpty() || p.endsWith("/") ? p : p + "/";
    }
}
