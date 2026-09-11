package org.dkf.jed2k;

import org.dkf.jed2k.protocol.Hash;

public class Settings {
    public Hash userAgent = new Hash(Hash.EMULE);
    public String modName = "jed2k";
    public String clientName = "jed2k";
    public int listenPort = 4661;
    public int udpPort = 4662;
    public int version = 0x3c;
    public int modMajor = 0;
    public int modMinor = 0;
    public int modBuild = 0;
    public int maxFailCount = 20;
    public int maxPeerListSize = 100;
    public int minPeerReconnectTime = 10;
    public int peerConnectionTimeout = 5;
    /** Seconds to wait for an upload-queue response after OP_STARTUPLOADREQ. */
    public int peerQueueResponseTimeout = 60;
    /** Standard ED2K interval before asking a disconnected queued source again. */
    public int peerReaskTime = 29 * 60;
    public int sessionConnectionsLimit = 20;
    /** Queued peers kept in addition to active/connecting peer slots. */
    public int queuedConnectionsLimit = 40;
    public int bufferPoolSize = 250;            // dataSize of buffer pool in blocks of 180K
    public int maxConnectionsPerSecond = 10;    // for testing purposes
    public int compressionVersion = 0;          // use 1 for activate compression
    public int serverSearchTimeout = 15;        // seconds
    public boolean reconnectoToServer = false;  // reconnect to server if connection was closed due to error

    /**
     * send ping message to server every serverPingTimeout seconds
     */
    public long serverPingTimeout = 0;

    @Override
    public String toString() {
        return "Settings{" +
                "userAgent=" + userAgent +
                ", modName='" + modName + '\'' +
                ", clientName='" + clientName + '\'' +
                ", listenPort=" + listenPort +
                ", udpPort=" + udpPort +
                ", version=" + version +
                ", modMajor=" + modMajor +
                ", modMinor=" + modMinor +
                ", modBuild=" + modBuild +
                ", maxFailCount=" + maxFailCount +
                ", maxPeerListSize=" + maxPeerListSize +
                ", minPeerReconnectTime=" + minPeerReconnectTime +
                ", peerConnectionTimeout=" + peerConnectionTimeout +
                ", peerQueueResponseTimeout=" + peerQueueResponseTimeout +
                ", peerReaskTime=" + peerReaskTime +
                ", sessionConnectionsLimit=" + sessionConnectionsLimit +
                ", queuedConnectionsLimit=" + queuedConnectionsLimit +
                ", bufferPoolSize=" + bufferPoolSize +
                ", maxConnectionsPerSecond=" + maxConnectionsPerSecond +
                ", compressionVersion=" + compressionVersion +
                ", serverSearchTimeout=" + serverSearchTimeout +
                ", serverPingTimeout=" + serverPingTimeout +
                ", reconnectToServer=" + (reconnectoToServer?"yes":"no") +
                '}';
    }
}
