package dev.suven.jungeytv.tv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Where this computer's sound goes while it is cast. With an HDMI cable the sound moves to
 * the TV, and so it does here: a silent output is made for the length of the cast, every
 * program is moved to it, and what it would have played is sent to the TV with the picture.
 * The TV plays a moment behind the screen, so sound left on the speakers too would echo.
 *
 * <p>Uses {@code pactl}, which PulseAudio and PipeWire both answer to.
 */
final class CastSound {

    static final String SINK = "jungey_tv_cast";

    /** The capture ffmpeg records: the silent output's monitor, or the speakers' own. */
    final String source;
    private final String previous;
    private final String module;

    private CastSound(String source, String previous, String module) {
        this.source = source;
        this.previous = previous;
        this.module = module;
    }

    /** Move the sound to the cast. Null when there is no sound server to ask. */
    static CastSound toTv() {
        tidyUp();
        String previous = pactl("get-default-sink");
        if (previous == null) return null;
        String module = pactl("load-module", "module-null-sink", "sink_name=" + SINK,
                "sink_properties=device.description=Jungey-TV-cast");
        if (module == null) return null;
        pactl("set-default-sink", SINK);
        moveEverythingTo(SINK);
        return new CastSound(SINK + ".monitor", previous, module);
    }

    /** Leave the sound on the speakers, and send the TV a copy of it. */
    static CastSound alsoOnTv() {
        String speakers = pactl("get-default-sink");
        return speakers == null ? null : new CastSound(speakers + ".monitor", null, null);
    }

    /** Put the sound back where it was. */
    void restore() {
        if (module == null) return;
        if (previous != null && !previous.equals(SINK)) {
            pactl("set-default-sink", previous);
            moveEverythingTo(previous);
        }
        pactl("unload-module", module);
    }

    /** A cast that ended without putting things back - the window was killed - is undone. */
    static void tidyUp() {
        String modules = pactl("list", "short", "modules");
        if (modules == null) return;
        for (String line : modules.split("\n")) {
            String[] cols = line.split("\t");
            if (cols.length > 2 && cols[1].equals("module-null-sink") && cols[2].contains("sink_name=" + SINK)) {
                pactl("unload-module", cols[0]);
            }
        }
    }

    private static void moveEverythingTo(String sink) {
        String inputs = pactl("list", "short", "sink-inputs");
        if (inputs == null) return;
        for (String line : inputs.split("\n")) {
            String id = line.split("\t")[0].trim();
            if (id.matches("\\d+")) pactl("move-sink-input", id, sink);
        }
    }

    /** What pactl printed, trimmed, or null if it is missing or failed. */
    private static String pactl(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("pactl");
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            return p.exitValue() == 0 ? out : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
