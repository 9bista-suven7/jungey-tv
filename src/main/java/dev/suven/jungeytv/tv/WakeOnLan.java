package dev.suven.jungeytv.tv;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Waking the TV from standby: the "magic packet", its network address repeated sixteen
 * times, sent to everyone on the network. The TV only listens for it with "Power On with
 * Mobile" turned on in its network settings.
 */
final class WakeOnLan {

    private WakeOnLan() {
    }

    static byte[] packet(String mac) {
        String hex = mac == null ? "" : mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) throw new IllegalArgumentException("not a MAC address: " + mac);
        byte[] address = new byte[6];
        for (int i = 0; i < 6; i++) address[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);

        byte[] packet = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xff;
        for (int i = 6; i < packet.length; i += 6) System.arraycopy(address, 0, packet, i, 6);
        return packet;
    }

    /** Send it everywhere it might be heard: the whole network, the TV's subnet, the TV itself. */
    static void send(String mac, String host) throws IOException {
        byte[] packet = packet(mac);
        Set<String> targets = new LinkedHashSet<>();
        targets.add("255.255.255.255");
        if (host != null && host.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
            targets.add(host.replaceFirst("\\.\\d+$", ".255"));
            targets.add(host);
        }
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            for (int round = 0; round < 3; round++) {
                for (String target : targets) {
                    InetAddress to = InetAddress.getByName(target);
                    socket.send(new DatagramPacket(packet, packet.length, to, 9));
                    socket.send(new DatagramPacket(packet, packet.length, to, 7));
                }
            }
        }
    }
}
