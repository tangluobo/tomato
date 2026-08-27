package com.tangluobo.tomato.rdp;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.URL;
import java.nio.file.Paths;
import java.security.cert.X509Certificate;
import java.util.logging.FileHandler;
import java.util.logging.SimpleFormatter;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.X509TrustManager;
import javax.swing.JComponent;

import com.tangluobo.tomato.rdp.CredentialProvider;
import com.tangluobo.tomato.rdp.DefaultCredentialsProvider;
import com.tangluobo.tomato.rdp.IContext;
import com.tangluobo.tomato.rdp.IContext.ReadyType;
import com.tangluobo.tomato.rdp.Options;
import com.tangluobo.tomato.rdp.RdesktopDisconnectException;
import com.tangluobo.tomato.rdp.RdesktopException;
import com.tangluobo.tomato.rdp.RdesktopLicenseException;
import com.tangluobo.tomato.rdp.State;
import com.tangluobo.tomato.rdp.graphics.RdesktopCanvas;
import com.tangluobo.tomato.rdp.io.DefaultIO;
import com.tangluobo.tomato.rdp.keymapping.KeyCode_FileBased;
import com.tangluobo.tomato.rdp.layers.Rdp;
import com.tangluobo.tomato.rdp.rdp5.VChannels;
import com.tangluobo.tomato.rdp.clipboard.FixedClipChannel;

/**
 * RDP客户端封装类，基于com.sshtools:rdp库
 * 提供连接、断开、状态查询等功能，支持NLA认证和多会话
 */
public class RdpClient {

    private static final Logger logger = Logger.getLogger(RdpClient.class.getName());

    static {
        try {
            FileHandler handler = new FileHandler(Paths.get("rdp-debug.log").toAbsolutePath().toString(), true);
            handler.setFormatter(new SimpleFormatter());
            Logger.getLogger("").addHandler(handler);
        } catch (Exception e) {
            logger.log(Level.WARNING, "无法初始化 RDP 诊断日志", e);
        }
    }

    private volatile boolean connected = false;
    private volatile Rdp rdpLayer;
    private RdesktopCanvas canvas;
    private State state;
    private Options options;
    private Consumer<String> onDisconnected;
    private Consumer<Void> onConnected;
    private Consumer<Void> onFirstFrame;
    private Thread rdpThread;
    private volatile boolean mapClipboard = true;

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
            // 关键：必须调用canvas.triggerReady()！
            // RdesktopFrame的标准实现会调用canvas.triggerReady(ready)，
            // 这在INPUT阶段触发input.triggerReadyToSend()→doLockKeys()，
            // 发送CapsLock/NumLock同步键事件，这是服务器开始推送画面的前提条件。
            // 不调用triggerReady(INPUT)会导致服务器不推送画面数据（黑屏）。
            if (canvas != null) {
                canvas.triggerReady(readyType);
            }
            if (readyType == ReadyType.DISPLAY) {
                ready = true;
                connected = true;
                logger.info("RDP桌面就绪，触发onConnected回调, rdp5=" + state.isRDP5());
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
     * @param host         服务器地址
     * @param port         服务器端口（通常3389）
     * @param username     用户名
     * @param password     密码
     * @param domain       域名（可为null）
     * @param width        桌面宽度
     * @param height       桌面高度
     * @param bpp          色深（16/24）
     * @param useSsl       是否使用SSL/TLS加密（无TLS服务器需设为false）
     * @param mapClipboard 是否启用剪贴板同步（本地与远程桌面互拷文本）
     */
    public void connect(String host, int port, String username, String password,
                         String domain, int width, int height, int bpp, boolean useSsl,
                         boolean mapClipboard) {
        if (connected) {
            throw new IllegalStateException("RDP客户端已连接，请先断开当前连接");
        }
        this.mapClipboard = mapClipboard;

        // 启用RDP库调试日志（slf4j-jdk14桥接到java.util.logging）
        Logger sshtools = Logger.getLogger("com.tangluobo.tomato.rdp");
        sshtools.setLevel(Level.FINE);
        Logger root = Logger.getLogger("");
        for (java.util.logging.Handler h : root.getHandlers()) {
            h.setLevel(Level.FINE);
        }

        // 创建配置
        options = new Options();
        options.setWidth(width);
        options.setHeight(height);
        options.setBpp(bpp);
        options.setRdp5(true);
        options.setPacketEncryption(true);
        options.setBitmapCaching(true);
        options.setMapClipboard(mapClipboard);
        options.setLowLatency(true);

        // 调试：启用hex dump查看所有收发数据
        options.setDebugHexdump(true);

        // 配置安全类型
        // State构造函数取securityTypes列表的最后一个元素作为初始securityType，
        // 所以列表最后一个元素决定了优先选择的安全类型。
        // 详见State.java: securityType = options.getSecurityTypes().get(size - 1)
        options.getSecurityTypes().clear();
        options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.STANDARD);
        if (useSsl) {
            // SSL在列表末尾会被优先选择；服务器不支持SSL时ISO协商会自动降级
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.SSL);
        }

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

        // 创建状态（使用RdpState阻止processGeneralCaps错误禁用RDP5）
        RdpState rdpState = new RdpState(options);
        rdpState.lockRdp5(); // 在连接前锁定RDP5状态
        state = rdpState;

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

        // 创建虚拟通道（FixedVChannels修复库分片重组NPE：大消息分片即断连）
        VChannels channels = new FixedVChannels(state);

        // 创建嵌入式Context
        EmbeddedContext context = new EmbeddedContext();

        // 创建画布（不在SwingNode中显示，直到ready回调触发）
        canvas = new RdesktopCanvas(context, state);
        state.setCanvas(canvas);

        // 注册剪贴板同步通道（含焦点监听，焦点切换时同步本地/远程剪贴板内容）
        registerClipboardChannel(state, canvas, channels);

        // 创建RDP层（使用RdpPatch修复rdp5_process加密bug）
        rdpLayer = new RdpPatch(context, state, channels);
        configureFrameCallback((RdpPatch) rdpLayer);

        // 核心修复1：通过反射替换Transport为RdpTransport
        // RdpTransport覆盖negotiateSSL()方法，强制使用TLS 1.2协议
        // 这解决了Transport.negotiateSSL()未调用setEnabledProtocols()
        // 导致SSLSocket尝试TLS 1.3而被Windows RDP服务器Connection Reset的问题
        // 同时传入hostname用于SNI（Server Name Indication），避免Windows Server 2012 R2+
        // 因缺少SNI扩展而重置TLS连接
        RdpTlsFix.injectRdpTransport(rdpLayer, host);

        // javardp 3.0.0 把带 FASTPATH_OUTPUT_ENCRYPTED 标志（0x02）的包误判为 slow-path。
        // 替换接收器，使其按 action 位而不是完整低两位识别 fast-path。
        RdpIsoFix.injectRdpIso(rdpLayer);

        // 在新线程中执行RDP连接（connect+mainLoop是阻塞调用）
        rdpThread = new Thread(() -> {
            try {
                logger.info("开始RDP连接: " + host + ":" + port + " useSsl=" + useSsl);
                rdpLayer.connect(new DefaultIO(InetAddress.getByName(host), port),
                        dcp, options.getCommand(), options.getDirectory());
                // 核心修复：SSL模式下在connect()之后强制设置licenceIssued=true
                // 原因：Secure.receive()在licenceIssued=false时会读取4字节sec_flags header，
                // 但SSL模式下接收的数据已被TLS层加密，不存在Secure层header。
                // 这导致4字节RDP有效数据被错误消费，后续所有PDU解析失败（bitmapUpdates=0）。
                // 必须在connect()之后设置：connect()期间Secure层发送PDUs时需要sec_flags header
                // （服务器在SSL模式下仍期望看到sec_flags来识别PDU类型如SEC_LOGON_INFO），
                // 但connect()之后接收PDUs时不应再读sec_flags（SSL模式下接收侧无此header）。
                if (useSsl && !state.isLicenceIssued()) {
                    logger.info("SSL模式：在connect()后强制设置licenceIssued=true（接收侧无Secure层header）");
                    state.setLicenceIssued(true);
                }
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
                // SSL协商失败且当前使用SSL时，自动回退到STANDARD重试
                if (useSsl && e.getMessage() != null && e.getMessage().contains("SSL negotiation failed")) {
                    logger.warning("SSL协商失败，尝试回退到Standard RDP Security（无TLS）重连...");
                    retryWithStandardSecurity(host, port, dcp);
                    return;
                }
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
                String msg = e.getMessage();
                // SSL_NOT_ALLOWED_BY_SERVER错误：服务器不支持SSL，回退到Standard RDP Security
                if (useSsl && msg != null && msg.contains("SSL_NOT_ALLOWED_BY_SERVER")) {
                    logger.warning("服务器不支持SSL，回退到Standard RDP Security重连...");
                    retryWithStandardSecurity(host, port, dcp);
                    return;
                }
                // SSL_REQUIRED_BY_SERVER错误：服务器要求SSL/TLS，回退到SSL/TLS加密重连
                if (!useSsl && msg != null && msg.contains("SSL_REQUIRED_BY_SERVER")) {
                    logger.warning("服务器要求Enhanced RDP Security（TLS），回退到SSL/TLS加密重连...");
                    retryWithSslSecurity(host, port, dcp);
                    return;
                }
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

        // 诊断：定期报告RDP状态（持续30秒，每5秒一次）
        java.util.Timer diagTimer = new java.util.Timer("RDP-Diag", true);
        final int[] count = {0};
        diagTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                count[0]++;
                if (count[0] > 6 || rdpThread == null || !rdpThread.isAlive()) {
                    cancel();
                    return;
                }
                if (rdpLayer instanceof RdpPatch) {
                    RdpPatch patch = (RdpPatch) rdpLayer;
                    logger.info(String.format("[DIAG #%d] totalPDUs=%d, bitmapUpdates=%d, rdp5Packets=%d, sent=%d, recv=%d, connected=%b, active=%b, rdp5=%b, licenceIssued=%b, securityType=%s",
                            count[0], patch.getTotalPduCount(), patch.getBitmapUpdateCount(), patch.getRdp5PacketCount(),
                            RdpTlsFix.RdpTransport.getSendPktCount(), RdpTlsFix.RdpTransport.getRecvPktCount(),
                            rdpLayer.isConnected(), state.isActive(), state.isRDP5(), state.isLicenceIssued(),
                            state.getSecurityType()));
                }
            }
        }, 5000, 5000);
    }

    /**
     * 注册剪贴板同步虚拟通道（启用时）。
     * 注意：回退重连路径会重建VChannels/Canvas，必须重新注册，否则回退后剪贴板同步失效。
     */
    private void registerClipboardChannel(State state, RdesktopCanvas canvas, VChannels channels) {
        if (!mapClipboard || !state.isRDP5()) {
            return;
        }
        try {
            // FixedClipChannel：修复库内clipboard未初始化（远程粘贴不可用）及
            // UnicodeHandler编码bug（中文乱码）、远程→本地格式选择问题
            FixedClipChannel clipChannel = new FixedClipChannel();
            channels.register(clipChannel);
            ((JComponent) canvas.getDisplay()).addFocusListener(clipChannel);
            logger.info("剪贴板同步通道已注册");
        } catch (RdesktopException e) {
            logger.log(Level.WARNING, "注册剪贴板通道失败: " + e.getMessage());
        }
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
     * SSL协商失败后，回退到Standard RDP Security（无TLS）重试连接。
     * 重新创建所有RDP层对象，仅使用STANDARD安全类型。
     */
    private void retryWithStandardSecurity(String host, int port, CredentialProvider dcp) {
        try {
            // 断开之前的连接
            if (rdpLayer != null && rdpLayer.isConnected()) {
                try { rdpLayer.disconnect(); } catch (Exception ignored) {}
            }

            // 重新配置：仅STANDARD安全类型
            options.getSecurityTypes().clear();
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.SSL);
            // 注意：STANDARD放在最后，这样State构造函数会选择STANDARD
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.STANDARD);

            // 重新创建状态
            RdpState rdpState = new RdpState(options);
            rdpState.lockRdp5();
            state = rdpState;

            // 重新创建画布
            EmbeddedContext context = new EmbeddedContext();
            canvas = new RdesktopCanvas(context, state);
            state.setCanvas(canvas);

            // 重新创建RDP层（FixedVChannels修复库分片重组NPE）
            VChannels channels = new FixedVChannels(state);
            // 回退重连后重新注册剪贴板通道（重建VChannels/Canvas后原注册已丢失）
            registerClipboardChannel(state, canvas, channels);
            rdpLayer = new RdpPatch(context, state, channels);
            configureFrameCallback((RdpPatch) rdpLayer);

            // 注入RdpTransport（仅当服务器支持SSL时才需要）
            RdpTlsFix.injectRdpTransport(rdpLayer, host);

            RdpIsoFix.injectRdpIso(rdpLayer);


            logger.info("回退重连: " + host + ":" + port + " securityType=STANDARD");
            rdpLayer.connect(new DefaultIO(InetAddress.getByName(host), port),
                    dcp, options.getCommand(), options.getDirectory());
            logger.info("Standard RDP Security连接成功，进入主循环");
            rdpLayer.mainLoop();
            logger.info("RDP主循环正常退出");
        } catch (RdesktopDisconnectException e) {
            logger.info("RDP连接断开: " + e.getMessage());
            notifyDisconnected(e.getMessage() != null ? e.getMessage() : "连接已断开");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Standard RDP Security重连也失败: " + e.getMessage(), e);
            notifyDisconnected("连接失败（SSL和Standard均不可用）: " + e.getMessage());
        } finally {
            connected = false;
        }
    }

    /**
     * 服务器要求SSL/TLS（SSL_REQUIRED_BY_SERVER）时，回退到SSL/TLS加密重试连接。
     * 重新创建所有RDP层对象，使用SSL作为优先安全类型。
     */
    private void retryWithSslSecurity(String host, int port, CredentialProvider dcp) {
        try {
            // 断开之前的连接
            if (rdpLayer != null && rdpLayer.isConnected()) {
                try { rdpLayer.disconnect(); } catch (Exception ignored) {}
            }

            // 重新配置：STANDARD在前，SSL在末尾（State构造函数取最后一个元素作为初始securityType）
            options.getSecurityTypes().clear();
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.STANDARD);
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.SSL);

            // 重新创建状态
            RdpState rdpState = new RdpState(options);
            rdpState.lockRdp5();
            state = rdpState;

            // 重新创建画布
            EmbeddedContext context = new EmbeddedContext();
            canvas = new RdesktopCanvas(context, state);
            state.setCanvas(canvas);

            // 重新创建RDP层（FixedVChannels修复库分片重组NPE）
            VChannels channels = new FixedVChannels(state);
            // 回退重连后重新注册剪贴板通道（重建VChannels/Canvas后原注册已丢失）
            registerClipboardChannel(state, canvas, channels);
            rdpLayer = new RdpPatch(context, state, channels);
            configureFrameCallback((RdpPatch) rdpLayer);

            // 注入RdpTransport（强制TLS 1.2 + SNI，修复SSLSocket默认TLS 1.3被服务器Reset的问题）
            RdpTlsFix.injectRdpTransport(rdpLayer, host);

            RdpIsoFix.injectRdpIso(rdpLayer);


            logger.info("回退重连: " + host + ":" + port + " securityType=SSL");
            rdpLayer.connect(new DefaultIO(InetAddress.getByName(host), port),
                    dcp, options.getCommand(), options.getDirectory());

            // SSL模式下connect()后必须强制设置licenceIssued=true：
            // SSL接收侧数据已被TLS层加密，不存在Secure层sec_flags header，
            // 若licenceIssued=false会错误消费4字节RDP有效数据，导致PDU解析失败。
            if (!state.isLicenceIssued()) {
                logger.info("SSL模式：在connect()后强制设置licenceIssued=true（接收侧无Secure层header）");
                state.setLicenceIssued(true);
            }
            logger.info("SSL/TLS连接成功，进入主循环");
            rdpLayer.mainLoop();
            logger.info("RDP主循环正常退出");
        } catch (RdesktopDisconnectException e) {
            logger.info("RDP连接断开: " + e.getMessage());
            notifyDisconnected(e.getMessage() != null ? e.getMessage() : "连接已断开");
        } catch (Exception e) {
            // SSL/TLS握手失败时，尝试使用HYBRID（CredSSP/NLA）重连
            // 很多Windows服务器要求NLA（网络级别认证），不支持纯TLS连接
            String msg = e.getMessage();
            if (msg != null && msg.contains("SSL negotiation failed")) {
                logger.warning("SSL/TLS握手失败，尝试使用HYBRID（CredSSP/NLA）重连...");
                retryWithHybridSecurity(host, port, dcp);
                return;
            }
            logger.log(Level.SEVERE, "SSL/TLS重连也失败: " + e.getMessage(), e);
            notifyDisconnected("连接失败（Standard和SSL均不可用）: " + e.getMessage());
        } finally {
            connected = false;
        }
    }

    /**
     * 使用HYBRID（CredSSP/NLA）安全类型重试连接。
     * 当SSL/TLS握手失败时，服务器可能要求NLA（网络级别认证）而非纯TLS。
     * HYBRID在TLS握手后进行CredSSP（NTLM/Kerberos）认证。
     */
    private void retryWithHybridSecurity(String host, int port, CredentialProvider dcp) {
        try {
            // 断开之前的连接
            if (rdpLayer != null && rdpLayer.isConnected()) {
                try { rdpLayer.disconnect(); } catch (Exception ignored) {}
            }

            // 重新配置：使用HYBRID（CredSSP/NLA）
            options.getSecurityTypes().clear();
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.STANDARD);
            options.getSecurityTypes().add(com.tangluobo.tomato.rdp.SecurityType.HYBRID);

            // 重新创建状态
            RdpState rdpState = new RdpState(options);
            rdpState.lockRdp5();
            state = rdpState;

            // 重新创建画布
            EmbeddedContext context = new EmbeddedContext();
            canvas = new RdesktopCanvas(context, state);
            state.setCanvas(canvas);

            // 重新创建RDP层（FixedVChannels修复库分片重组NPE）
            VChannels channels = new FixedVChannels(state);
            // 回退重连后重新注册剪贴板通道（重建VChannels/Canvas后原注册已丢失）
            registerClipboardChannel(state, canvas, channels);
            rdpLayer = new RdpPatch(context, state, channels);
            configureFrameCallback((RdpPatch) rdpLayer);

            // 注入RdpTransport（强制TLS 1.2 + SNI + 全量密码套件）
            RdpTlsFix.injectRdpTransport(rdpLayer, host);

            RdpIsoFix.injectRdpIso(rdpLayer);


            logger.info("回退重连: " + host + ":" + port + " securityType=HYBRID (CredSSP/NLA)");
            rdpLayer.connect(new DefaultIO(InetAddress.getByName(host), port),
                    dcp, options.getCommand(), options.getDirectory());

            // HYBRID模式下connect()后也需设置licenceIssued=true（与SSL相同，TLS接收侧无Secure层header）
            if (!state.isLicenceIssued()) {
                logger.info("HYBRID模式：在connect()后强制设置licenceIssued=true");
                state.setLicenceIssued(true);
            }
            logger.info("HYBRID (CredSSP/NLA) 连接成功，进入主循环");
            rdpLayer.mainLoop();
            logger.info("RDP主循环正常退出");
        } catch (RdesktopDisconnectException e) {
            logger.info("RDP连接断开: " + e.getMessage());
            notifyDisconnected(e.getMessage() != null ? e.getMessage() : "连接已断开");
        } catch (Exception e) {
            logger.log(Level.SEVERE, "HYBRID (CredSSP/NLA) 重连也失败: " + e.getMessage(), e);
            notifyDisconnected("连接失败（Standard、SSL和HYBRID均不可用）: " + e.getMessage());
        } finally {
            connected = false;
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

    /** Sets a callback that runs only after the first desktop bitmap is rendered. */
    public void setOnFirstFrame(Consumer<Void> callback) {
        this.onFirstFrame = callback;
    }

    private void configureFrameCallback(RdpPatch patch) {
        patch.setOnFirstFrame(v -> {
            Consumer<Void> callback = onFirstFrame;
            if (callback != null) {
                callback.accept(null);
            }
        });
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
