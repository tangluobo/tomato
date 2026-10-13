package com.tangluobo.tomato.module.connect.handler;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayDeque;

/** Bounded compressed-video backlog. After dropping references, resume only at an IDR frame. */
final class ScrcpyPacketBuffer {
    private static final long MAX_SPAN_US = 250_000;
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final ArrayDeque<Packet> packets = new ArrayDeque<>();
    private byte[] config;
    private boolean configPending;
    private int bytes;
    private boolean awaitingKeyframe;
    private IOException failure;

    synchronized void offer(ScrcpyVideoDecoder.EncodedPacket encoded) {
        if (failure != null) return;
        if (encoded.config()) {
            config = ScrcpyVideoDecoder.prependConfig(configPending ? config : null, encoded.data());
            configPending = true;
            return;
        }
        configPending = false;
        if (!packets.isEmpty() && (encoded.pts() - packets.peekFirst().pts() > MAX_SPAN_US
                || packets.size() >= 90 || bytes + encoded.data().length > MAX_BYTES)) {
            packets.clear();
            bytes = 0;
            awaitingKeyframe = true;
        }
        if (awaitingKeyframe && !encoded.keyframe()) return;
        boolean reset = awaitingKeyframe;
        awaitingKeyframe = false;
        byte[] payload = encoded.keyframe()
                ? ScrcpyVideoDecoder.prependConfig(config, encoded.data()) : encoded.data();
        packets.addLast(new Packet(encoded.pts(), payload, reset));
        bytes += payload.length;
        notifyAll();
    }

    synchronized Packet take() throws IOException {
        while (packets.isEmpty() && failure == null) {
            try {
                wait();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("视频解码已中断", error);
            }
        }
        if (packets.isEmpty()) throw failure;
        Packet packet = packets.removeFirst();
        bytes -= packet.data().length;
        return packet;
    }

    synchronized boolean hasPending() { return !packets.isEmpty(); }

    synchronized void finish(IOException error) {
        if (failure == null) failure = error;
        notifyAll();
    }

    synchronized void close() {
        packets.clear();
        bytes = 0;
        finish(new EOFException("scrcpy 视频流已关闭"));
    }

    record Packet(long pts, byte[] data, boolean reset) { }
}
