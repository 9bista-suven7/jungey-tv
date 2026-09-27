package dev.suven.jungeytv;

import dev.suven.jungeytv.ui.ControlCenter;

/**
 * jungey-tv on its own opens the control center; with a command it does that and exits.
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
        System.exit(Cli.run(args, System.out));
    }
}
