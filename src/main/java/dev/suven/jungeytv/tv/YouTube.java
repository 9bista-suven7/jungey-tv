package dev.suven.jungeytv.tv;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finding a video and putting it on the TV.
 *
 * <p>Playing goes the way a phone casts: DIAL, the protocol YouTube uses to hand a video to
 * a TV, answered by the TV's YouTube app on port 8080. It needs no token, and starts the
 * app if it is not already open. Finding the video is YouTube's own search page, read for
 * the first video in it - no account or API key.
 */
public final class YouTube {

    private YouTube() {
    }

    public record Video(String id, String title) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
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
    private static final Pattern RESULT = Pattern.compile(
            "\"videoRenderer\":\\{\"videoId\":\"(" + ID + ")\".*?\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);

    /** The video a YouTube link points at, if the text is one. */
    public static Optional<String> idFromLink(String text) {
        Matcher m = LINK.matcher(text == null ? "" : text);
        return m.find() ? Optional.of(m.group(1)) : Optional.empty();
    }

    /** The first video YouTube finds for these words. */
    public static Video search(String query) throws TvException {
        // sp=EgIQAQ%3D%3D limits the results to videos: no channels, playlists or shelves.
        String url = "https://www.youtube.com/results?search_query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&sp=EgIQAQ%253D%253D";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/128.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.8")
                // Skips the cookie-consent page some regions get before the results.
                .header("Cookie", "SOCS=CAI; CONSENT=YES+1")
                .GET().build();
        String html;
        try {
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new TvException(TvException.Problem.FAILED, "YouTube search failed (" + res.statusCode() + ").");
            }
            html = res.body();
        } catch (IOException e) {
            throw new TvException(TvException.Problem.FAILED, "Could not reach YouTube to search.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TvException(TvException.Problem.FAILED, "Interrupted.");
        }
        return parse(html).orElseThrow(() ->
                new TvException(TvException.Problem.NO_MATCH, "YouTube found nothing for \"" + query + "\"."));
    }

    /** The first video in a results page. */
    static Optional<Video> parse(String html) {
        Matcher m = RESULT.matcher(html);
        if (!m.find()) return Optional.empty();
        String title;
        try {
            title = MAPPER.readValue("\"" + m.group(2) + "\"", String.class);
        } catch (IOException e) {
            title = m.group(2);
        }
        return Optional.of(new Video(m.group(1), title));
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
