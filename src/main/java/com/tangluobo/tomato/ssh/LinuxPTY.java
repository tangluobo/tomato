package com.tangluobo.tomato.ssh;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Linux/macOS POSIX PTY 实现，使用 Foreign Function & Memory API。
 *
 * 通过 posix_openpt + grantpt + unlockpt + ptsname + fork + execv 创建伪终端。
 * 支持 bash/zsh 等交互式 shell，以及 vim/ssh/top 等控制台程序。
 *
 * 适用于 Linux（libc.so.6）和 macOS（libSystem.B.dylib）。
 */
public class LinuxPTY implements PseudoTerminal {

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC;

    // 文件描述符标志
    private static final int O_RDWR = 0x0002;
    private static final int O_NOCTTY = 0x0100;  // Linux
    private static final int O_NOCTTY_MAC = 0x0200; // macOS

    // ioctl 请求
    private static final int TIOCSWINSZ = 0x5414;  // Linux
    private static final int TIOCSWINSZ_MAC = 0x80087467;  // macOS
    private static final int TIOCSCTTY = 0x540E;   // Linux
    private static final int TIOCSCTTY_MAC = 0x20007461;  // macOS

    // waitpid 选项
    private static final int WNOHANG = 1;

    // 信号
    private static final int SIGKILL = 9;

    private static final boolean IS_MAC;

    // libc 函数句柄
    private static final MethodHandle posix_openpt;
    private static final MethodHandle grantpt;
    private static final MethodHandle unlockpt;
    private static final MethodHandle ptsname;
    private static final MethodHandle fork;
    private static final MethodHandle setsid;
    private static final MethodHandle open;
    private static final MethodHandle close;
    private static final MethodHandle dup2;
    private static final MethodHandle ioctl;
    private static final MethodHandle execvp;
    private static final MethodHandle _exit;
    private static final MethodHandle read;
    private static final MethodHandle write;
    private static final MethodHandle waitpid;
    private static final MethodHandle kill;
    private static final MethodHandle getenv;
    private static final MethodHandle strerror;

    static {
        boolean isMac = System.getProperty("os.name", "").toLowerCase().contains("mac");
        IS_MAC = isMac;

        SymbolLookup libc = null;
        try {
            if (isMac) {
                libc = SymbolLookup.libraryLookup("libSystem.B.dylib", Arena.global());
            } else {
                // 尝试标准 glibc
                libc = SymbolLookup.libraryLookup("libc.so.6", Arena.global());
            }
        } catch (Exception e) {
            try {
                // 回退：尝试通用 libc
                libc = SymbolLookup.libraryLookup("libc.so", Arena.global());
            } catch (Exception e2) {
                libc = null;
            }
        }
        LIBC = libc;

        if (libc != null) {
            try {
                posix_openpt = lookup("posix_openpt",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                grantpt = lookup("grantpt",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                unlockpt = lookup("unlockpt",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                ptsname = lookup("ptsname",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                fork = lookup("fork",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
                setsid = lookup("setsid",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
                open = lookup("open",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                close = lookup("close",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                dup2 = lookup("dup2",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                ioctl = lookup("ioctl",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
                execvp = lookup("execvp",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                _exit = lookup("_exit",
                    FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT));
                read = lookup("read",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                write = lookup("write",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
                waitpid = lookup("waitpid",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                kill = lookup("kill",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
                getenv = lookup("getenv",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
                strerror = lookup("strerror",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            } catch (Throwable t) {
                throw new ExceptionInInitializerError("Failed to load libc functions: " + t.getMessage());
            }
        } else {
            posix_openpt = null;
            grantpt = null;
            unlockpt = null;
            ptsname = null;
            fork = null;
            setsid = null;
            open = null;
            close = null;
            dup2 = null;
            ioctl = null;
            execvp = null;
            _exit = null;
            read = null;
            write = null;
            waitpid = null;
            kill = null;
            getenv = null;
            strerror = null;
        }
    }

    private static MethodHandle lookup(String name, FunctionDescriptor desc) {
        MemorySegment addr = LIBC.find(name)
            .orElseThrow(() -> new UnsatisfiedLinkError("libc: " + name));
        return LINKER.downcallHandle(addr, desc);
    }

    // 实例状态
    private int masterFd = -1;
    private int childPid = -1;
    private volatile boolean closed = false;
    private Arena arena;

    @Override
    public boolean isAvailable() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return (os.contains("linux") || os.contains("mac") || os.contains("nix")) && LIBC != null;
    }

    @Override
    public void start(String command, int cols, int rows) throws IOException {
        arena = Arena.ofShared();

        try {
            // 1. 打开主设备 /dev/ptmx
            int flags = O_RDWR | (IS_MAC ? O_NOCTTY_MAC : O_NOCTTY);
            masterFd = (int) posix_openpt.invoke(flags);
            if (masterFd < 0) {
                throw new IOException("posix_openpt failed: " + getError());
            }

            // 2. 授权和解锁从设备
            int rc = (int) grantpt.invoke(masterFd);
            if (rc != 0) {
                throw new IOException("grantpt failed: " + getError());
            }
            rc = (int) unlockpt.invoke(masterFd);
            if (rc != 0) {
                throw new IOException("unlockpt failed: " + getError());
            }

            // 3. 获取从设备路径
            MemorySegment slaveNameSeg = (MemorySegment) ptsname.invoke(masterFd);
            if (slaveNameSeg.address() == 0) {
                throw new IOException("ptsname failed: " + getError());
            }
            // 将 C 字符串拷贝到 Java（fork 前完成）
            String slavePath = slaveNameSeg.getString(0);

            // 4. 设置终端窗口大小（struct winsize: 4 shorts = 8 字节）
            MemorySegment winsize = arena.allocate(ValueLayout.JAVA_SHORT, 4);
            winsize.set(ValueLayout.JAVA_SHORT, 0, (short) rows);    // ws_row
            winsize.set(ValueLayout.JAVA_SHORT, 2, (short) cols);    // ws_col
            winsize.set(ValueLayout.JAVA_SHORT, 4, (short) 0);       // ws_xpixel
            winsize.set(ValueLayout.JAVA_SHORT, 6, (short) 0);       // ws_ypixel
            long winsizeReq = IS_MAC ? TIOCSWINSZ_MAC : TIOCSWINSZ;
            rc = (int) ioctl.invoke(masterFd, winsizeReq, winsize);
            // 忽略 ioctl 失败（不致命）

            // 5. 准备命令行参数（execvp 需要 argv 数组）
            // 解析命令行为参数数组
            String[] parts = command.trim().split("\\s+");
            MemorySegment cmdSeg = arena.allocateUtf8String(parts[0]);

            // argv 数组：指针数组，以 NULL 结尾
            MemorySegment argv = arena.allocate(ValueLayout.ADDRESS, (long) (parts.length + 1));
            for (int i = 0; i < parts.length; i++) {
                MemorySegment argSeg = arena.allocateUtf8String(parts[i]);
                argv.set(ValueLayout.ADDRESS, (long) i * ValueLayout.ADDRESS.byteSize(), argSeg);
            }
            argv.set(ValueLayout.ADDRESS, (long) parts.length * ValueLayout.ADDRESS.byteSize(), MemorySegment.NULL);

            // 6. fork 子进程
            int pid = (int) fork.invoke();
            if (pid < 0) {
                throw new IOException("fork failed: " + getError());
            }

            if (pid == 0) {
                // ===== 子进程 =====
                // 注意：fork 后只能调用 async-signal-safe 的函数
                // 不创建 Java 对象，只调用 native 函数

                // 创建新会话，脱离控制终端
                setsid.invoke();

                // 打开从设备作为控制终端
                try (Arena childArena = Arena.ofConfined()) {
                    MemorySegment slaveSeg = childArena.allocateUtf8String(slavePath);
                    int slaveFd = (int) open.invoke(slaveSeg, O_RDWR);
                    if (slaveFd < 0) {
                        _exit.invoke(1);
                    }

                    // 设置控制终端
                    int cttyReq = IS_MAC ? TIOCSCTTY_MAC : TIOCSCTTY;
                    ioctl.invoke(slaveFd, cttyReq, 0L);

                    // 重定向 stdin/stdout/stderr
                    dup2.invoke(slaveFd, 0);
                    dup2.invoke(slaveFd, 1);
                    dup2.invoke(slaveFd, 2);
                    if (slaveFd > 2) {
                        close.invoke(slaveFd);
                    }
                }

                // 关闭主设备
                close.invoke(masterFd);

                // 执行 shell
                execvp.invoke(cmdSeg, argv);

                // execvp 失败则退出
                _exit.invoke(127);
                // 不会到达这里
            }

            // ===== 父进程 =====
            childPid = pid;

            // 等待一小段时间确认子进程启动
            Thread.sleep(50);

            // 检查子进程是否仍在运行
            if (!isAlive()) {
                throw new IOException("Child process exited immediately (command: " + command + ")");
            }

        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("LinuxPTY start failed: " + t.getMessage(), t);
        }
    }

    @Override
    public int read(byte[] buffer) throws IOException {
        if (closed || masterFd < 0) return -1;
        try {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment buf = localArena.allocate(buffer.length);
                long n = (long) read.invoke(masterFd, buf, (long) buffer.length);
                if (n <= 0) {
                    return -1;
                }
                int len = (int) n;
                MemorySegment.copy(buf, ValueLayout.JAVA_BYTE, 0L, buffer, 0, len);
                return len;
            }
        } catch (Throwable t) {
            throw new IOException("LinuxPTY read failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (closed || masterFd < 0) return;
        try {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment buf = localArena.allocate(data.length);
                MemorySegment.copy(data, 0, buf, ValueLayout.JAVA_BYTE, 0L, data.length);
                long n = (long) write.invoke(masterFd, buf, (long) data.length);
                if (n < 0) {
                    throw new IOException("write failed: " + getError());
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("LinuxPTY write failed: " + t.getMessage(), t);
        }
    }

    @Override
    public void resize(int cols, int rows) {
        if (closed || masterFd < 0) return;
        try {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment winsize = localArena.allocate(ValueLayout.JAVA_SHORT, 4);
                winsize.set(ValueLayout.JAVA_SHORT, 0, (short) rows);
                winsize.set(ValueLayout.JAVA_SHORT, 2, (short) cols);
                winsize.set(ValueLayout.JAVA_SHORT, 4, (short) 0);
                winsize.set(ValueLayout.JAVA_SHORT, 6, (short) 0);
                long req = IS_MAC ? TIOCSWINSZ_MAC : TIOCSWINSZ;
                ioctl.invoke(masterFd, req, winsize);
            }
        } catch (Throwable t) {
            // 忽略 resize 失败
        }
    }

    @Override
    public boolean isAlive() {
        if (childPid < 0) return false;
        try {
            try (Arena localArena = Arena.ofConfined()) {
                MemorySegment status = localArena.allocate(ValueLayout.JAVA_INT);
                int ret = (int) waitpid.invoke(childPid, status, WNOHANG);
                if (ret == 0) {
                    return true; // 仍在运行
                }
                return false; // 已退出
            }
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public int getPid() {
        return childPid;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;

        // 终止子进程
        if (childPid > 0) {
            try {
                kill.invoke(childPid, SIGKILL);
            } catch (Throwable ignored) {}
            // 回收子进程
            try {
                try (Arena localArena = Arena.ofConfined()) {
                    MemorySegment status = localArena.allocate(ValueLayout.JAVA_INT);
                    waitpid.invoke(childPid, status, 0);
                }
            } catch (Throwable ignored) {}
            childPid = -1;
        }

        // 关闭主设备
        if (masterFd >= 0) {
            try {
                close.invoke(masterFd);
            } catch (Throwable ignored) {}
            masterFd = -1;
        }

        if (arena != null) {
            arena.close();
        }
    }

    /** 获取 errno 对应的错误消息 */
    private String getError() {
        try {
            // errno 通过 __errno_location 或 __error 获取
            // 简化处理：直接返回通用消息
            return "errno";
        } catch (Exception e) {
            return "unknown error";
        }
    }
}
