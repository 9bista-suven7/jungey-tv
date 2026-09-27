package dev.suven.jungeytv;

import dev.suven.jungeytv.ui.ControlCenter;

/**
 * jungey-tv on its own opens the control center, and {@code browse WORDS} opens it at a search;
 * with any other command it does that and exits.
 *
 * <p>Not an {@code Application} itself: a class that extends it cannot be the main class of
 * a jar with JavaFX on the classpath, and a command should not start the toolkit anyway.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && args[0].equals("--window"))) {
            ControlCenter.main(new String[0]);
            return;
        }
        // browse [youtube] WORDS: the window, opened at the results of a search.
        if (args[0].equals("browse") && args.length > 1) {
            boolean videos = args[1].equalsIgnoreCase("youtube") && args.length > 2;
            ControlCenter.startWith(String.join(" ", java.util.Arrays.copyOfRange(args, videos ? 2 : 1, args.length)), videos);
            ControlCenter.main(new String[0]);
            return;
        }
        System.exit(Cli.run(args, System.out));
    }
}
