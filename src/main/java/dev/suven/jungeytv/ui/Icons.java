package dev.suven.jungeytv.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.SVGPath;

/**
 * The remote's symbols, drawn rather than typed: fonts on a Linux desktop cannot be
 * counted on for a power sign or a pause bar, and a missing glyph is a box.
 *
 * <p>Each is drawn in a 24-unit square and scaled; wrapped in a {@link Group} so the scaled
 * size is the one layout sees.
 */
final class Icons {

    private Icons() {
    }

    static final String POWER = "M12 3 V11 M7.05 6.05 A7.5 7.5 0 1 0 16.95 6.05";
    static final String HOME = "M3.5 11 L12 3.5 L20.5 11 M6 9 V20 H18 V9";
    static final String BACK = "M9 5.5 L4 10.5 L9 15.5 M4 10.5 H14.5 A5 5 0 0 1 14.5 20.5 H11";
    static final String SOURCE = "M3 5 H21 V16 H3 Z M8.5 20 H15.5 M12 16 V20";
    static final String PLUS = "M12 5 V19 M5 12 H19";
    static final String MINUS = "M5 12 H19";
    static final String GUIDE = "M4 6.5 H20 M4 12 H20 M4 17.5 H20";
    static final String UP = "M6 15 L12 9 L18 15";
    static final String DOWN = "M6 9 L12 15 L18 9";
    static final String LEFT = "M15 6 L9 12 L15 18";
    static final String RIGHT = "M9 6 L15 12 L9 18";
    static final String MUTE_SLASH = "M16 9.5 L21 14.5 M21 9.5 L16 14.5";

    static final String SPEAKER = "M3.5 9 H7.5 L12.5 5 V19 L7.5 15 H3.5 Z";
    static final String PLAY = "M8 5 L19 12 L8 19 Z";
    static final String PAUSE = "M6.5 5 H10 V19 H6.5 Z M14 5 H17.5 V19 H14 Z";
    static final String REWIND = "M11.5 6 L4 12 L11.5 18 Z M20.5 6 L13 12 L20.5 18 Z";
    static final String FORWARD = "M3.5 6 L11 12 L3.5 18 Z M12.5 6 L20 12 L12.5 18 Z";
    static final String GRID = "M4 4 H10 V10 H4 Z M14 4 H20 V10 H14 Z M4 14 H10 V20 H4 Z M14 14 H20 V20 H14 Z";
    static final String SEARCH = "M17 10.5 A6.5 6.5 0 1 1 4 10.5 A6.5 6.5 0 1 1 17 10.5 Z M15.3 15.3 L20.5 20.5";
    static final String FILM = "M3.5 4.5 H20.5 V19.5 H3.5 Z M7.5 4.5 V19.5 M16.5 4.5 V19.5 M3.5 9.5 H7.5 M3.5 14.5 H7.5"
            + " M16.5 9.5 H20.5 M16.5 14.5 H20.5";
    static final String KEYBOARD = "M2.5 6 H21.5 V18 H2.5 Z M6 9.5 H6.01 M9.5 9.5 H9.51 M13 9.5 H13.01 M16.5 9.5 H16.51"
            + " M6 12.5 H6.01 M9.5 12.5 H9.51 M13 12.5 H13.01 M16.5 12.5 H16.51 M7.5 15.3 H16.5";

    /** A line drawing: power, home, arrows. */
    static Node line(String path, double size) {
        return make(path, size, "icon-line");
    }

    /** A solid shape: play, pause, the speaker. */
    static Node solid(String path, double size) {
        return make(path, size, "icon-solid");
    }

    /** The speaker with a cross through it. */
    static Node mute(double size) {
        SVGPath body = svg(Icons.SPEAKER, "icon-solid");
        SVGPath slash = svg(Icons.MUTE_SLASH, "icon-line");
        Group g = new Group(body, slash);
        g.setScaleX(size / 24);
        g.setScaleY(size / 24);
        return new Group(g);
    }

    private static Node make(String path, double size, String style) {
        SVGPath p = svg(path, style);
        p.setScaleX(size / 24);
        p.setScaleY(size / 24);
        return new Group(p);
    }

    private static SVGPath svg(String path, String style) {
        SVGPath p = new SVGPath();
        p.setContent(path);
        p.getStyleClass().add(style);
        return p;
    }
}
