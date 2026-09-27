package dev.suven.jungeytv.tv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding videos and putting them on the TV.
 *
 * <p>Searching asks the same service YouTube's own web page does ({@code youtubei/v1/search}),
 * which answers in JSON: twenty results a page, a token for the next page, and the same
 * filters as the website - movies, live, long videos, newest first. No account or API key.
 *
 * <p>Playing goes the way a phone casts: DIAL, the protocol YouTube uses to hand a video to
 * a TV, answered by the TV's YouTube app on port 8080. It needs no token, and starts the
 * app if it is not already open.
 */
public final class YouTube {

    private YouTube() {
    }

    /** The website's search filters, as the {@code sp} value its filter menu sets. */
    public enum Filter {
        ALL("All", null),
        VIDEOS("Videos", "EgIQAQ=="),
        MOVIES("Movies", "EgIQBA=="),
        LIVE("Live", "EgJAAQ=="),
        LONG("20+ min", "EgIYAg=="),
        NEWEST("Newest", "CAI=");

        public final String label;
        final String params;

        Filter(String label, String params) {
            this.label = label;
            this.params = params;
        }

        /** "movies", "Live", "long" - the filter by name, or ALL. */
        public static Filter named(String name) {
            if (name == null) return ALL;
            String n = name.trim().toUpperCase(java.util.Locale.ENGLISH).replace("-", "");
            if (n.equals("20+MIN") || n.equals("LONGER")) return LONG;
            if (n.equals("RECENT") || n.equals("LATEST")) return NEWEST;
            for (Filter f : values()) if (f.name().equals(n)) return f;
            return ALL;
        }
    }

    /**
     * One result. Everything but the ID may be null: a pasted link knows nothing else, and
     * a live stream has no length.
     */
    public record Video(String id, String title, String channel, String length, String views, String age,
                        boolean live, boolean movie, String badge, String detail) {

        public static Video linked(String id) {
            return new Video(id, null, null, null, null, null, false, false, null, null);
        }

        /** 320 by 180. */
        public String thumbnail() {
            return "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg";
        }

        /** 480 by 360. */
        public String largeThumbnail() {
            return "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        }
    }

    /** A page of results, and the token for the next one (null at the end). */
    public record Page(List<Video> videos, String next) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";
    /** What the website says it is. YouTube accepts versions of the web client long after they are new. */
    private static final String CLIENT_VERSION = "2.20250101.00.00";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    /** For the TV, whose web server hangs up on the HTTP/2 upgrade Java offers by default. */
    private static final HttpClient TV = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    /** A video ID, 11 characters of letters, digits, - and _. */
    private static final String ID = "[A-Za-z0-9_-]{11}";
    private static final Pattern LINK = Pattern.compile(
            "(?:youtu\\.be/|youtube\\.com/(?:watch\\?(?:.*&)?v=|shorts/|embed/|live/))(" + ID + ")");

    /** The video a YouTube link points at, if the text is one. */
    public static Optional<String> idFromLink(String text) {
        Matcher m = LINK.matcher(text == null ? "" : text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** The first page of results for these words. */
    public static Page search(String query, Filter filter, String country) throws TvException {
        ObjectNode body = request(country);
        body.put("query", query);
        if (filter != null && filter.params != null) body.put("params", filter.params);
        Page page = parse(post(body));
        // The first page can be mostly shelves - Shorts, "people also watched". Fill it up.
        if (page.videos().size() < 12 && page.next() != null) {
            Page more = more(page.next(), country);
            List<Video> all = new ArrayList<>(page.videos());
            Set<String> seen = new HashSet<>();
            all.forEach(v -> seen.add(v.id()));
            more.videos().stream().filter(v -> seen.add(v.id())).forEach(all::add);
            page = new Page(all, more.next());
        }
        return page;
    }

    /** The page after the one the token came with. */
    public static Page more(String token, String country) throws TvException {
        ObjectNode body = request(country);
        body.put("continuation", token);
        return parse(post(body));
    }

    /** The first video YouTube finds for these words - what "play X" plays. */
    public static Video first(String query, String country) throws TvException {
        List<Video> videos = search(query, Filter.VIDEOS, country).videos();
        if (videos.isEmpty()) {
            throw new TvException(TvException.Problem.NO_MATCH, "YouTube found nothing for \"" + query + "\".");
        }
        return videos.getFirst();
    }

    private static ObjectNode request(String country) {
        ObjectNode body = MAPPER.createObjectNode();
        body.putObject("context").putObject("client")
                .put("clientName", "WEB")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", "en")
                .put("gl", country == null || country.isBlank() ? "US" : country);
        return body;
    }

    private static JsonNode post(ObjectNode body) throws TvException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/128.0 Safari/537.36")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new TvException(TvException.Problem.FAILED, "YouTube search failed (" + res.statusCode() + ").");
            }
            return MAPPER.readTree(res.body());
        } catch (IOException e) {
            throw new TvException(TvException.Problem.FAILED, "Could not reach YouTube to search.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TvException(TvException.Problem.FAILED, "Interrupted.");
        }
    }

    /**
     * Every video and film in an answer, in the order given, once each - wherever it sits,
     * so a change in how YouTube nests its results does not lose them.
     */
    static Page parse(JsonNode root) {
        List<Video> videos = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String[] next = {null};
        walk(root, videos, seen, next);
        return new Page(videos, next[0]);
    }

    private static void walk(JsonNode node, List<Video> videos, Set<String> seen, String[] next) {
        if (node.isArray()) {
            for (JsonNode n : node) walk(n, videos, seen, next);
            return;
        }
        if (!node.isObject()) return;
        var fields = node.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            switch (e.getKey()) {
                case "videoRenderer" -> add(video(e.getValue(), false), videos, seen);
                case "movieRenderer" -> add(video(e.getValue(), true), videos, seen);
                case "continuationItemRenderer" -> {
                    // The page's own "more" comes last; shelves have theirs earlier on.
                    String token = e.getValue().path("continuationEndpoint").path("continuationCommand")
                            .path("token").asText("");
                    if (!token.isBlank()) next[0] = token;
                }
                default -> walk(e.getValue(), videos, seen, next);
            }
        }
    }

    private static void add(Video v, List<Video> videos, Set<String> seen) {
        if (v != null && seen.add(v.id())) videos.add(v);
    }

    private static Video video(JsonNode r, boolean movie) {
        String id = r.path("videoId").asText("");
        if (!id.matches(ID)) return null;
        List<String> badges = new ArrayList<>();
        for (JsonNode b : r.path("badges")) {
            String label = b.path("metadataBadgeRenderer").path("label").asText("");
            if (!label.isBlank()) badges.add(label);
        }
        boolean live = badges.stream().anyMatch(b -> b.equalsIgnoreCase("LIVE"));
        String channel = firstText(r, "ownerText", "longBylineText", "shortBylineText");
        String views = firstText(r, "shortViewCountText", "viewCountText");
        String badge = null;
        String detail = null;
        if (movie) {
            // "Free with ads", or nothing (to rent or buy); ratings and CC are not worth showing.
            badge = badges.stream().filter(b -> b.toLowerCase().contains("free")).findFirst().orElse(null);
            JsonNode top = r.path("topMetadataItems");
            if (top.isArray() && !top.isEmpty()) detail = text(top.get(0));
        }
        return new Video(id, text(r.path("title")), channel, text(r.path("lengthText")), views,
                text(r.path("publishedTimeText")), live, movie, badge, detail);
    }

    private static String firstText(JsonNode r, String... keys) {
        for (String k : keys) {
            String t = text(r.path(k));
            if (t != null) return t;
        }
        return null;
    }

    /** YouTube's text: either {"simpleText": ...} or {"runs": [{"text": ...}, ...]}. */
    private static String text(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.has("simpleText")) return blankToNull(n.get("simpleText").asText());
        if (n.has("runs")) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode run : n.get("runs")) sb.append(run.path("text").asText(""));
            return blankToNull(sb.toString());
        }
        return null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Hand the video to the TV's YouTube app, which opens if it has to. */
    public static void play(String host, String videoId) throws TvException {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://" + host + ":8080/ws/apps/YouTube"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString("v=" + videoId))
                .build();
        try {
            HttpResponse<Void> res = TV.send(req, HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() / 100 != 2) {
                throw new TvException(TvException.Problem.FAILED,
                        "The TV's YouTube app would not take the video (" + res.statusCode() + ").");
            }
        } catch (IOException e) {
            throw new TvException(TvException.Problem.UNREACHABLE, "The TV's YouTube app is not answering.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TvException(TvException.Problem.FAILED, "Interrupted.");
        }
    }
}
