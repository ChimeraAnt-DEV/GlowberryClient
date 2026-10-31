package org.chimeramc.client.core.voice;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.util.Log;
import org.chimeramc.client.util.AppExecutors;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The peer link for proximity voice: a multicast UDP socket on the local network.
 *
 * <p>Multicast is the right transport for "players in the same world" because everyone on the
 * same LAN segment (which two devices in the same Bedrock world are) can hear every datagram with
 * no server, no account and no rendezvous. It also means the link is bounded to the local
 * network by construction, which is exactly the proximity the feature promises. A player on the
 * other side of the internet is not on the group and never hears anything.
 *
 * <p>Android needs a {@code WifiManager.MulticastLock} for multicast to reach user space; without
 * it the kernel drops the packets and the socket simply never receives, which looks like "voice
 * does nothing". The lock is acquired for the lifetime of the transport and released on
 * {@link #stop()}.
 *
 * <p>This class does no packet interpretation. It hands raw bytes to a listener and takes raw
 * bytes to send; {@link VoiceProtocol} owns the meaning. That split keeps the protocol testable
 * off a network and this class small.
 */
public final class VoiceTransport implements VoiceLink {

    private static final String TAG = "VoiceTransport";

    /** Administratively-scoped IPv4 multicast group, local to the link. */
    public static final String GROUP = "239.255.42.99";
    public static final int PORT = 47901;

    /**
     * What a received datagram triggers. Kept as its own name for the existing call sites; it is
     * the same contract as {@link VoiceLink.Listener}.
     */
    public interface Listener extends VoiceLink.Listener {
    }

    private final Context context;
    private final Listener listener;
    private MulticastSocket socket;
    private WifiManager.MulticastLock multicastLock;
    private Thread receiveThread;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public VoiceTransport(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Opens the socket and starts receiving; returns false when the network refuses. */
    @Override
    public boolean start() {
        if (running.get()) return true;
        try {
            InetAddress group = InetAddress.getByName(GROUP);
            MulticastSocket multicastSocket = new MulticastSocket(PORT);
            multicastSocket.setReuseAddress(true);
            multicastSocket.setTimeToLive(1); // link-local: never leave the LAN segment
            multicastSocket.joinGroup(group);
            socket = multicastSocket;
        } catch (IOException e) {
            Log.w(TAG, "Could not open the voice multicast socket", e);
            return false;
        }

        acquireMulticastLock();

        running.set(true);
        receiveThread = AppExecutors.thread("voice-receive", this::receiveLoop);
        receiveThread.start();
        return true;
    }

    private void acquireMulticastLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            multicastLock = wifi.createMulticastLock("chimera-voice");
            multicastLock.setReferenceCounted(true);
            multicastLock.acquire();
        } catch (Throwable t) {
            // A device without a WifiManager (or a blocked lock) still gets unicast sockets; do
            // not fail the transport over the lock.
            Log.w(TAG, "Could not acquire the multicast lock", t);
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    /** Sends one datagram to the group; a failure is reported as false, never thrown. */
    @Override
    public boolean send(byte[] data) {
        MulticastSocket target = socket;
        if (target == null || data == null || data.length == 0) return false;
        try {
            DatagramPacket packet = new DatagramPacket(
                    data, data.length, InetAddress.getByName(GROUP), PORT);
            target.send(packet);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[VoiceProtocol.MAX_PAYLOAD + 64];
        while (running.get()) {
            MulticastSocket active = socket;
            if (active == null) break;
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                active.receive(packet);
                NetworkInterface iface = packet.getAddress() == null
                        ? null
                        : NetworkInterface.getByInetAddress(packet.getAddress());
                if (iface != null && iface.isLoopback()) {
                    // Our own beacon echoes back on some stacks; drop it so we never appear as
                    // our own peer.
                    continue;
                }
                byte[] copy = new byte[packet.getLength()];
                System.arraycopy(packet.getData(), packet.getOffset(), copy, 0, packet.getLength());
                if (listener != null) listener.onDatagram(copy, copy.length);
            } catch (IOException e) {
                if (!running.get()) break;
                Log.w(TAG, "Voice receive failed", e);
                if (listener != null) listener.onStopped(e.getMessage());
                break;
            }
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            closeQuietly();
            releaseMulticastLock();
            return;
        }
        closeQuietly();
        Thread thread = receiveThread;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            receiveThread = null;
        }
        releaseMulticastLock();
    }

    private void closeQuietly() {
        MulticastSocket active = socket;
        socket = null;
        if (active == null) return;
        try {
            active.leaveGroup(InetAddress.getByName(GROUP));
        } catch (IOException ignored) {
        }
        active.close();
    }

    private void releaseMulticastLock() {
        WifiManager.MulticastLock lock = multicastLock;
        multicastLock = null;
        if (lock == null) return;
        try {
            if (lock.isHeld()) lock.release();
        } catch (Throwable ignored) {
        }
    }
}
