package dev.suven.jungeytv.ui;

import java.text.Normalizer;
import java.util.Locale;

/**
 * How an app looks on the remote: its colours and a short mark, like the tiles on the
 * TV's own home screen. Well-known apps get their own; the rest get a colour of their
 * own worked out from the name, and its initials.
 */
final class AppTiles {

    private AppTiles() {
    }

    /** Top colour, bottom colour, the mark, and whether the mark is dark (for light tiles). */
    record Look(String from, String to, String mark, boolean darkMark) {
    }

    static Look look(String appName) {
        String n = label(appName).toLowerCase(Locale.ENGLISH);
        if (n.contains("youtube tv")) return new Look("#ff2d2d", "#9b0000", "TV", false);
        if (n.contains("youtube kids")) return new Look("#ff4f4f", "#b30000", "kids", false);
        if (n.contains("youtube")) return new Look("#ff2d2d", "#b30000", "▶", false);
        if (n.contains("netflix")) return new Look("#e50914", "#5c0307", "N", false);
        if (n.contains("prime video")) return new Look("#1fb4ff", "#0f4c9c", "prime", false);
        if (n.contains("amazon music")) return new Look("#25d1da", "#0a5f8a", "music", false);
        if (n.contains("disney+") || n.equals("disney")) return new Look("#1d4fd8", "#0a1a5c", "D+", false);
        if (n.contains("hulu")) return new Look("#3dfc9b", "#10a35a", "hulu", true);
        if (n.contains("spotify")) return new Look("#2fe06d", "#0f7a37", "♫", true);
        if (n.contains("apple tv")) return new Look("#3a3a3c", "#0a0a0a", "tv", false);
        if (n.contains("paramount")) return new Look("#1a73ff", "#002a8f", "P+", false);
        if (n.contains("peacock")) return new Look("#2b2b2b", "#050505", "P", false);
        if (n.contains("tubi")) return new Look("#9b3bff", "#3d0a8f", "tubi", false);
        if (n.contains("tiktok")) return new Look("#1e1e1e", "#000000", "♪", false);
        if (n.contains("espn")) return new Look("#e8212b", "#6d070c", "E", false);
        if (n.contains("sling")) return new Look("#2a8cff", "#0a3a8f", "sling", false);
        if (n.contains("pbs")) return new Look("#2a6cff", "#0c2a7a", "PBS", false);
        if (n.contains("internet")) return new Look("#38e8ff", "#0a5a8f", "www", false);
        if (n.contains("gallery")) return new Look("#ffb347", "#c2410c", "▦", false);
        if (n.contains("directv")) return new Look("#2aa9ff", "#07408a", "DTV", false);

        // Anything else: a colour of its own, the same every time, and its initials.
        int hue = Math.floorMod(n.hashCode(), 360);
        return new Look(hsb(hue, 70, 48), hsb((hue + 25) % 360, 75, 22), initials(label(appName)), false);
    }

    /** App names as the TV spells them can hold full-width signs the font has no glyph for. */
    static String label(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).trim();
    }

    /** A short name for under the tile: "Tubi - Free Movies & TV" is just "Tubi". */
    static String shortName(String name) {
        String s = label(name).split("\\s+[-:|]\\s+")[0];
        return s.length() > 18 ? s.substring(0, 17).trim() + "…" : s;
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String word : shortName(name).split("[\\s-]+")) {
            if (!word.isEmpty() && Character.isLetterOrDigit(word.charAt(0))) sb.append(Character.toUpperCase(word.charAt(0)));
            if (sb.length() == 2) break;
        }
        return sb.isEmpty() ? "•" : sb.toString();
    }

    private static String hsb(int h, int s, int b) {
        return "hsb(" + h + "," + s + "%," + b + "%)";
    }
}
