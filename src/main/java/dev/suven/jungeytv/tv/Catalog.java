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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Films and series, and where each one streams: Netflix, Prime Video, Disney+, Hulu and
 * the rest, in the country the TV is in.
 *
 * <p>The listings are JustWatch's, from the same service its website searches with. It
 * has no published API for this, so it may change without notice; a failure here only
 * means the Movies and shows search is empty.
 */
public final class Catalog {

    private Catalog() {
    }

    /** Somewhere a title can be watched. */
    public record Offer(String service, String serviceId, String type, String url) {

        /** Included with the service, or free - rather than rented or bought. */
        public boolean included() {
            return !type.equals("rent") && !type.equals("buy");
        }
    }

    /** A film or series. {@code kind} is "movie" or "show"; offers come best first. */
    public record Title(String id, String kind, String name, int year, String poster, List<Offer> offers) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String API = "https://apis.justwatch.com/graphql";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static final String QUERY = """
            query SearchTitles($country: Country!, $language: Language!, $first: Int!, $filter: TitleFilter) {
              popularTitles(country: $country, first: $first, filter: $filter) {
                edges { node {
                  id objectType
                  content(country: $country, language: $language) { title originalReleaseYear posterUrl }
                  offers(country: $country, platform: WEB) {
                    monetizationType standardWebURL
                    package { clearName technicalName }
                  }
                } }
              }
            }""";

    /** How much better one kind of offer is than another: included first, then free, rent, buy. */
    private static final List<String> RANK = List.of("subscription", "free", "free with ads", "rent", "buy");

    /** Titles matching these words, most popular first. */
    public static List<Title> search(String query, String country) throws TvException {
        ObjectNode body = MAPPER.createObjectNode().put("query", QUERY);
        ObjectNode vars = body.putObject("variables");
        vars.put("country", country == null || country.isBlank() ? "US" : country.toUpperCase(Locale.ENGLISH));
        vars.put("language", "en");
        vars.put("first", 24);
        vars.putObject("filter").put("searchQuery", query);

        HttpRequest req = HttpRequest.newBuilder(URI.create(API))
                .timeout(Duration.ofSeconds(12))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Jungey TV")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        try {
            HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new TvException(TvException.Problem.FAILED, "The movie search failed (" + res.statusCode() + ").");
            }
            return parse(MAPPER.readTree(res.body()));
        } catch (IOException e) {
            throw new TvException(TvException.Problem.FAILED, "Could not reach the movie listings.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TvException(TvException.Problem.FAILED, "Interrupted.");
        }
    }

    static List<Title> parse(JsonNode root) throws TvException {
        JsonNode edges = root.path("data").path("popularTitles").path("edges");
        if (!edges.isArray()) {
            String why = root.path("errors").path(0).path("message").asText("no listings came back");
            throw new TvException(TvException.Problem.FAILED, "The movie search failed: " + why);
        }
        List<Title> titles = new ArrayList<>();
        for (JsonNode edge : edges) {
            JsonNode n = edge.path("node");
            JsonNode c = n.path("content");
            String name = c.path("title").asText("");
            if (name.isBlank()) continue;
            String poster = c.path("posterUrl").asText("");
            titles.add(new Title(
                    n.path("id").asText(""),
                    n.path("objectType").asText("").equalsIgnoreCase("SHOW") ? "show" : "movie",
                    name,
                    c.path("originalReleaseYear").asInt(0),
                    poster.isBlank() ? null : "https://images.justwatch.com"
                            + poster.replace("{profile}", "s332").replace("{format}", "jpg"),
                    offers(n.path("offers"))));
        }
        return titles;
    }

    /** One offer per app - the best kind it has - best first, discs and cinemas left out. */
    private static List<Offer> offers(JsonNode list) {
        Map<String, Offer> best = new LinkedHashMap<>();
        for (JsonNode o : list) {
            String type = switch (o.path("monetizationType").asText("")) {
                case "FLATRATE" -> "subscription";
                case "FREE" -> "free";
                case "ADS", "FAST" -> "free with ads";
                case "RENT" -> "rent";
                case "BUY" -> "buy";
                default -> null;
            };
            JsonNode p = o.path("package");
            String service = p.path("clearName").asText("");
            String id = p.path("technicalName").asText("");
            if (type == null || service.isBlank() || service.matches("(?i).*\\b(dvd|blu-?ray)\\b.*")) continue;
            Offer offer = new Offer(service, id, type, o.path("standardWebURL").asText(""));
            // "Netflix" and "Netflix Standard with Ads" are one app on the TV, so one offer.
            String app = Services.appName(id, service);
            String group = app == null ? id : app;
            Offer had = best.get(group);
            if (had == null || RANK.indexOf(type) < RANK.indexOf(had.type())) best.put(group, offer);
        }
        List<Offer> offers = new ArrayList<>(best.values());
        offers.sort((a, b) -> Integer.compare(RANK.indexOf(a.type()), RANK.indexOf(b.type())));
        return offers;
    }
}
