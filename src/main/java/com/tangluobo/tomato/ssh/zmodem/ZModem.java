package com.tangluobo.tomato.ssh.zmodem;

import com.tangluobo.tomato.ssh.zmodem.util.FileAdapter;
import com.tangluobo.tomato.ssh.zmodem.xfer.zm.util.ZModemReceive;
import com.tangluobo.tomato.ssh.zmodem.xfer.zm.util.ZModemSend;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public class ZModem {

    private final InputStream netIs;
    private final OutputStream netOs;
    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    public ZModem(InputStream in, OutputStream out) {
        this.netIs = in;
        this.netOs = out;
    }

    public void receive(Supplier<FileAdapter> dstDir) throws IOException {
        ZModemReceive receiver = new ZModemReceive(dstDir, this.netIs, this.netOs);
        receiver.receive(this.isCancelled::get);
        this.netOs.write("\r".getBytes());
        this.netOs.flush();
    }

    public void send(Supplier<List<FileAdapter>> filesSupplier) throws Exception {
        ZModemSend sender = new ZModemSend(filesSupplier, this.netIs, this.netOs);
        sender.send(this.isCancelled::get);
        this.netOs.write("\r".getBytes());
        this.netOs.flush();
    }

    public void cancel() throws IOException {
        this.isCancelled.compareAndSet(false, true);
    }
}
