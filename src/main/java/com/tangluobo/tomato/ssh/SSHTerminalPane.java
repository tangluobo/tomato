package com.tangluobo.tomato.ssh;

import com.tangluobo.tomato.zmodem.ZModem;
import com.tangluobo.tomato.zmodem.util.CustomFile;
import com.tangluobo.tomato.zmodem.util.FileAdapter;
import com.tangluobo.tomato.zmodem.xfer.zm.util.ZModemCharacter;
import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSH终端组件，使用VT100终端模拟器，支持ZModem协议（rz/sz文件传输）
 */
public class SSHTerminalPane extends BorderPane {

    // ZModem协议前缀: ** ZDLE
    private static final char[] ZMODEM_PREFIX = new char[]{
            (char) ZModemCharacter.ZPAD.value(),
            (char) ZModemCharacter.ZPAD.value(),
            (char) ZModemCharacter.ZDLE.value()
    };

    private final TerminalEmulator emulator;
    private final TerminalView terminalView;
    private SSHSession sshSession;
    private Thread readThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile ZModem zmodem;
    private volatile boolean inZModemMode = false;

    // 渲染节流
    private long lastRenderTime = 0;
    private static final long RENDER_INTERVAL = 33; // ~30fps
    private boolean renderPending = false;

    public SSHTerminalPane() {
        emulator = new TerminalEmulator();
        terminalView = new TerminalView(emulator);

        setCenter(terminalView);
        setStyle("-fx-background-color: #1e1e1e;");

        // 设置键盘输入回调
        terminalView.setKeyInputHandler(data -> {
            if (sshSession == null || !sshSession.isConnected()) return;
            if (inZModemMode) {
                // ZModem传输中，Ctrl+C取消
                if (data.length == 1 && data[0] == 0x03) {
                    try { if (zmodem != null) zmodem.cancel(); } catch (IOException ignored) {}
                }
                return;
            }
            try {
                OutputStream os = sshSession.getOutputStream();
                if (os != null) {
                    os.write(data);
                    os.flush();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * 连接SSH
     */
    public void connect(String host, int port, String username, String password) throws Exception {
        sshSession = new SSHSession(host, port, username, password);
        sshSession.connect();
        running.set(true);

        // 通知SSH服务器终端大小
        sshSession.resize(emulator.getCols(), emulator.getRows(),
                (int) terminalView.getCharWidth() * emulator.getCols(),
                (int) terminalView.getCharHeight() * emulator.getRows());

        startReadThread();
        // requestFocus必须在FX线程执行
        Platform.runLater(() -> terminalView.requestFocus());
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        running.set(false);
        if (zmodem != null) {
            try { zmodem.cancel(); } catch (IOException ignored) {}
        }
        if (readThread != null) {
            readThread.interrupt();
        }
        if (sshSession != null) {
            sshSession.disconnect();
            sshSession = null;
        }
    }

    public boolean isConnected() {
        return sshSession != null && sshSession.isConnected();
    }

    private void startReadThread() {
        readThread = new Thread(() -> {
            byte[] buffer = new byte[4096];

            while (running.get() && sshSession != null && sshSession.isConnected()) {
                try {
                    InputStream is = sshSession.getInputStream();
                    if (is == null) break;

                    int len = is.read(buffer);
                    if (len == -1) break;

                    // 检测ZModem协议前缀
                    int zmodemStart = indexOfZModem(buffer, len);
                    if (zmodemStart != -1 && !inZModemMode) {
                        // 先处理ZModem前缀之前的数据
                        if (zmodemStart > 0) {
                            final byte[] beforeData = new byte[zmodemStart];
                            System.arraycopy(buffer, 0, beforeData, 0, zmodemStart);
                            Platform.runLater(() -> {
                                emulator.process(beforeData);
                                scheduleRender();
                            });
                        }

                        // 解析ZModem帧类型: ** ZDLE B frame_type
                        // frame[5]: 48='0'=sz, 49='1'=rz
                        byte[] frame = new byte[len - zmodemStart];
                        System.arraycopy(buffer, zmodemStart, frame, 0, frame.length);
                        boolean isSz = frame.length > 5 && frame[5] == 48;

                        // 创建ZModem输入流（将帧数据预存到缓冲）
                        ZModemInputStream zmodemIn = new ZModemInputStream(is, frame);

                        if (isSz) {
                            handleSzDownload(zmodemIn, sshSession.getOutputStream());
                        } else {
                            handleRzUpload(zmodemIn, sshSession.getOutputStream());
                        }
                        continue;
                    }

                    // 普通输出，交给终端模拟器处理
                    final byte[] data = new byte[len];
                    System.arraycopy(buffer, 0, data, 0, len);
                    Platform.runLater(() -> {
                        emulator.process(data);
                        scheduleRender();
                    });

                } catch (IOException e) {
                    if (running.get()) {
                        e.printStackTrace();
                    }
                    break;
                }
            }
            running.set(false);

            // 显示断开信息
            Platform.runLater(() -> {
                emulator.process(("\r\n[连接已关闭]\r\n").getBytes());
                scheduleRender();
            });
        }, "SSH-Read-Thread");
        readThread.setDaemon(true);
        readThread.start();
    }

    /**
     * 节流渲染，避免每收到一个字节就渲染一次
     */
    private void scheduleRender() {
        long now = System.currentTimeMillis();
        if (now - lastRenderTime >= RENDER_INTERVAL) {
            lastRenderTime = now;
            terminalView.render();
            renderPending = false;
        } else if (!renderPending) {
            renderPending = true;
            // 延迟渲染
            javafx.animation.PauseTransition delay = new javafx.animation.PauseTransition(
                    javafx.util.Duration.millis(RENDER_INTERVAL - (now - lastRenderTime)));
            delay.setOnFinished(e -> {
                lastRenderTime = System.currentTimeMillis();
                terminalView.render();
                renderPending = false;
            });
            delay.play();
        }
    }

    /**
     * 处理rz上传文件（远端执行rz，本地发送文件给远端）
     */
    private void handleRzUpload(ZModemInputStream zmodemIn, OutputStream outputStream) {
        inZModemMode = true;
        Platform.runLater(() -> {
            emulator.process(("\r\n[ZModem] 检测到rz上传请求，请选择要上传的文件...\r\n").getBytes());
            scheduleRender();
        });

        try {
            zmodem = new ZModem(zmodemIn, outputStream);
            List<File> selectedFiles = openFileDialog();
            if (selectedFiles == null || selectedFiles.isEmpty()) {
                Platform.runLater(() -> {
                    emulator.process(("\r\n[ZModem] 未选择文件，取消上传\r\n").getBytes());
                    scheduleRender();
                });
                zmodem.cancel();
                return;
            }

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 正在上传文件...\r\n").getBytes());
                scheduleRender();
            });

            zmodem.send(() -> {
                List<FileAdapter> files = new ArrayList<>();
                for (File f : selectedFiles) {
                    files.add(new CustomFile(f));
                }
                return files;
            });

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 上传完成\r\n").getBytes());
                scheduleRender();
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 上传失败: " + e.getMessage() + "\r\n").getBytes());
                scheduleRender();
            });
            e.printStackTrace();
        } finally {
            inZModemMode = false;
            zmodem = null;
        }
    }

    /**
     * 处理sz下载文件（远端执行sz，本地接收远端文件）
     */
    private void handleSzDownload(ZModemInputStream zmodemIn, OutputStream outputStream) {
        inZModemMode = true;
        Platform.runLater(() -> {
            emulator.process(("\r\n[ZModem] 检测到sz下载请求，请选择保存目录...\r\n").getBytes());
            scheduleRender();
        });

        try {
            zmodem = new ZModem(zmodemIn, outputStream);
            File saveDir = openDirDialog();
            if (saveDir == null) {
                Platform.runLater(() -> {
                    emulator.process(("\r\n[ZModem] 未选择保存目录，取消下载\r\n").getBytes());
                    scheduleRender();
                });
                zmodem.cancel();
                return;
            }
            if (!saveDir.exists()) saveDir.mkdirs();

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 正在下载文件到: " + saveDir.getAbsolutePath() + "\r\n").getBytes());
                scheduleRender();
            });

            zmodem.receive(() -> new CustomFile(saveDir));

            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 下载完成\r\n").getBytes());
                scheduleRender();
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                emulator.process(("\r\n[ZModem] 下载失败: " + e.getMessage() + "\r\n").getBytes());
                scheduleRender();
            });
            e.printStackTrace();
        } finally {
            inZModemMode = false;
            zmodem = null;
        }
    }

    private List<File> openFileDialog() {
        CompletableFuture<List<File>> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                FileChooser fileChooser = new FileChooser();
                fileChooser.setTitle("选择要上传的文件");
                fileChooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("所有文件", "*.*")
                );
                List<File> files = fileChooser.showOpenMultipleDialog(getStage());
                future.complete(files != null ? files : List.of());
            } catch (Exception e) {
                future.complete(List.of());
            }
        });
        try {
            return future.get();
        } catch (Exception e) {
            return List.of();
        }
    }

    private File openDirDialog() {
        CompletableFuture<File> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
                dirChooser.setTitle("选择保存目录");
                File dir = dirChooser.showDialog(getStage());
                future.complete(dir);
            } catch (Exception e) {
                future.complete(null);
            }
        });
        try {
            return future.get();
        } catch (Exception e) {
            return null;
        }
    }

    private Stage getStage() {
        return (Stage) getScene().getWindow();
    }

    /**
     * 在buffer中查找ZModem协议前缀位置
     */
    private static int indexOfZModem(byte[] buffer, int len) {
        if (len < ZMODEM_PREFIX.length) return -1;
        for (int i = 0; i <= len - ZMODEM_PREFIX.length; i++) {
            boolean match = true;
            for (int j = 0; j < ZMODEM_PREFIX.length; j++) {
                if ((buffer[i + j] & 0xFF) != ZMODEM_PREFIX[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    /**
     * ZModem输入流，将已读取的帧数据预存到缓冲
     */
    private static class ZModemInputStream extends InputStream {
        private final InputStream input;
        private final List<Byte> buffer;

        public ZModemInputStream(InputStream input, byte[] initialData) {
            this.input = input;
            this.buffer = new ArrayList<>();
            for (byte b : initialData) {
                this.buffer.add(b);
            }
        }

        @Override
        public int read() throws IOException {
            if (!buffer.isEmpty()) {
                return buffer.removeFirst() & 0xFF;
            }
            return input.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!buffer.isEmpty()) {
                int count = 0;
                while (!buffer.isEmpty() && count < len) {
                    b[off + count] = buffer.removeFirst();
                    count++;
                }
                return count;
            }
            return input.read(b, off, len);
        }
    }
}
