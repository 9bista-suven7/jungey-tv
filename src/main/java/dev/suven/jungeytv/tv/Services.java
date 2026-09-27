package dev.suven.jungeytv.tv;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Map.entry;

/**
 * Streaming services and the TV's apps for them: which app plays what the listings call
 * "Amazon Prime Video" or "Netflix Standard with Ads", and how to open that app at a title
 * rather than at its home screen, where the app allows it.
 */
public final class Services {

    private Services() {
    }

    /** JustWatch's name for a service, and what the app is called on the TV. */
    private static final Map<String, String> APP = Map.ofEntries(
            entry("netflix", "netflix"), entry("netflixbasicwithads", "netflix"),
            entry("amazonprime", "prime video"), entry("amazonprimevideowithads", "prime video"),
            entry("amazon", "prime video"), entry("amazonvideo", "prime video"),
            entry("disneyplus", "disney+"), entry("hulu", "hulu"),
            entry("max", "max"), entry("hbomax", "max"), entry("maxamazonchannel", "prime video"),
            entry("peacock", "peacock"), entry("peacocktv", "peacock"), entry("peacockpremium", "peacock"),
            entry("peacockpremiumplus", "peacock"),
            entry("paramountplus", "paramount"), entry("paramountplusessential", "paramount"),
            entry("paramountpluspremium", "paramount"),
            entry("appletvplus", "apple tv"), entry("itunes", "apple tv"), entry("appletv", "apple tv"),
            entry("tubitv", "tubi"), entry("plutotv", "pluto"), entry("freevee", "freevee"),
            entry("amazonfreevee", "freevee"),
            entry("youtube", "youtube"), entry("youtubefree", "youtube"), entry("youtubepremium", "youtube"),
            entry("youtubetv", "youtube tv"),
            entry("vudu", "fandango"), entry("fandangoathome", "fandango"), entry("play", "google play"),
            entry("slingtv", "sling"), entry("directvstream", "directv"), entry("espnplus", "espn"),
            entry("discoveryplus", "discovery"), entry("amcplus", "amc"), entry("starz", "starz"),
            entry("mgmplus", "mgm"), entry("crunchyroll", "crunchyroll"), entry("philo", "philo"),
            entry("plex", "plex"), entry("viaplay", "viaplay"), entry("vix", "vix"), entry("kanopy", "kanopy"));

    private static final Pattern NETFLIX_TITLE = Pattern.compile("netflix\\.com/(?:[a-z-]+/)?title/(\\d+)");

    /**
     * Which app a service is watched in, by its listing name, or null for one that is not a
     * streaming app at all (a shop selling discs, a cable box). Channels sold through Prime
     * Video or Apple TV ("ViX Premium Amazon Channel") play in that app.
     */
    static String appName(String serviceId, String service) {
        String known = APP.get(serviceId);
        if (known != null) return known;
        // Tiers and variants: "peacockpremiumplus", "netflixstandardwithads".
        for (Map.Entry<String, String> e : APP.entrySet()) {
            if (e.getKey().length() >= 5 && serviceId.startsWith(e.getKey())) return e.getValue();
        }
        String both = (serviceId + " " + service).toLowerCase(java.util.Locale.ENGLISH);
        if (both.contains("amazonchannel") || both.contains("amazon channel")) return "prime video";
        if (both.contains("appletvchannel") || both.contains("apple tv channel")) return "apple tv";
        return null;
    }

    /** A streaming service people have heard of, rather than a disc shop or a cable operator. */
    public static boolean known(Catalog.Offer offer) {
        return appName(offer.serviceId(), offer.service()) != null;
    }

    /** The app on the TV that plays this offer, if the TV has one. */
    public static Optional<Remote.App> app(Catalog.Offer offer, List<Remote.App> installed) {
        String known = appName(offer.serviceId(), offer.service());
        if (known != null) return SamsungTv.best(installed, known);
        return SamsungTv.best(installed, offer.service());
    }

    /** The app a spoken service name means - "prime", "disney plus", "netflix". */
    public static Optional<Remote.App> named(String name, List<Remote.App> installed) {
        return SamsungTv.best(installed, name);
    }

    /** Whether an offer belongs to the service a person named. */
    static boolean sameService(Catalog.Offer offer, Remote.App app, List<Remote.App> installed) {
        return app(offer, installed).map(a -> a.id().equals(app.id())).orElse(false);
    }

    /**
     * What to hand an app so it opens at the title - Netflix takes the title's catalogue
     * address - or null when the app is simply opened.
     */
    public static String deepLink(Catalog.Offer offer) {
        if (offer.serviceId().startsWith("netflix")) {
            Matcher m = NETFLIX_TITLE.matcher(offer.url());
            if (m.find()) return "m=http://api-global.netflix.com/catalog/titles/movies/" + m.group(1) + "&source_type=4";
        }
        return null;
    }

    /** A YouTube offer is a video, and plays the way any video does. */
    public static Optional<String> youtubeVideo(Catalog.Offer offer) {
        return offer.serviceId().startsWith("youtube") && !offer.serviceId().equals("youtubetv")
                ? YouTube.idFromLink(offer.url()) : Optional.empty();
    }
}
