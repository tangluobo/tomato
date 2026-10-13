package com.tangluobo.tomato.module.connect.handler;

import org.bytedeco.ffmpeg.avcodec.AVCodec;
import org.bytedeco.ffmpeg.avcodec.AVCodecContext;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.swscale.SwsContext;
import org.bytedeco.javacpp.PointerPointer;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.Loader;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

import static org.bytedeco.ffmpeg.global.avcodec.*;
import static org.bytedeco.ffmpeg.global.avutil.*;
import static org.bytedeco.ffmpeg.global.swscale.*;
import static org.bytedeco.ffmpeg.avcodec.AVCodecContext.FF_THREAD_SLICE;

/** Decodes framed H.264 access units in process, without probing or raw-video process pipes. */
final class ScrcpyVideoDecoder implements AutoCloseable {
    private final Runnable closeInput;
    private final DataInputStream input;
    private final Object nativeLock = new Object();
    private AVCodecContext context;
    private AVPacket packet;
    private AVFrame decoded;
    private AVFrame converted;
    private SwsContext scaler;
    private final ScrcpyPacketBuffer packets = new ScrcpyPacketBuffer();
    private Thread receiver;
    private volatile boolean closed;

    ScrcpyVideoDecoder(ScrcpyControlClient server) throws IOException {
        this(server.videoInput(), server::closeVideo);
    }

    ScrcpyVideoDecoder(DataInputStream input, Runnable closeInput) throws IOException {
        this.input = input;
        this.closeInput = closeInput;
        try {
            AVCodec codec = avcodec_find_decoder(AV_CODEC_ID_H264);
            if (codec == null || codec.isNull()) throw new IOException("内置 H.264 解码器不可用");
            context = avcodec_alloc_context3(codec);
            if (context == null || context.isNull()) throw new IOException("无法分配解码器");
            // Slice threading parallelizes a frame without buffering several frames like frame threading.
            context.thread_type(FF_THREAD_SLICE).thread_count(4);
            context.flags(context.flags() | AV_CODEC_FLAG_LOW_DELAY);

            check(avcodec_open2(context, codec, (PointerPointer<?>) null), "打开 H.264 解码器");
            packet = av_packet_alloc();
            decoded = av_frame_alloc();
            converted = av_frame_alloc();

            if (packet == null || packet.isNull() || decoded == null || decoded.isNull()
                    || converted == null || converted.isNull()) throw new IOException("无法分配解码缓冲区");
            receiver = Thread.ofVirtual().name("adb-video-receiver").start(() -> {
                try {
                    while (!closed) packets.offer(readPacket(input));
                } catch (IOException error) {
                    packets.finish(error);
                } catch (RuntimeException error) {
                    packets.finish(new IOException("读取视频流失败", error));
                }
            });
        } catch (IOException | RuntimeException | LinkageError error) {
            close();
            throw new IOException("无法初始化内置视频解码器：" + error.getMessage(), error);
        }
    }

    AdbClient.VideoFrame readFrame() throws IOException {
        long catchUpDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(33);
        while (!closed) {
            synchronized (nativeLock) {
                if (closed) break;
                int result = avcodec_receive_frame(context, decoded);
                if (result == 0) {
                    // Decode all reference frames, but avoid converting obsolete frames in a backlog.
                    if (packets.hasPending() && System.nanoTime() < catchUpDeadline) {
                        av_frame_unref(decoded);
                        continue;
                    }
                    AdbClient.VideoFrame frame = convertFrame();
                    av_frame_unref(decoded);
                    return frame;
                }
                if (!needsMoreInput(result, Loader.getPlatform())) check(result, "接收视频帧");
            }
            ScrcpyPacketBuffer.Packet encoded = packets.take();
            byte[] payload = encoded.data();
            synchronized (nativeLock) {
                if (closed) break;
                if (encoded.reset()) avcodec_flush_buffers(context);
                av_packet_unref(packet);
                check(av_new_packet(packet, payload.length), "分配视频数据包");
                packet.data().put(payload);
                packet.pts(encoded.pts()).dts(encoded.pts());
                check(avcodec_send_packet(context, packet), "解码 H.264 数据包");
            }
        }
        throw new EOFException("scrcpy 视频流已关闭");
    }

    static boolean needsMoreInput(int result, String platform) {
        // AVERROR(EAGAIN) is -35 on Darwin, -11 on the Linux/Windows FFmpeg builds.
        // Do not accept both everywhere: -11 is EDEADLK on Darwin.
        return result == (platform.startsWith("macosx") ? -35 : -11);
    }

    static EncodedPacket readPacket(DataInputStream input) throws IOException {
        long ptsAndFlags = input.readLong();
        int size = input.readInt();
        if (size <= 0 || size > 16 * 1024 * 1024) throw new IOException("无效的 scrcpy 视频包长度：" + size);
        byte[] data = new byte[size];
        input.readFully(data);
        return new EncodedPacket(ptsAndFlags & 0x3fffffffffffffffL, ptsAndFlags < 0,
                (ptsAndFlags & (1L << 62)) != 0, data);
    }

    static byte[] prependConfig(byte[] config, byte[] payload) {
        if (config == null || config.length == 0) return payload;
        byte[] data = Arrays.copyOf(config, config.length + payload.length);
        System.arraycopy(payload, 0, data, config.length, payload.length);
        return data;
    }

    private AdbClient.VideoFrame convertFrame() throws IOException {
        int width = decoded.width();
        int height = decoded.height();
        if (converted.width() != width || converted.height() != height) {
            av_frame_unref(converted);
            converted.format(AV_PIX_FMT_BGRA).width(width).height(height);
            check(av_frame_get_buffer(converted, 32), "分配显示帧");
        }
        scaler = sws_getCachedContext(scaler, width, height, decoded.format(), width, height,
                AV_PIX_FMT_BGRA, SWS_FAST_BILINEAR, null, null, (double[]) null);
        if (scaler == null || scaler.isNull()) throw new IOException("无法创建视频颜色转换器");
        int rows = sws_scale(scaler, decoded.data(), decoded.linesize(), 0, height, converted.data(), converted.linesize());
        if (rows != height) throw new IOException("视频颜色转换失败");
        int rowBytes = Math.multiplyExact(width, 4);
        byte[] pixels = new byte[Math.multiplyExact(rowBytes, height)];
        int stride = converted.linesize(0);
        BytePointer data = converted.data(0);
        if (stride == rowBytes) {
            data.get(pixels);
        } else {
            for (int y = 0; y < height; y++) {
                data.position((long) y * stride).get(pixels, y * rowBytes, rowBytes);
            }
        }
        return new AdbClient.VideoFrame(width, height, pixels);
    }

    private static void check(int result, String operation) throws IOException {
        if (result < 0) throw new IOException(operation + "失败（FFmpeg " + result + "）");
    }

    @Override
    public void close() {
        closed = true;
        packets.close();
        closeInput.run(); // Unblock a socket read before waiting for the decoder lock.
        if (receiver != null) receiver.interrupt();
        synchronized (nativeLock) {
            if (scaler != null && !scaler.isNull()) sws_freeContext(scaler);
            scaler = null;
            if (converted != null && !converted.isNull()) av_frame_free(converted);
            if (decoded != null && !decoded.isNull()) av_frame_free(decoded);
            if (packet != null && !packet.isNull()) av_packet_free(packet);
            if (context != null && !context.isNull()) avcodec_free_context(context);
            converted = null;
            decoded = null;
            packet = null;
            context = null;
        }
    }

    record EncodedPacket(long pts, boolean config, boolean keyframe, byte[] data) { }
}
