package com.tangluobo.tomato;

import javafx.application.Application;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;

public class Launcher {
    public static void main(String[] args) {
        // 关闭 HiDPI 自动缩放：让 1 逻辑像素 = 1 物理像素，避免 Windows 显示缩放（如 150%）把界面整体放大
        System.setProperty("prism.allowhidpi", "false");
        // 启用LCD文字渲染，消除模糊
        System.setProperty("prism.lcdtext", "true");
        System.setProperty("prism.text", "lcd");
        // javaw / jpackage GUI 启动器无控制台，System.out/err 为无效句柄，
        // JavaFX 内部（java.util.logging）写入会抛异常导致启动失败，重定向到空输出流规避。
        // 原生镜像启动异常可通过 TOMATO_CONSOLE_DIAGNOSTICS=1 临时保留控制台输出进行排查。
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).startsWith("windows");
        if (windows && !"1".equals(System.getenv("TOMATO_CONSOLE_DIAGNOSTICS"))) {
            System.setOut(new PrintStream(OutputStream.nullOutputStream(), true));
            System.setErr(new PrintStream(OutputStream.nullOutputStream(), true));
        }
        Application.launch(TomatoApplication.class, args);
    }
}
