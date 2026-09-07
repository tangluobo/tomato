package com.tangluobo.tomato;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import com.tangluobo.tomato.module.connect.service.DdnsService;
import com.tangluobo.tomato.module.tools.DownloaderPane;
import com.tangluobo.tomato.utils.DialogPositionUtil;
import javafx.stage.StageStyle;

import java.io.IOException;

public class TomatoApplication extends Application {
    @Override
    public void start(Stage stage) throws IOException {
        try {
            // 启动 DDNS 全局服务：加载持久化配置，自动定时更新（不依赖连接面板是否打开）
            DdnsService.getInstance().start();

            // 暴露主窗口引用：供弹窗定位工具使用，多屏环境下让 Alert/Dialog 跟随主窗口所在屏幕
            DialogPositionUtil.setMainWindow(stage);

            FXMLLoader fxmlLoader = new FXMLLoader(TomatoApplication.class.getResource("tomato-view.fxml"));
            Scene scene = new Scene(fxmlLoader.load(), 1200, 900);
            scene.setFill(Color.WHITE);
            stage.initStyle(StageStyle.UNDECORATED);
            stage.getIcons().add(new Image(getClass().getResourceAsStream("/images/logo.png")));
            // 无边框窗口必须保留可操作的最小尺寸，避免显示器热插拔后标题栏按钮被挤出可视区域。
            stage.setMinWidth(TomatoController.MIN_WINDOW_WIDTH);
            stage.setMinHeight(TomatoController.MIN_WINDOW_HEIGHT);
            stage.setScene(scene);
            stage.show();
        } catch (Throwable error) {
            if ("1".equals(System.getenv("TOMATO_CONSOLE_DIAGNOSTICS"))) {
                error.printStackTrace();
            }
            throw new RuntimeException("Tomato startup failed", error);
        }
    }

    @Override
    public void stop() {
        // 保存下载列表，并等待正在下载的数据及断点记录刷盘。
        DownloaderPane.prepareForApplicationExit();
        // 停止 DDNS 全局调度
        DdnsService.getInstance().stop();
        // 确保所有非守护线程（如JSch SSH连接线程）被清理，使JVM能正常退出
        Platform.exit();
        System.exit(0);
    }
}
