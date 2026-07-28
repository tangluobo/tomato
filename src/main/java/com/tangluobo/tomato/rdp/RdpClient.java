package com.tangluobo.tomato.rdp;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;
import javax.swing.JComponent;

import com.sshtools.javardp.CredentialProvider;
import com.sshtools.javardp.DefaultCredentialsProvider;
import com.sshtools.javardp.IContext;
import com.sshtools.javardp.IContext.ReadyType;
import com.sshtools.javardp.Options;
import com.sshtools.javardp.RdesktopDisconnectException;
import com.sshtools.javardp.RdesktopException;
import com.sshtools.javardp.RdesktopLicenseException;
import com.sshtools.javardp.State;
import com.sshtools.javardp.graphics.RdesktopCanvas;
import com.sshtools.javardp.io.DefaultIO;
import com.sshtools.javardp.keymapping.KeyCode_FileBased;
import com.sshtools.javardp.layers.Rdp;
import com.sshtools.javardp.rdp5.VChannels;
import com.sshtools.javardp.rdp5.cliprdr.ClipChannel;

/**
 * RDP客户端封装类，基于com.sshtools:rdp库
 * 提供连接、断开、状态查询等功能，支持NLA认证和多会话
 */
public class RdpClient {

    private static final Logger logger = Logger.getLogger(RdpClient.class.getName());

    private volatile boolean connected = false;
    private volatile Rdp rdpLayer;
    private RdesktopCanvas canvas;
    private State state;
    private Options options;
    private Consumer<String> onDisconnected;
    private Consumer<Void> onConnected;
    private Thread rdpThread;

    /**
     * 嵌入式IContext实现，不创建独立窗口
     */
    private class EmbeddedContext implements IContext {
        private volatile boolean loggedOn = false;
        private volatile boolean ready = false;

        @Override
        public void dispose() {
            connected = false;
        }

        @Override
        public void error(Exception e, boolean sysexit) {
            logger.log(Level.SEVERE, "RDP错误: " + e.getMessage(), e);
            if (sysexit) {
                connected = false;
                notifyDisconnected("连接错误: " + e.getMessage());
            }
        }

        @Override
        public byte[] loadLicense() throws java.io.IOException {
            // 暂不支持许可证持久化
            return null;
        }

        @Override
        public void saveLicense(byte[] license) throws java.io.IOException {
            // 暂不支持许可证持久化
        }

        @Override
        public void screenResized(int width, int height, boolean clientInitiated) {
            logger.info("屏幕大小变更: " + width + "x" + height);
        }

        @Override
        public void setLoggedOn() {
            loggedOn = true;
            logger.info("RDP登录成功");
        }

        @Override
        public void toggleFullScreen() {
            // 嵌入模式不支持全屏切换
        }

        @Override
        public void ready(ReadyType readyType) {
            logger.info("RDP ready回调: " + readyType);
            if (readyType == ReadyType.DISPLAY) {
                ready = true;
                connected = true;
                logger.info("RDP桌面就绪，触发onConnected回调");
                if (onConnected != null) {
                    onConnected.accept(null);
                }
            }
        }

        public boolean isLoggedOn() {
            return loggedOn;
        }

        public boolean isReady() {
            return ready;
        }
    }

    public RdpClient() {
    }

    /**
     * 连接到RDP服务器（异步，连接就绪后通过onConnected回调通知）
     *
     * @param host     服务器地址
     * @param port     服务器端口（通常3389）
     * @param username 用户名
     * @param password 密码
     * @param domain   域名（可为null）
     * @param width    桌面宽度
     * @param height   桌面高度
     * @param bpp      色深（16/24）
     */
    public void connect(String host, int port, String username, String password,
                         String domain, int width, int height, int bpp) {
        if (connected) {
            throw new IllegalStateException("RDP客户端已连接，请先断开当前连接");
        }

        // 创建配置
        options = new Options();
        options.setWidth(width);
        options.setHeight(height);
        options.setBpp(bpp);
        options.setRdp5(true);
        options.setPacketEncryption(true);
        options.setBitmapCaching(true);
        options.setMapClipboard(true);
        options.setLowLatency(true);

        // 配置安全类型：提供STANDARD和SSL（不含HYBRID/NLA）
        // Windows即使去掉NLA勾选仍要求SSL（SSL_REQUIRED_BY_SERVER），
        // 但不需要HYBRID(NLA/CredSSP)。首选SSL（列表末尾），STANDARD作为备选。
        options.getSecurityTypes().clear();
        options.getSecurityTypes().add(com.sshtools.javardp.SecurityType.STANDARD);
        options.getSecurityTypes().add(com.sshtools.javardp.SecurityType.SSL);

        // 设置宽松的TrustManager：接受RDP服务器自签名证书
        X509TrustManager permissiveTrustManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }
            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }
            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        options.setTrustManager(permissiveTrustManager);

        // 应用RDP TLS兼容性修复（替换Transport.CIPHERS + 设置SSLContext + 移除安全限制）
        RdpTlsFix.apply(permissiveTrustManager);

        // 创建凭证
        DefaultCredentialsProvider dcp = new DefaultCredentialsProvider();
        if (username != null) dcp.setUsername(username);
        if (password != null) dcp.setPassword(password.toCharArray());
        if (domain != null && !domain.isEmpty()) dcp.setDomain(domain);

        // 创建状态
        state = new State(options);

        // 加载键盘映射（keymaps在rdp JAR的根目录下）
        String keyMapPath = "keymaps/";
        String mapFile = "en-us";
        try {
            // 尝试从rdp JAR的classpath加载
            URL resource = KeyCode_FileBased.class.getResource("/" + keyMapPath + mapFile);
            if (resource == null) {
                // 备选：用ClassLoader加载
                resource = ClassLoader.getSystemResource(keyMapPath + mapFile);
            }
            if (resource != null) {
                InputStream istr = resource.openStream();
                if (istr != null) {
                    KeyCode_FileBased keyMap = new KeyCode_FileBased(options, resource, istr);
                    options.setKeymap(keyMap);
                    istr.close();
                    logger.info("键盘映射加载成功: " + mapFile);
                }
            } else {
                logger.warning("未找到键盘映射文件: " + keyMapPath + mapFile + "，使用默认映射");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "加载键盘映射失败: " + e.getMessage());
        }

        // 创建虚拟通道
        VChannels channels = new VChannels(state);
        ClipChannel clipChannel = new ClipChannel();
        if (state.isRDP5()) {
            try {
                channels.register(clipChannel);
            } catch (RdesktopException e) {
                logger.log(Level.WARNING, "注册剪贴板通道失败: " + e.getMessage());
            }
        }

        // 创建嵌入式Context
        EmbeddedContext context = new EmbeddedContext();

        // 创建画布（不在SwingNode中显示，直到ready回调触发）
        canvas = new RdesktopCanvas(context, state);
        state.setCanvas(canvas);

        // 注册剪贴板焦点监听
        ((JComponent) canvas.getDisplay()).addFocusListener(clipChannel);

        // 创建RDP层
        rdpLayer = new Rdp(context, state, channels);

        // 在新线程中执行RDP连接（connect+mainLoop是阻塞调用）
        rdpThread = new Thread(() -> {
            try {
                logger.info("开始RDP连接: " + host + ":" + port);
                rdpLayer.connect(new DefaultIO(InetAddress.getByName(host), port),
                        dcp, options.getCommand(), options.getDirectory());
                logger.info("RDP协议握手完成，进入主循环: " + host + ":" + port);
                rdpLayer.mainLoop();
                logger.info("RDP主循环正常退出");
            } catch (RdesktopDisconnectException e) {
                logger.info("RDP连接断开: " + e.getMessage());
                notifyDisconnected(e.getMessage() != null ? e.getMessage() : "连接已断开");
            } catch (RdesktopLicenseException e) {
                logger.log(Level.SEVERE, "RDP许可证错误: " + e.getMessage());
                notifyDisconnected("许可证错误: " + e.getMessage());
            } catch (RdesktopException e) {
                logger.log(Level.SEVERE, "RDP异常: " + e.getMessage(), e);
                notifyDisconnected("连接异常: " + e.getMessage());
            } catch (java.net.UnknownHostException e) {
                logger.log(Level.SEVERE, "无法解析主机: " + host);
                notifyDisconnected("无法解析主机: " + host);
            } catch (java.net.ConnectException e) {
                logger.log(Level.SEVERE, "连接被拒绝: " + e.getMessage());
                notifyDisconnected("连接被拒绝: " + host + ":" + port + " - " + e.getMessage());
            } catch (java.net.SocketException e) {
                if (connected) {
                    logger.log(Level.WARNING, "连接中断: " + e.getMessage());
                    notifyDisconnected("连接中断: " + e.getMessage());
                }
            } catch (java.io.IOException e) {
                logger.log(Level.SEVERE, "IO错误: " + e.getMessage());
                notifyDisconnected("IO错误: " + e.getMessage());
            } catch (Exception e) {
                logger.log(Level.SEVERE, "RDP连接错误: " + e.getMessage(), e);
                notifyDisconnected("连接错误: " + e.getMessage());
            } finally {
                connected = false;
            }
        }, "RDP-" + host);
        rdpThread.setDaemon(true);
        rdpThread.start();
    }

    private void notifyDisconnected(String reason) {
        connected = false;
        if (onDisconnected != null) {
            onDisconnected.accept(reason);
        }
    }

    /**
     * 断开RDP连接
     */
    public void disconnect() {
        if (!connected && (rdpLayer == null || !rdpLayer.isConnected())) return;
        connected = false;
        try {
            if (rdpLayer != null && rdpLayer.isConnected()) {
                rdpLayer.disconnect();
                logger.info("RDP已断开");
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "断开RDP连接时出错: " + e.getMessage());
        }
    }

    /**
     * 查询连接状态
     */
    public boolean isConnected() {
        return connected && rdpLayer != null && rdpLayer.isConnected();
    }

    /**
     * 设置断开连接回调
     */
    public void setOnDisconnected(Consumer<String> callback) {
        this.onDisconnected = callback;
    }

    /**
     * 设置连接就绪回调
     */
    public void setOnConnected(Consumer<Void> callback) {
        this.onConnected = callback;
    }

    /**
     * 获取渲染画布的JComponent（仅在onConnected回调后才有效）
     */
    public JComponent getDisplayComponent() {
        return canvas != null ? (JComponent) canvas.getDisplay() : null;
    }

    /**
     * 获取RdesktopCanvas
     */
    public RdesktopCanvas getCanvas() {
        return canvas;
    }
}
