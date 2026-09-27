package dev.suven.jungeytv.tv;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Finding Samsung TVs on the home network the way casting apps do: a UPnP search that
 * every device on the network hears, then asking whatever says it is Samsung to describe
 * itself.
 */
public final class Discovery {

    private Discovery() {
    }

    private static final String SEARCH = """
            M-SEARCH * HTTP/1.1\r
            HOST: 239.255.255.250:1900\r
            MAN: "ssdp:discover"\r
            MX: 2\r
            ST: ssdp:all\r
            \r
            """;

    /** Every Samsung TV that answered within the time given, in the order they answered. */
    public static List<TvInfo> find(Duration listen) {
        Set<String> candidates = new LinkedHashSet<>();
        try (MulticastSocket socket = new MulticastSocket()) {
            socket.setTimeToLive(2);
            socket.setSoTimeout(300);
            byte[] ask = SEARCH.getBytes(StandardCharsets.US_ASCII);
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 2; i++) socket.send(new DatagramPacket(ask, ask.length, group, 1900));

            byte[] buffer = new byte[8192];
            long end = System.nanoTime() + listen.toNanos();
            while (System.nanoTime() < end) {
                DatagramPacket reply = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(reply);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                String text = new String(reply.getData(), 0, reply.getLength(), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ENGLISH);
                if (text.contains("samsung")) candidates.add(reply.getAddress().getHostAddress());
            }
        } catch (Exception e) {
            // No multicast on this network: nothing found, which the caller reports.
        }

        List<TvInfo> tvs = new ArrayList<>();
        for (String host : candidates) {
            TvInfo info = TvInfo.fetch(host);
            if (info != null && info.samsung()) tvs.add(info);
        }
        return tvs;
    }
}
