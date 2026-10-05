package com.headqlink.link;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

/**
 * Escucha el broadcast del coche en UDP 18463 y envía el Broadcast_ACK al 18464 del coche
 * desde el mismo socket.
 */
final class UdpDiscovery extends Thread {
    interface Listener {
        void onCarBroadcast(InetAddress carIp, JSONObject info);
    }

    private final Listener listener;
    private volatile boolean running = true;
    private DatagramSocket socket;
    private long lastLog;

    UdpDiscovery(Listener listener) {
        super("udp-discovery");
        this.listener = listener;
    }

    @Override
    public void run() {
        try {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.setBroadcast(true);
            socket.setSoTimeout(1000);
            socket.bind(new InetSocketAddress(Proto.UDP_LISTEN_PORT));
            L.i("UDP escuchando en " + Proto.UDP_LISTEN_PORT);
            LinkState.setUdpBusy(false);
        } catch (IOException e) {
            L.e("no se pudo abrir UDP " + Proto.UDP_LISTEN_PORT + " (¿otra app usando el puerto?)", e);
            if (e instanceof java.net.BindException) LinkState.setUdpBusy(true);
            return;
        }
        byte[] buf = new byte[2048];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                pkt.setLength(buf.length);
                socket.receive(pkt);
            } catch (SocketTimeoutException e) {
                continue;
            } catch (IOException e) {
                if (running) L.e("UDP receive", e);
                break;
            }
            String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
            InetAddress from = pkt.getAddress();
            long now = System.currentTimeMillis();
            if (now - lastLog > 10_000) {
                lastLog = now;
                L.i("UDP <- " + from.getHostAddress() + ": " + msg);
            }
            if (!msg.startsWith(Proto.UDP_MAGIC) || !msg.contains(Proto.UDP_BROADCAST)) continue;
            CarTrace.udp(from.getHostAddress(), msg);
            int brace = msg.indexOf('{');
            if (brace < 0) continue;
            try {
                listener.onCarBroadcast(from, new JSONObject(msg.substring(brace)));
            } catch (Exception e) {
                L.e("broadcast JSON inválido: " + msg, e);
            }
        }
        socket.close();
        L.i("UDP parado");
    }

    void sendAck(InetAddress carIp, String json) {
        byte[] data = Proto.udpAck(json);
        new Thread(() -> {
            try {
                socket.send(new DatagramPacket(data, data.length, carIp, Proto.UDP_REPLY_PORT));
                L.i("UDP -> " + carIp.getHostAddress() + ":" + Proto.UDP_REPLY_PORT + " " + new String(data, StandardCharsets.UTF_8));
                CarTrace.tx("UDP " + carIp.getHostAddress() + ":" + Proto.UDP_REPLY_PORT, new String(data, StandardCharsets.UTF_8));
            } catch (IOException e) {
                L.e("UDP send ACK", e);
            }
        }, "udp-ack").start();
    }

    void shutdown() {
        running = false;
        if (socket != null) socket.close();
    }
}
