package org.chimeramc.client.core.voice;

import android.util.Log;

import org.chimeramc.client.util.SignalGate;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The relay transport: a unicast UDP session with a server that forwards between clients.
 *
 * <p>Where {@link VoiceTransport} multicasts to everyone on the LAN, this talks to one server. The
 * server assigns a client id on {@code HELLO}, then relays each datagram to the peers whose channel
 * can hear the sender's. That is what makes voice work between players who are not on the same
 * Wi-Fi.
 *
 * <p><b>Three things make it survive a phone's network.</b> First, a <em>keepalive</em>: a phone on
 * mobile data sits behind a NAT that drops an idle UDP mapping after a minute or so, so the client
 * sends a small packet on the interval the server advertises and the mapping stays open. Second,
 * <em>reconnect with backoff</em>: if the server goes quiet, the client re-runs the handshake on
 * the schedule {@link RelayReconnectPolicy} computes, with jitter so a recovering server is not hit
 * by every client at once. Third, the <em>handshake is idempotent</em>: a re-HELLO replaces the old
 * session server-side, so a reconnect cannot leave a ghost.
 *
 * <p>The socket is a plain {@link DatagramSocket}; the server address is resolved once at start and
 * re-resolved on reconnect, because a phone that changed network has a new DNS answer. Every byte
 * on the wire is built by {@link VoiceProtocol}; this class only moves datagrams and tracks the
 * session's liveness.
 */
public final class VoiceRelayTransport implements VoiceLink {

    private static final String TAG = "VoiceRelayTransport";

    /** How long to wait for the server's HELLO_ACK before treating the attempt as failed. */
    private static final int HANDSHAKE_TIMEOUT_MS = 4000;
    /** How long the read loop blocks before checking whether it should still be running. */
    private static final int READ_TIMEOUT_MS = 500;
    /** The keepalive period when the server has not told us one; the server's own default. */
    private static final int DEFAULT_KEEPALIVE_MS = 3000;
    /** How long without any server packet before the link is considered dead and reconnected. */
    private static final long SERVER_TIMEOUT_MS = 12000;

    private final VoiceRelayAddress address;
    private final String displayName;
    private final String password;
    private final String tokenSecret;
    private final String deviceId;
    private final String initialChannel;
    private final Listener listener;
    private final RelayReconnectPolicy reconnect = RelayReconnectPolicy.defaults();
    /** Cuts a backoff wait short so stop() does not have to wait out the delay it just scheduled. */
    private final SignalGate backoffGate = new SignalGate();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile DatagramSocket socket;
    private volatile InetSocketAddress server;
    private volatile long clientId;
    private volatile int keepaliveMs = DEFAULT_KEEPALIVE_MS;
    private volatile long lastServerPacketMs;
    private volatile String lastError;
    private Thread loopThread;

    public VoiceRelayTransport(VoiceRelayAddress address, String displayName, String password,
                               String channel, Listener listener) {
        this(address, displayName, password, "", "", channel, listener);
    }

    /**
     * The full constructor.
     *
     * <p>{@code tokenSecret} and {@code deviceId} drive the signed-token handshake: when a secret is
     * set the HELLO carries a freshly minted token instead of the raw password, and the token is
     * re-minted on every reconnect so an expiry during a long session cannot lock the client out.
     * {@code deviceId} rides in its own field so the relay can bind the session to a device for
     * banning, whether or not tokens are in use.
     */
    public VoiceRelayTransport(VoiceRelayAddress address, String displayName, String password,
                               String tokenSecret, String deviceId, String channel,
                               Listener listener) {
        this.address = address;
        this.displayName = displayName == null ? "" : displayName;
        this.password = password == null ? "" : password;
        this.tokenSecret = tokenSecret == null ? "" : tokenSecret;
        this.deviceId = deviceId == null ? "" : deviceId;
        this.initialChannel = VoiceChannel.normalize(channel);
        this.listener = listener;
    }

    /** The credential to present: a fresh signed token when a secret is configured, else the password. */
    private byte[] credential() {
        if (!tokenSecret.isEmpty()) {
            String token = VoiceToken.issue(tokenSecret, deviceId,
                    VoiceToken.DEFAULT_TTL_SECONDS, System.currentTimeMillis() / 1000L);
            if (token != null) {
                return token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public boolean prefersOpus() {
        return true;
    }

    /** The id the server assigned, or 0 before the handshake completes. */
    public long clientId() {
        return clientId;
    }

    /** True once the handshake has completed and the server is answering. */
    public boolean isConnected() {
        return running.get() && clientId != 0L;
    }

    /** The last failure reason, or null. */
    public String lastError() {
        return lastError;
    }

    /** The server's advertised keepalive interval, for the status line. */
    public int keepaliveIntervalMs() {
        return keepaliveMs;
    }

    /** The device id stamped into every relayed frame, so the relay can bind a ban to it. */
    public String deviceId() {
        return deviceId;
    }

    @Override
    public boolean start() {
        if (running.get()) return true;
        try {
            server = new InetSocketAddress(InetAddress.getByName(address.host), address.port);
        } catch (IOException e) {
            lastError = "cannot resolve " + address.host;
            Log.w(TAG, "Could not resolve the relay host", e);
            return false;
        }
        running.set(true);
        loopThread = new Thread(this::runLoop, "voice-relay");
        loopThread.setDaemon(true);
        loopThread.start();
        return true;
    }

    @Override
    public void stop() {
        // Signalled before the join so a loop sitting in a 30-second backoff exits now; otherwise
        // the join has to time out and the socket stays open behind it.
        backoffGate.signal();
        if (!running.compareAndSet(true, false)) {
            closeSocket();
            return;
        }
        sendBye();
        closeSocket();
        Thread thread = loopThread;
        loopThread = null;
        if (thread != null) {
            try {
                thread.join(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        clientId = 0L;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean send(byte[] data) {
        DatagramSocket active = socket;
        InetSocketAddress target = server;
        if (active == null || target == null || data == null || data.length == 0) return false;
        try {
            active.send(new DatagramPacket(data, data.length, target));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * The connection's whole life: connect, then pump keepalives and reads until it breaks, then
     * back off and do it again. It runs on its own thread so the rest of the session is unaware of
     * a reconnect — the module keeps sending frames and they simply start arriving again.
     */
    private void runLoop() {
        while (running.get()) {
            boolean connected = connect();
            if (!connected) {
                long delay = reconnect.nextDelayMs(Math.random());
                if (reconnect.isDegraded()) {
                    lastError = "relay unreachable (" + address.display() + ")";
                }
                Log.w(TAG, "Relay connect failed; retrying in " + delay + "ms");
                sleep(delay);
                continue;
            }

            reconnect.reset();
            lastError = null;
            pumpUntilBroken();
            clientId = 0L;
            if (!running.get()) return;
            Log.w(TAG, "Relay link dropped; reconnecting");
            sleep(reconnect.nextDelayMs(Math.random()));
        }
    }

    /** Opens a socket, sends HELLO and waits for the ACK. */
    private boolean connect() {
        closeSocket();
        DatagramSocket fresh;
        try {
            fresh = new DatagramSocket();
            fresh.setSoTimeout(READ_TIMEOUT_MS);
        } catch (IOException e) {
            lastError = "could not open a socket";
            return false;
        }
        socket = fresh;

        send(VoiceProtocol.encodeHello(displayName, deviceId, initialChannel, credential()));

        long deadline = System.currentTimeMillis() + HANDSHAKE_TIMEOUT_MS;
        byte[] buffer = new byte[VoiceProtocol.MAX_PAYLOAD + 128];
        while (running.get() && System.currentTimeMillis() < deadline) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                fresh.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                return false;
            }
            byte[] copy = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), copy, 0, packet.getLength());
            VoiceProtocol.Packet decoded = VoiceProtocol.decode(copy);
            if (decoded == null) continue;

            if (decoded.type == VoiceProtocol.TYPE_HELLO_ACK) {
                clientId = decoded.clientId;
                if (decoded.sequence > 0) keepaliveMs = decoded.sequence;
                lastServerPacketMs = System.currentTimeMillis();
                Log.i(TAG, "Relay connected as client " + clientId
                        + " (keepalive " + keepaliveMs + "ms)");
                return true;
            }
            if (decoded.type == VoiceProtocol.TYPE_NOTICE) {
                lastError = noticeMessage(decoded.sequence);
                Log.w(TAG, "Relay refused the connection: " + lastError);
                return false;
            }
        }
        lastError = "no response from " + address.display();
        return false;
    }

    /** Reads datagrams and sends keepalives until the server goes quiet or the socket breaks. */
    private void pumpUntilBroken() {
        byte[] buffer = new byte[VoiceProtocol.MAX_PAYLOAD + 128];
        long lastKeepalive = 0;
        while (running.get()) {
            DatagramSocket active = socket;
            if (active == null) return;

            long now = System.currentTimeMillis();
            if (now - lastKeepalive >= keepaliveMs) {
                send(VoiceProtocol.encodePong(clientId));
                lastKeepalive = now;
            }
            if (now - lastServerPacketMs > SERVER_TIMEOUT_MS) {
                lastError = "relay stopped responding";
                return;
            }

            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                active.receive(packet);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (running.get()) lastError = "relay socket error";
                return;
            }
            byte[] copy = new byte[packet.getLength()];
            System.arraycopy(packet.getData(), packet.getOffset(), copy, 0, packet.getLength());
            VoiceProtocol.Packet decoded = VoiceProtocol.decode(copy);
            if (decoded == null) continue;
            lastServerPacketMs = System.currentTimeMillis();

            if (decoded.type == VoiceProtocol.TYPE_PING) {
                send(VoiceProtocol.encodePong(clientId));
                continue;
            }
            if (decoded.type == VoiceProtocol.TYPE_NOTICE) {
                lastError = noticeMessage(decoded.sequence);
                continue;
            }
            // The relay rewrites the sender id into every relayed frame, so the local id can be
            // trusted as "ours" and our own echo dropped without decoding the peer id.
            if (decoded.clientId != 0L && decoded.clientId == clientId) continue;
            if (listener != null) listener.onDatagram(copy, copy.length);
        }
    }

    private static String noticeMessage(int reason) {
        switch (reason) {
            case VoiceProtocol.NOTICE_SERVER_FULL:
                return "relay is full";
            case VoiceProtocol.NOTICE_BAD_PASSWORD:
                return "relay password is wrong";
            case VoiceProtocol.NOTICE_CHANNEL_FULL:
                return "channel is full on the relay";
            case VoiceProtocol.NOTICE_BAD_PROTOCOL:
                return "relay speaks a different protocol version";
            case VoiceProtocol.NOTICE_BAD_TOKEN:
                return "relay rejected the join token";
            case VoiceProtocol.NOTICE_BANNED:
                return "you are banned from this relay";
            default:
                return "relay refused the connection";
        }
    }

    private void sendBye() {
        if (clientId == 0L) return;
        send(VoiceProtocol.encodeRelayBeacon(clientId, VoiceProtocol.TYPE_BYE, "", displayName,
                VoiceChannel.WORLD, VoiceProtocol.VISIBILITY_PUBLIC, "",
                VoiceProtocol.CAPACITY_NONE, 0f, false, 0f, 0f, 0f, 0, VoiceProtocol.CODEC_PCM, null));
    }

    private void closeSocket() {
        DatagramSocket active = socket;
        socket = null;
        if (active != null) {
            try {
                active.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Waits out a backoff delay, cut short by {@link #stop()}.
     *
     * <p>The delay is the point here, so it is not replaced by a wake-up; it is made interruptible.
     * The schedule reaches 30 seconds, and a stop that has to wait that out is a stop that appears
     * to hang while a socket stays open.
     */
    private void sleep(long millis) {
        backoffGate.await(Math.max(0L, millis));
    }
}
