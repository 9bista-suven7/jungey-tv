package dev.suven.jungeytv.tv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramSocket;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This computer's screen and sound on the TV, like an HDMI cable without the cable.
 *
 * <p>ffmpeg records the screen and the sound and makes a live video of them; a small web
 * server here hands it out, to the TV's address only, at an address no one could guess;
 * and the TV's media player ({@link MediaRenderer}) is told to play that address. The TV
 * shows it a few seconds behind the screen - its player holds that much back, whatever
 * is sent - so the sound goes to the TV alone
 * ({@link CastSound}) and picture and sound stay together.
 *
 * <p>It ends when {@link #close} is called, or when the TV stops playing it - Stop or
 * Back on the TV's remote, another app opened, the TV turned off.
 */
public final class ScreenCast implements AutoCloseable {

    /**
     * Streamed as it is made, and live: sent at the pace it is recorded (the leading 8), and
     * growing at both ends (D), so the TV plays what arrives instead of building a buffer.
     */
    static final String DLNA_FEATURES = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=8D700000000000000000000000000000";
    private static final String MIME = "video/mp2t";
    /** How long the TV may take to start showing it. */
    private static final Duration START_WAIT = Duration.ofSeconds(30);
    /** The biggest picture the TV's player takes; a larger screen is scaled down to fit. */
    private static final int MAX_W = 1920, MAX_H = 1080;

    /** " 0: +*eDP-1 1920/344x1080/193+0+0  eDP-1" - the star marks the main screen. */
    private static final Pattern MONITOR = Pattern.compile("^\\s*\\d+:\\s+([+*]*)\\S+\\s+(\\d+)/\\d+x(\\d+)/\\d+\\+(\\d+)\\+(\\d+)");

    /** A part of the screen, in pixels. */
    public record Area(int x, int y, int width, int height) {
    }

    private final MediaRenderer renderer;
    private final InetAddress tv;
    private final ServerSocket server;
    private final String path;
    private final List<String> command;
    private final CastSound sound;
    private final Consumer<String> ended;
    private final Thread hook = new Thread(() -> shutDown(true), "jungey-tv-cast-cleanup");
    private final ScheduledExecutorService watch = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "jungey-tv-cast-watch"));
    private final long started = System.nanoTime();
    private final Object lock = new Object();

    private Process feed;
    private volatile String lastError;
    private boolean closed;
    private boolean playing;
    private int failures;

    private ScreenCast(MediaRenderer renderer, InetAddress tv, ServerSocket server, String path, List<String> command,
                       CastSound sound, Consumer<String> ended) {
        this.renderer = renderer;
        this.tv = tv;
        this.server = server;
        this.path = path;
        this.command = command;
        this.sound = sound;
        this.ended = ended;
    }

    /**
     * Start showing the main screen on the TV at this address. {@code ended} hears why, if
     * the cast stops by any means but {@link #close}.
     *
     * @param soundOnTv the sound moves to the TV; otherwise it stays here too, and the TV gets a copy
     */
    public static ScreenCast start(String host, boolean soundOnTv, Consumer<String> ended) throws TvException {
        String display = System.getenv("DISPLAY");
        if ("wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE")) || display == null || display.isBlank()) {
            throw new TvException(TvException.Problem.FAILED, "Casting needs an X11 desktop; this one is Wayland.");
        }
        if (!runs("ffmpeg", "-version")) {
            throw new TvException(TvException.Problem.FAILED, "Casting needs ffmpeg. Install it with: sudo apt install ffmpeg");
        }
        MediaRenderer renderer = MediaRenderer.of(host);

        InetAddress tv;
        ServerSocket server;
        try {
            tv = InetAddress.getByName(host);
            server = new ServerSocket(0, 4, addressFacing(tv));
        } catch (IOException e) {
            throw new TvException(TvException.Problem.FAILED, "Could not open a connection for the TV to fetch the cast.", e);
        }
        byte[] secret = new byte[16];
        new SecureRandom().nextBytes(secret);
        String path = "/jungey-tv/" + HexFormat.of().formatHex(secret) + ".ts";
        InetAddress me = server.getInetAddress();
        String url = "http://" + (me instanceof Inet6Address ? "[" + me.getHostAddress() + "]" : me.getHostAddress())
                + ":" + server.getLocalPort() + path;

        CastSound sound = soundOnTv ? CastSound.toTv() : CastSound.alsoOnTv();
        List<String> command = ffmpeg(display, screen(), sound == null ? null : sound.source);
        ScreenCast cast = new ScreenCast(renderer, tv, server, path, command, sound, ended);
        Runtime.getRuntime().addShutdownHook(cast.hook);
        Thread serving = new Thread(cast::serve, "jungey-tv-cast-server");
        serving.setDaemon(true);
        serving.start();
        try {
            renderer.load(url, "This computer", MIME);
            renderer.play();
        } catch (TvException e) {
            cast.shutDown(false);
            throw e;
        }
        cast.watch.scheduleWithFixedDelay(cast::check, 3, 2, TimeUnit.SECONDS);
        return cast;
    }

    /** How long it has been on. */
    public Duration elapsed() {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    @Override
    public void close() {
        shutDown(true);
    }

    // ------------------------------------------------------------------ the recording

    /** ffmpeg: the screen area (the whole screen if null) and a sound source (none if null), as a live H.264 video. */
    static List<String> ffmpeg(String display, Area area, String audio) {
        List<String> c = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
                "-thread_queue_size", "1024", "-f", "x11grab", "-draw_mouse", "1", "-framerate", "30"));
        String input = display;
        if (area != null) {
            c.addAll(List.of("-video_size", area.width() + "x" + area.height()));
            input += "+" + area.x() + "," + area.y();
        }
        c.addAll(List.of("-i", input));
        if (audio != null) c.addAll(List.of("-thread_queue_size", "1024", "-f", "pulse", "-ac", "2", "-ar", "48000", "-i", audio));
        c.addAll(List.of("-map", "0:v"));
        if (audio != null) c.addAll(List.of("-map", "1:a"));

        int[] size = area == null ? null : outputSize(area.width(), area.height());
        c.addAll(List.of("-vf", (size == null ? "scale=trunc(iw/2)*2:trunc(ih/2)*2" : "scale=" + size[0] + ":" + size[1])
                + ",format=yuv420p"));
        // Fast enough to leave the computer free for what is being shown; a keyframe every
        // half second so the TV can start soon after it asks; a small rate buffer, since
        // every second the TV holds back is a second behind the screen.
        c.addAll(List.of("-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-flags", "low_delay",
                "-profile:v", "high", "-level:v", "4.1", "-g", "15", "-b:v", "6M", "-maxrate", "6M", "-bufsize", "1M"));
        if (audio != null) c.addAll(List.of("-c:a", "aac", "-b:a", "160k", "-af", "aresample=async=1"));
        c.addAll(List.of("-f", "mpegts", "-muxdelay", "0", "-muxpreload", "0", "-flush_packets", "1", "pipe:1"));
        return c;
    }

    /** The picture's size: the screen's, scaled down to fit 1920 by 1080, in even numbers as H.264 wants. */
    static int[] outputSize(int width, int height) {
        double f = Math.min(1.0, Math.min((double) MAX_W / width, (double) MAX_H / height));
        return new int[]{(int) (width * f) / 2 * 2, (int) (height * f) / 2 * 2};
    }

    /** The main screen, or null to record the whole desktop. */
    private static Area screen() {
        try {
            Process p = new ProcessBuilder("xrandr", "--listmonitors").redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(3, TimeUnit.SECONDS);
            return primary(out);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** The screen marked main in {@code xrandr --listmonitors}, else the first; null if none is listed. */
    static Area primary(String listMonitors) {
        Area first = null;
        for (String line : listMonitors.split("\n")) {
            Matcher m = MONITOR.matcher(line);
            if (!m.find()) continue;
            Area a = new Area(Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)),
                    Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
            if (m.group(1).contains("*")) return a;
            if (first == null) first = a;
        }
        return first;
    }

    // ------------------------------------------------------------------ handing it to the TV

    /** Answer the TV, and nobody else. */
    private void serve() {
        while (!server.isClosed()) {
            Socket s;
            try {
                s = server.accept();
            } catch (IOException e) {
                return;
            }
            if (!s.getInetAddress().equals(tv)) {
                quietly(s);
                continue;
            }
            daemon(() -> answer(s), "jungey-tv-cast-feed").start();
        }
    }

    /** The TV asks with HEAD first, and with a GET it drops at once, before the GET it plays. */
    private void answer(Socket s) {
        try (s) {
            s.setSoTimeout(5000);
            String[] request = requestLine(s.getInputStream()).split(" ");
            s.setSoTimeout(0);
            OutputStream out = s.getOutputStream();
            boolean head = request[0].equals("HEAD");
            if (request.length < 2 || !request[1].equals(path) || !(head || request[0].equals("GET"))) {
                out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            // No length: it goes on until one side hangs up.
            out.write(("HTTP/1.1 200 OK\r\n"
                    + "Content-Type: " + MIME + "\r\n"
                    + "transferMode.dlna.org: Streaming\r\n"
                    + "contentFeatures.dlna.org: " + DLNA_FEATURES + "\r\n"
                    + "Cache-Control: no-cache\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (!head) feed(out);
        } catch (IOException e) {
            // The TV hung up.
        }
    }

    /** Record for as long as the TV takes it. A newer request replaces an older one. */
    private void feed(OutputStream out) throws IOException {
        Process p;
        synchronized (lock) {
            if (closed) return;
            p = new ProcessBuilder(command).start();
            Process before = feed;
            feed = p;
            if (before != null) end(before);
        }
        daemon(() -> keepLastError(p), "jungey-tv-cast-errors").start();
        try (InputStream in = p.getInputStream()) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            end(p);
            synchronized (lock) {
                if (feed == p) feed = null;
            }
        }
    }

    private void keepLastError(Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank()) lastError = line.trim();
            }
        } catch (IOException e) {
            // ffmpeg is gone.
        }
    }

    private static String requestLine(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int b;
        while ((b = in.read()) != -1 && head.length() < 16_384) {
            head.append((char) b);
            if (head.length() >= 4 && head.lastIndexOf("\r\n\r\n") == head.length() - 4) break;
        }
        int eol = head.indexOf("\r\n");
        return eol < 0 ? head.toString() : head.substring(0, eol);
    }

    // ------------------------------------------------------------------ keeping up with the TV

    private void check() {
        String state;
        try {
            state = renderer.state();
            failures = 0;
        } catch (TvException e) {
            if (++failures >= 3) finish("Lost the TV, so the cast stopped.");
            return;
        }
        switch (state) {
            case "PLAYING", "PAUSED_PLAYBACK" -> playing = true;
            case "STOPPED", "NO_MEDIA_PRESENT" -> {
                if (playing) finish(lastError == null ? "The TV stopped showing this computer." : "The cast stopped: " + lastError);
            }
            default -> {
            }
        }
        if (!playing && elapsed().compareTo(START_WAIT) > 0) {
            finish("The TV did not start showing the cast." + (lastError == null ? "" : " " + lastError));
        }
    }

    private void finish(String why) {
        if (shutDown(true)) ended.accept(why);
    }

    /** End it all and put the sound back; true for the call that did. */
    private boolean shutDown(boolean stopTv) {
        Process last;
        synchronized (lock) {
            if (closed) return false;
            closed = true;
            last = feed;
        }
        watch.shutdown();
        if (stopTv) {
            try {
                renderer.stop();
            } catch (TvException e) {
                // It is off, or already stopped.
            }
        }
        quietly(server);
        if (last != null) end(last);
        if (sound != null) sound.restore();
        try {
            Runtime.getRuntime().removeShutdownHook(hook);
        } catch (IllegalStateException e) {
            // Already shutting down: this is the hook.
        }
        return true;
    }

    // ------------------------------------------------------------------ bits

    /** The address of this computer that the TV can reach it at. */
    private static InetAddress addressFacing(InetAddress tv) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(tv, 9);
            return s.getLocalAddress();
        }
    }

    private static boolean runs(String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void end(Process p) {
        p.destroy();
        try {
            if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void quietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception e) {
            // Closing anyway.
        }
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }
}
