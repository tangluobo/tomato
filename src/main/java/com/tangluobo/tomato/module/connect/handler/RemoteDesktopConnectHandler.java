package com.tangluobo.tomato.module.connect.handler;

import com.tangluobo.tomato.module.connect.ConnectModule;
import com.tangluobo.tomato.module.connect.ConnectType;
import com.tangluobo.tomato.module.connect.ConnectionConfig;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.awt.Desktop;
import java.net.URI;

/** Opens VNC and SPICE connections with an installed native viewer. */
public class RemoteDesktopConnectHandler implements ConnectHandler {
    private static final String VIRT_VIEWER_DOWNLOAD = "https://virt-manager.org/download";

    @Override
    public boolean supports(ConnectType type) {
        return type == ConnectType.VNC || type == ConnectType.SPICE;
    }

    @Override
    public void handleConnect(ConnectModule module, ConnectionConfig config) {
        try {
            RemoteDesktopClientLauncher.launch(config);
        } catch (RemoteDesktopClientLauncher.ClientNotFoundException e) {
            showMissingClient(module, e.getMessage());
        } catch (Exception e) {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            if (module.getStage() != null) alert.initOwner(module.getStage());
            alert.setTitle("远程桌面连接失败");
            alert.setHeaderText("无法启动 " + config.getType().getDisplayName());
            alert.setContentText(e.getMessage() != null ? e.getMessage() : e.toString());
            alert.showAndWait();
        }
    }

    private void showMissingClient(ConnectModule module, String message) {
        ButtonType download = new ButtonType("打开下载页");
        Alert alert = new Alert(Alert.AlertType.WARNING, message, download, ButtonType.CANCEL);
        if (module.getStage() != null) alert.initOwner(module.getStage());
        alert.setTitle("缺少远程桌面客户端");
        alert.setHeaderText("请安装 virt-viewer 或配置客户端路径");
        alert.showAndWait().filter(download::equals).ifPresent(button -> openDownloadPage());
    }

    private void openDownloadPage() {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(VIRT_VIEWER_DOWNLOAD));
            }
        } catch (Exception ignored) {
        }
    }
}
