package dev.suven.jungeytv.tv;

import java.io.IOException;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Everything Jungey TV can do to the TV, in the words it is asked in: turn it on, turn the
 * volume down, open Netflix, play this video.
 *
 * <p>Holds one connection open between requests, so the remote window's buttons answer at
 * once; a command-line run opens it, does one thing, and {@link #close}s it.
 */
public final class SamsungTv implements AutoCloseable {

    /** How long pairing waits for someone to press Allow on the TV. */
    private static final Duration PAIRING_WAIT = Duration.ofSeconds(30);
    private static final Duration CONNECT_WAIT = Duration.ofSeconds(5);
    /** A TV woken from standby takes this long to be ready for anything. */
    private static final Duration WAKE_WAIT = Duration.ofSeconds(20);

    /** "the office on peacock": the title, and a service name after the last " on ". */
    private static final Pattern ON_SERVICE = Pattern.compile("(?i)^(.+)\\s+on\\s+(?:the\\s+)?(.+?)(?:\\s+app)?$");

    private final TvSettings settings;
    private Remote remote;
    private volatile Consumer<String> tap;
    /** The TV's apps as last listed; they change only when something is installed. */
    private volatile List<Remote.App> installed;

    public SamsungTv(TvSettings settings) {
        this.settings = settings;
    }

    public TvSettings settings() {
        return settings;
    }

    /** Look for a TV if none is set up yet, and keep the first one found. */
    public TvInfo locate() throws TvException {
        if (settings.host != null) {
            TvInfo info = TvInfo.fetch(settings.host);
            if (info != null) remember(info);
            return info;
        }
        List<TvInfo> found = Discovery.find(Duration.ofSeconds(3));
        if (found.isEmpty()) {
            throw new TvException(TvException.Problem.NOT_FOUND,
                    "I could not find a Samsung TV on this network. Is it on, and on the same Wi-Fi?");
        }
        remember(found.getFirst());
        return found.getFirst();
    }

    /** Use this TV from now on. Forgets the permission of the one before. */
    public void choose(TvInfo info) {
        if (!info.host().equals(settings.host)) {
            settings.token = null;
            settings.certSha256 = null;
        }
        settings.host = info.host();
        remember(info);
    }

    private void remember(TvInfo info) {
        boolean changed = !info.host().equals(settings.host)
                || !info.name().equals(settings.name)
                || (info.mac() != null && !info.mac().equals(settings.mac))
                || !info.model().equals(settings.model);
        if (!changed) return;
        settings.host = info.host();
        settings.name = info.name();
        settings.model = info.model();
        if (info.mac() != null) settings.mac = info.mac();
        settings.save();
    }

    /** "on", "standby", or "off" when the TV does not answer at all. */
    public String power() throws TvException {
        TvInfo info = locate();
        return info == null ? "off" : info.powerState();
    }

    /** Ask the TV for permission; someone has to press Allow on it within 30 seconds. */
    public void pair(boolean reset) throws TvException {
        locate();
        close();
        if (reset) {
            settings.token = null;
            settings.certSha256 = null;
            settings.save();
        }
        remote = Remote.open(settings, PAIRING_WAIT);
    }

    public void turnOn() throws TvException {
        String state = power();
        if (state.equals("on")) return;
        if (state.equals("standby")) {
            // Network standby: the remote channel is still listening, and the power key wakes it.
            press("KEY_POWER", 1, true);
        } else {
            if (settings.mac == null) {
                throw new TvException(TvException.Problem.UNREACHABLE,
                        "The TV is off and I do not know its network address to wake it. Turn it on once by hand.");
            }
            try {
                WakeOnLan.send(settings.mac, settings.host);
            } catch (IOException | IllegalArgumentException e) {
                throw new TvException(TvException.Problem.FAILED, "Could not send the wake-up signal.", e);
            }
        }
        long end = System.nanoTime() + WAKE_WAIT.toNanos();
        while (System.nanoTime() < end) {
            sleep(1000);
            TvInfo info = TvInfo.fetch(settings.host);
            if (info != null && info.on()) {
                close();   // the channel from before standby is stale
                return;
            }
        }
        throw new TvException(TvException.Problem.UNREACHABLE,
                "The TV did not wake. Turn on Power On with Mobile in its network settings, under Expert Settings.");
    }

    public void turnOff() throws TvException {
        if (!power().equals("on")) return;
        press("KEY_POWER", 1, true);
        close();
    }

    /** Press a button, by any name {@link Keys} knows. */
    public void press(String button, int times) throws TvException {
        press(button, times, false);
    }

    private void press(String button, int times, boolean evenIfOff) throws TvException {
        String code = Keys.code(button);
        if (code == null) throw new TvException(TvException.Problem.NO_MATCH, "The TV has no " + button + " button.");
        if (!evenIfOff) requireOn();
        withRemote(r -> {
            r.key(code, times);
            return null;
        });
    }

    public List<Remote.App> apps() throws TvException {
        requireOn();
        installed = withRemote(Remote::apps);
        return installed;
    }

    private List<Remote.App> installed() throws TvException {
        List<Remote.App> known = installed;
        if (known != null && !known.isEmpty()) return known;
        installed = withRemote(Remote::apps);
        return installed;
    }

    /** Hear what the TV says of its own accord, such as its keyboard opening. */
    public void onEvent(Consumer<String> tap) {
        this.tap = tap;
        if (remote != null) remote.onEvent(tap);
    }

    /** Open the installed app whose name best matches, turning the TV on first if need be. */
    public Remote.App open(String name) throws TvException {
        turnOn();
        List<Remote.App> apps = installed();
        Remote.App app = best(apps, name).orElseThrow(() -> new TvException(TvException.Problem.NO_MATCH,
                "There is no " + name + " on the TV."));
        withRemote(r -> {
            r.launch(app, null);
            return null;
        });
        return app;
    }

    /** Play a video - a link, or words to search YouTube for - turning the TV on first if need be. */
    public YouTube.Video youtube(String linkOrSearch) throws TvException {
        Optional<String> id = YouTube.idFromLink(linkOrSearch);
        YouTube.Video video = id.isPresent() ? YouTube.Video.linked(id.get())
                : YouTube.first(linkOrSearch, settings.country());
        play(video);
        return video;
    }

    /** Play this video, chosen from a search. */
    public void play(YouTube.Video video) throws TvException {
        turnOn();
        YouTube.play(settings.host, video.id());
    }

    /** What happened when asked to watch something: the title, where, and whether it opened at the title. */
    public record Watching(Catalog.Title title, Catalog.Offer offer, Remote.App app, boolean atTitle) {
    }

    /**
     * Open a title in the app that has it - at the title itself where the app allows (Netflix,
     * YouTube), or at the app's home otherwise.
     */
    public Watching watch(Catalog.Title title, Catalog.Offer offer) throws TvException {
        turnOn();
        List<Remote.App> apps = installed();
        Optional<String> video = Services.youtubeVideo(offer);
        if (video.isPresent()) {
            YouTube.play(settings.host, video.get());
            return new Watching(title, offer, best(apps, "youtube").orElse(null), true);
        }
        Remote.App app = Services.app(offer, apps).orElseThrow(() -> new TvException(TvException.Problem.NO_MATCH,
                "The TV has no " + offer.service() + " app."));
        String link = Services.deepLink(offer);
        withRemote(r -> {
            r.launch(app, link);
            return null;
        });
        return new Watching(title, offer, app, link != null);
    }

    /**
     * Find a film or series and open it: in the service named ("the office on peacock"), or
     * else the first app on the TV that has it included, or else one that rents or sells it.
     */
    public Watching watch(String what) throws TvException {
        return watch(what, 0);
    }

    /** As {@link #watch(String)}, preferring the title released in this year: two series can share a name. */
    public Watching watch(String what, int year) throws TvException {
        turnOn();
        List<Remote.App> apps = installed();
        String query = what.trim();
        Remote.App wanted = null;
        Matcher on = ON_SERVICE.matcher(query);
        if (on.matches()) {
            Optional<Remote.App> app = Services.named(on.group(2), apps);
            if (app.isPresent()) {
                wanted = app.get();
                query = on.group(1).trim();
            }
        }
        List<Catalog.Title> titles = new java.util.ArrayList<>(Catalog.search(query, settings.country()));
        if (titles.isEmpty()) throw new TvException(TvException.Problem.NO_MATCH, "I could not find " + query + ".");
        if (year > 0) titles.sort(Comparator.comparing(t -> t.year() != year));

        // The best match that the TV can play, looking a little past the most popular one.
        for (Catalog.Title t : titles.subList(0, Math.min(5, titles.size()))) {
            Catalog.Offer pick = null;
            for (Catalog.Offer o : t.offers()) {
                boolean playable = Services.app(o, apps).isPresent() || Services.youtubeVideo(o).isPresent();
                if (!playable) continue;
                if (wanted != null && !Services.sameService(o, wanted, apps)) continue;
                pick = o;
                break;
            }
            if (pick != null) return watch(t, pick);
        }
        Catalog.Title top = titles.getFirst();
        String where = top.offers().isEmpty() ? "is not streaming anywhere here"
                : "is only on " + String.join(", ", top.offers().stream().map(Catalog.Offer::service).distinct().limit(3).toList())
                + ", which the TV has no app for";
        throw new TvException(TvException.Problem.NO_MATCH, top.name() + (wanted == null ? " " + where
                : " is not on " + wanted.name()) + ".");
    }

    /** Type into the TV's on-screen keyboard, when an app has it open. */
    public void type(String text) throws TvException {
        requireOn();
        withRemote(r -> {
            r.text(text);
            return null;
        });
    }

    /**
     * The installed app a spoken name means: exact first, then one whose name starts with
     * it, then one with it as a whole word - "prime" finds Prime Video, "disney" finds
     * Disney+, and "max" does not find MagellanTV.
     */
    static Optional<Remote.App> best(List<Remote.App> apps, String name) {
        String want = simple(name);
        if (want.isEmpty()) return Optional.empty();
        return apps.stream()
                .filter(a -> rank(simple(a.name()), want) < 4)
                .min(Comparator.comparingInt(a -> rank(simple(a.name()), want)));
    }

    private static int rank(String n, String want) {
        if (n.equals(want)) return 0;
        if (n.startsWith(want + " ") || n.startsWith(want) && want.length() >= 4) return 1;
        if ((" " + n + " ").contains(" " + want + " ")) return 2;
        if (n.length() >= 4 && (" " + want + " ").contains(" " + n + " ")) return 3;
        return 4;
    }

    private static String simple(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ENGLISH)
                .replace("amazon ", "")
                .replace("+", " plus")
                .replaceAll("[^a-z0-9 ]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private void requireOn() throws TvException {
        String state = power();
        if (state.equals("off")) throw new TvException(TvException.Problem.UNREACHABLE, "The TV is off or not answering.");
        if (state.equals("standby")) throw new TvException(TvException.Problem.OFF, "The TV is in standby.");
    }

    private interface Action<T> {
        T run(Remote remote) throws TvException;
    }

    /** Run over the open connection, reconnecting once if it had dropped. */
    private synchronized <T> T withRemote(Action<T> action) throws TvException {
        if (!settings.paired()) {
            throw new TvException(TvException.Problem.NOT_PAIRED,
                    "Jungey TV is not paired with the TV yet. Pair it, then press Allow on the TV.");
        }
        for (int attempt = 0; ; attempt++) {
            if (remote == null || !remote.isOpen()) {
                remote = Remote.open(settings, CONNECT_WAIT);
                if (tap != null) remote.onEvent(tap);
            }
            try {
                return action.run(remote);
            } catch (TvException e) {
                if (attempt > 0 || e.problem() != TvException.Problem.UNREACHABLE) throw e;
                remote.close();
                remote = null;
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public synchronized void close() {
        if (remote != null) {
            remote.close();
            remote = null;
        }
    }
}
