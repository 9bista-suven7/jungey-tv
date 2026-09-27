package dev.suven.jungeytv.tv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.net.ssl.SSLHandshakeException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One open connection to the TV's remote-control channel - the one Samsung's own phone
 * app uses. Buttons are sent over it as key names; the list of apps and launching one are
 * messages to the TV itself.
 *
 * <p>The TV answers on its own time, so everything it sends lands in a queue and is waited
 * for by what it is: "ms.channel.connect" once the connection is allowed, the app list
 * when it was asked for, and so on.
 */
public final class Remote implements AutoCloseable {

    /** How the TV lists us under Device Connection Manager. Keep it: the token belongs to this name. */
    static final String CLIENT_NAME = "Jungey TV";

    /** Pressing the same button again sooner than this, the TV can take two presses as one. */
    private static final long KEY_GAP_MS = 140;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode CLOSED = MAPPER.createObjectNode().put("event", "jungey.closed");

    private final WebSocket socket;
    private final BlockingQueue<JsonNode> events;
    private final Listener listener;
    private volatile boolean open = true;

    private Remote(WebSocket socket, BlockingQueue<JsonNode> events, Listener listener) {
        this.socket = socket;
        this.events = events;
        this.listener = listener;
    }

    /** An installed app, as the TV lists it. */
    public record App(String id, String name, int type) {
    }

    /**
     * Connect and wait until the TV lets us in. Without a token the TV asks whoever is in
     * front of it, which is why pairing waits much longer than an ordinary connection.
     */
    static Remote open(TvSettings settings, Duration allowFor) throws TvException {
        if (settings.host == null) throw new TvException(TvException.Problem.NOT_FOUND, "No TV is set up yet.");
        String name = Base64.getEncoder().encodeToString(CLIENT_NAME.getBytes(StandardCharsets.UTF_8));
        String uri = "wss://" + settings.host + ":8002/api/v2/channels/samsung.remote.control?name=" + name
                + (settings.paired() ? "&token=" + settings.token : "");

        PinnedTrust trust = PinnedTrust.forPin(settings.certSha256);
        HttpClient client = HttpClient.newBuilder()
                .sslContext(trust.context())
                .connectTimeout(Duration.ofSeconds(4))
                .build();

        BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();
        Listener listener = new Listener(events);
        WebSocket socket;
        try {
            socket = client.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .buildAsync(URI.create(uri), listener)
                    .get(8, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw connectFailure(e.getCause() == null ? e : e.getCause());
        } catch (TimeoutException e) {
            throw new TvException(TvException.Problem.UNREACHABLE, "The TV is not answering.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TvException(TvException.Problem.FAILED, "Interrupted.");
        }

        Remote remote = new Remote(socket, events, listener);
        JsonNode reply = remote.await(e -> {
            String ev = e.path("event").asText();
            return ev.equals("ms.channel.connect") || ev.equals("ms.channel.unauthorized")
                    || ev.equals("ms.channel.timeOut") || ev.equals(CLOSED.path("event").asText());
        }, allowFor);

        String event = reply == null ? "" : reply.path("event").asText();
        if (!event.equals("ms.channel.connect")) {
            remote.close();
            if (event.equals("ms.channel.unauthorized")) {
                forget(settings);
                throw new TvException(TvException.Problem.DENIED,
                        "The TV refused. Pair again, and press Allow when the TV asks.");
            }
            if (event.equals("ms.channel.timeOut") || reply == null) {
                throw new TvException(TvException.Problem.NOT_PAIRED,
                        "Nobody pressed Allow on the TV in time. Pair again and accept the message on the TV.");
            }
            throw new TvException(TvException.Problem.UNREACHABLE, "The TV closed the connection.");
        }

        // Let in: keep the token the TV hands out, and remember whose certificate this was.
        String token = reply.path("data").path("token").asText("");
        boolean changed = false;
        if (!token.isBlank() && !token.equals(settings.token)) {
            settings.token = token;
            changed = true;
        }
        if (settings.certSha256 == null && trust.seen() != null) {
            settings.certSha256 = trust.seen();
            changed = true;
        }
        if (changed) settings.save();
        return remote;
    }

    private static void forget(TvSettings settings) {
        if (settings.token != null) {
            settings.token = null;
            settings.save();
        }
    }

    private static TvException connectFailure(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String msg = root.getMessage() + " " + e.getMessage();
        if (msg.contains("certificate changed")) {
            return new TvException(TvException.Problem.CERT_CHANGED,
                    "Something other than your TV answered at its address. If you replaced or reset the TV, "
                            + "run jungey-tv pair --reset.", e);
        }
        if (e instanceof SSLHandshakeException) {
            return new TvException(TvException.Problem.UNREACHABLE, "The TV would not open a secure connection.", e);
        }
        if (e instanceof ConnectException || root instanceof ConnectException || e instanceof HttpTimeoutException) {
            return new TvException(TvException.Problem.UNREACHABLE, "The TV is not answering. It may be off.", e);
        }
        return new TvException(TvException.Problem.UNREACHABLE, "Could not reach the TV: " + root.getMessage(), e);
    }

    public boolean isOpen() {
        return open && !socket.isOutputClosed() && !socket.isInputClosed();
    }

    /** Press a button, by the TV's own key name, e.g. KEY_VOLUP. */
    public void key(String code, int times) throws TvException {
        for (int i = 0; i < Math.max(1, times); i++) {
            if (i > 0) pause(KEY_GAP_MS);
            ObjectNode msg = MAPPER.createObjectNode().put("method", "ms.remote.control");
            msg.putObject("params")
                    .put("Cmd", "Click")
                    .put("DataOfCmd", code)
                    .put("Option", "false")
                    .put("TypeOfRemote", "SendRemoteKey");
            send(msg);
        }
    }

    /**
     * Type into the TV's on-screen keyboard - whichever app opened it, Netflix's search
     * included - and finish, as pressing Done would. Nothing happens if no keyboard is open.
     */
    public void text(String text) throws TvException {
        ObjectNode msg = MAPPER.createObjectNode().put("method", "ms.remote.control");
        msg.putObject("params")
                .put("Cmd", Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)))
                .put("DataOfCmd", "base64")
                .put("TypeOfRemote", "SendInputString");
        send(msg);
        ObjectNode end = MAPPER.createObjectNode().put("method", "ms.remote.control");
        end.putObject("params").put("TypeOfRemote", "SendInputEnd");
        send(end);
    }

    /** Hear what the TV says of its own accord - "ms.remote.imeStart" when its keyboard opens. */
    public void onEvent(Consumer<String> tap) {
        listener.tap = tap;
    }

    /** Every app installed on the TV. */
    public List<App> apps() throws TvException {
        events.clear();
        ObjectNode msg = MAPPER.createObjectNode().put("method", "ms.channel.emit");
        msg.putObject("params").put("event", "ed.installedApp.get").put("to", "host");
        send(msg);
        JsonNode reply = await(e -> e.path("event").asText().equals("ed.installedApp.get"), Duration.ofSeconds(6));
        if (reply == null) throw new TvException(TvException.Problem.FAILED, "The TV did not list its apps.");

        List<App> apps = new ArrayList<>();
        for (JsonNode a : reply.path("data").path("data")) {
            apps.add(new App(a.path("appId").asText(), a.path("name").asText(), a.path("app_type").asInt(2)));
        }
        return apps;
    }

    /** Open an app, optionally at something inside it (a deep link the app understands). */
    public void launch(App app, String deepLink) throws TvException {
        ObjectNode msg = MAPPER.createObjectNode().put("method", "ms.channel.emit");
        ObjectNode data = msg.putObject("params")
                .put("event", "ed.apps.launch")
                .put("to", "host")
                .putObject("data");
        // Web apps are opened by link; the TV's own (type 4) are started natively.
        data.put("action_type", app.type() == 4 ? "NATIVE_LAUNCH" : "DEEP_LINK");
        data.put("appId", app.id());
        if (deepLink != null && !deepLink.isBlank()) data.put("metaTag", deepLink);
        send(msg);
    }

    private void send(JsonNode msg) throws TvException {
        if (!isOpen()) throw new TvException(TvException.Problem.UNREACHABLE, "The connection to the TV was lost.");
        try {
            socket.sendText(MAPPER.writeValueAsString(msg), true).get(4, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException | IOException e) {
            open = false;
            throw new TvException(TvException.Problem.UNREACHABLE, "The connection to the TV was lost.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TvException(TvException.Problem.FAILED, "Interrupted.");
        }
    }

    /** The next message from the TV that matches, or null if none came in time. */
    JsonNode await(Predicate<JsonNode> wanted, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return null;
                JsonNode e = events.poll(left, TimeUnit.NANOSECONDS);
                if (e == null) return null;
                if (e == CLOSED) {
                    open = false;
                    return wanted.test(e) ? e : null;
                }
                if (wanted.test(e)) return e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (!open) return;
        open = false;
        // A last key sent just before closing can be dropped; give the TV a moment with it.
        pause(150);
        try {
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            socket.abort();
        }
    }

    /** Collects whole messages - the TV may split one across frames - into the queue. */
    private static final class Listener implements WebSocket.Listener {
        private final BlockingQueue<JsonNode> events;
        private final StringBuilder partial = new StringBuilder();
        private volatile Consumer<String> tap;

        Listener(BlockingQueue<JsonNode> events) {
            this.events = events;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                try {
                    JsonNode event = MAPPER.readTree(partial.toString());
                    events.add(event);
                    Consumer<String> t = tap;
                    if (t != null) t.accept(event.path("event").asText(""));
                } catch (IOException e) {
                    // Not JSON; nothing the TV says that matters comes like that.
                }
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            events.add(CLOSED);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            events.add(CLOSED);
        }
    }
}
