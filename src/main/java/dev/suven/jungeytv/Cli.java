package dev.suven.jungeytv;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.suven.jungeytv.tv.Discovery;
import dev.suven.jungeytv.tv.Keys;
import dev.suven.jungeytv.tv.Remote;
import dev.suven.jungeytv.tv.SamsungTv;
import dev.suven.jungeytv.tv.TvException;
import dev.suven.jungeytv.tv.TvInfo;
import dev.suven.jungeytv.tv.TvSettings;
import dev.suven.jungeytv.tv.YouTube;

import java.io.PrintStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * jungey-tv as a command: do one thing to the TV and say how it went. This is how Jungey
 * drives the TV - with --json it gets {"ok":..., "message":...} back, the message worded
 * to be spoken.
 */
public final class Cli {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    static final String HELP = """
            Jungey TV - a remote for a Samsung smart TV.

              jungey-tv                        open the control center
              jungey-tv status                 is the TV on, and is it paired
              jungey-tv find                   look for Samsung TVs on the network
              jungey-tv use HOST               use the TV at this address
              jungey-tv pair [--reset]         ask the TV for permission (press Allow on it)
              jungey-tv on | off
              jungey-tv volume up|down [N]     N presses, 3 if not given
              jungey-tv mute
              jungey-tv channel up|down [N]
              jungey-tv source [hdmi1..hdmi4]
              jungey-tv key BUTTON [N]         home, back, up, down, left, right, ok, play, pause, ...
              jungey-tv apps                   list the TV's apps
              jungey-tv open APP               Netflix, "prime video", YouTube, ...
              jungey-tv youtube WORDS|LINK     play the first video found, or the one linked

            Options: --json for a JSON reply; --tv HOST to use another TV this once.
            """;

    private Cli() {
    }

    /** A finished command: whether it worked, what to say, and anything worth listing. */
    record Outcome(boolean ok, String message, String problem, Object data) {
        static Outcome ok(String message) {
            return new Outcome(true, message, null, null);
        }

        static Outcome ok(String message, Object data) {
            return new Outcome(true, message, null, data);
        }
    }

    public static int run(String[] argv, PrintStream out) {
        List<String> args = new ArrayList<>(Arrays.asList(argv));
        boolean json = args.remove("--json");
        String host = null;
        int at = args.indexOf("--tv");
        if (at >= 0 && at + 1 < args.size()) {
            host = args.get(at + 1);
            args.subList(at, at + 2).clear();
        }

        TvSettings settings = TvSettings.load();
        if (host != null) settings.host = host;

        Outcome outcome;
        try (SamsungTv tv = new SamsungTv(settings)) {
            outcome = dispatch(tv, args);
        } catch (TvException e) {
            outcome = new Outcome(false, e.getMessage(), e.problem().name().toLowerCase(Locale.ENGLISH), null);
        } catch (IllegalArgumentException e) {
            outcome = new Outcome(false, e.getMessage(), "usage", null);
        }

        if (json) {
            ObjectNode node = MAPPER.createObjectNode()
                    .put("ok", outcome.ok())
                    .put("message", outcome.message());
            if (outcome.problem() != null) node.put("problem", outcome.problem());
            if (outcome.data() != null) node.set("data", MAPPER.valueToTree(outcome.data()));
            out.println(node);
        } else {
            out.println(outcome.message());
        }
        return outcome.ok() ? 0 : 1;
    }

    static Outcome dispatch(SamsungTv tv, List<String> args) throws TvException {
        if (args.isEmpty() || args.getFirst().equals("help") || args.getFirst().equals("--help")) {
            return Outcome.ok(HELP.strip());
        }
        String command = args.getFirst().toLowerCase(Locale.ENGLISH);
        List<String> rest = args.subList(1, args.size());
        String name = tv.settings().spokenName();

        return switch (command) {
            case "status" -> status(tv);
            case "find" -> find();
            case "use" -> use(tv, rest);
            case "pair" -> {
                tv.pair(rest.contains("--reset"));
                yield Outcome.ok("Paired with the " + tv.settings().spokenName() + ".");
            }
            case "on" -> {
                tv.turnOn();
                yield Outcome.ok("The TV is on.");
            }
            case "off" -> {
                tv.turnOff();
                yield Outcome.ok("TV off.");
            }
            case "volume" -> {
                String way = word(rest, 0, "up or down");
                int n = count(rest, 1, 3);
                if (!way.equals("up") && !way.equals("down")) throw new IllegalArgumentException("volume up or volume down?");
                tv.press("volume-" + way, n);
                yield Outcome.ok("TV volume " + way + ".");
            }
            case "mute", "unmute" -> {
                tv.press("mute", 1);
                yield Outcome.ok("Pressed mute on the TV.");
            }
            case "channel" -> {
                String way = word(rest, 0, "up or down");
                if (!way.equals("up") && !way.equals("down")) throw new IllegalArgumentException("channel up or channel down?");
                tv.press("channel-" + way, count(rest, 1, 1));
                yield Outcome.ok("Channel " + way + ".");
            }
            case "source", "input" -> {
                String to = rest.isEmpty() ? "source" : String.join("", rest).toLowerCase(Locale.ENGLISH);
                if (Keys.code(to) == null) throw new IllegalArgumentException("No input called " + to + ".");
                tv.press(to, 1);
                yield Outcome.ok(to.equals("source") ? "Showing the TV's inputs." : "Switched the TV to " + to.toUpperCase(Locale.ENGLISH).replace("HDMI", "HDMI ") + ".");
            }
            case "key", "press" -> {
                String button = word(rest, 0, "which button");
                tv.press(button, count(rest, 1, 1));
                yield Outcome.ok("Pressed " + button + ".");
            }
            case "apps" -> {
                List<Remote.App> apps = tv.apps();
                List<String> names = apps.stream().map(Remote.App::name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
                yield Outcome.ok("The " + name + " has " + names.size() + " apps: " + String.join(", ", names) + ".", apps);
            }
            case "open", "launch" -> {
                if (rest.isEmpty()) throw new IllegalArgumentException("Open which app?");
                Remote.App app = tv.open(String.join(" ", rest));
                yield Outcome.ok("Opening " + app.name() + " on the TV.", app);
            }
            case "youtube", "play" -> {
                if (rest.isEmpty()) throw new IllegalArgumentException("Play what?");
                YouTube.Video video = tv.youtube(String.join(" ", rest));
                yield Outcome.ok(video.title() == null ? "Playing it on the TV." : "Playing " + video.title() + " on the TV.", video);
            }
            default -> {
                // A bare button name works too: jungey-tv home, jungey-tv hdmi2.
                if (Keys.code(command) != null && rest.size() <= 1) {
                    tv.press(command, count(rest, 0, 1));
                    yield Outcome.ok("Pressed " + command + ".");
                }
                throw new IllegalArgumentException("I don't know \"" + command + "\". Try jungey-tv help.");
            }
        };
    }

    private static Outcome status(SamsungTv tv) throws TvException {
        String power = tv.power();
        TvSettings s = tv.settings();
        String state = switch (power) {
            case "on" -> "on";
            case "standby" -> "in standby";
            default -> "off or not answering";
        };
        String message = "The " + s.spokenName() + " is " + state + "."
                + (s.paired() ? "" : " It is not paired yet: run jungey-tv pair and press Allow on the TV.");
        ObjectNode data = MAPPER.createObjectNode()
                .put("host", s.host)
                .put("name", s.name)
                .put("model", s.model)
                .put("power", power)
                .put("paired", s.paired());
        return Outcome.ok(message, data);
    }

    private static Outcome find() {
        List<TvInfo> tvs = Discovery.find(Duration.ofSeconds(3));
        if (tvs.isEmpty()) return new Outcome(false, "No Samsung TV answered on this network.", "not_found", null);
        ArrayNode list = MAPPER.createArrayNode();
        StringBuilder message = new StringBuilder("Found " + tvs.size() + (tvs.size() == 1 ? " TV:" : " TVs:"));
        for (TvInfo t : tvs) {
            list.addObject().put("host", t.host()).put("name", t.name()).put("model", t.model()).put("power", t.powerState());
            message.append("\n  ").append(t.host()).append("  ").append(t.name()).append("  ").append(t.model())
                    .append("  (").append(t.powerState()).append(")");
        }
        return Outcome.ok(message.toString(), list);
    }

    private static Outcome use(SamsungTv tv, List<String> rest) throws TvException {
        String host = word(rest, 0, "the TV's address");
        TvInfo info = TvInfo.fetch(host);
        if (info == null || !info.samsung()) {
            throw new TvException(TvException.Problem.NOT_FOUND, "No Samsung TV answered at " + host + ".");
        }
        tv.choose(info);
        return Outcome.ok("Using the " + tv.settings().spokenName() + " at " + host + ". Pair it next.");
    }

    private static String word(List<String> rest, int i, String what) {
        if (rest.size() <= i) throw new IllegalArgumentException("Missing " + what + ".");
        return rest.get(i).toLowerCase(Locale.ENGLISH);
    }

    private static int count(List<String> rest, int i, int fallback) {
        if (rest.size() <= i) return fallback;
        try {
            return Math.max(1, Math.min(50, Integer.parseInt(rest.get(i))));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + rest.get(i) + "\" is not a number.");
        }
    }
}
