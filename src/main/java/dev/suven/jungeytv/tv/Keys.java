package dev.suven.jungeytv.tv;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Remote buttons by the names people use, and the key names the TV wants for them. */
public final class Keys {

    private Keys() {
    }

    private static final Map<String, String> BY_NAME = new LinkedHashMap<>();

    static {
        put("KEY_POWER", "power");
        put("KEY_VOLUP", "volume-up", "louder");
        put("KEY_VOLDOWN", "volume-down", "quieter");
        put("KEY_MUTE", "mute", "unmute");
        put("KEY_CHUP", "channel-up", "next-channel");
        put("KEY_CHDOWN", "channel-down", "previous-channel");
        put("KEY_UP", "up");
        put("KEY_DOWN", "down");
        put("KEY_LEFT", "left");
        put("KEY_RIGHT", "right");
        put("KEY_ENTER", "ok", "okay", "enter", "select");
        put("KEY_RETURN", "back", "return");
        put("KEY_HOME", "home");
        put("KEY_MENU", "menu", "settings");
        put("KEY_SOURCE", "source", "input");
        put("KEY_HDMI", "hdmi");
        put("KEY_HDMI1", "hdmi1");
        put("KEY_HDMI2", "hdmi2");
        put("KEY_HDMI3", "hdmi3");
        put("KEY_HDMI4", "hdmi4");
        put("KEY_PLAY", "play", "resume");
        put("KEY_PAUSE", "pause");
        put("KEY_STOP", "stop");
        put("KEY_REWIND", "rewind");
        put("KEY_FF", "forward", "fast-forward");
        put("KEY_INFO", "info");
        put("KEY_GUIDE", "guide");
        put("KEY_EXIT", "exit");
        for (int d = 0; d <= 9; d++) put("KEY_" + d, String.valueOf(d));
    }

    private static void put(String code, String... names) {
        for (String n : names) BY_NAME.put(n, code);
    }

    /**
     * The TV's key name for a button: "volume up", "Volume-Up" and "volume_up" all give
     * KEY_VOLUP, "hdmi 2" gives KEY_HDMI2. A name already in the TV's form passes through.
     *
     * @return the key name, or null for a button there is no such thing as
     */
    public static String code(String name) {
        if (name == null) return null;
        String raw = name.trim();
        if (raw.matches("KEY_[A-Z0-9_]+")) return raw;
        String n = raw.toLowerCase(Locale.ENGLISH)
                .replaceAll("[\\s_]+", "-")
                .replaceAll("^hdmi-(\\d)$", "hdmi$1");
        return BY_NAME.get(n);
    }

    /** Every button name that {@link #code} understands, for help. */
    public static Iterable<String> names() {
        return BY_NAME.keySet();
    }
}
