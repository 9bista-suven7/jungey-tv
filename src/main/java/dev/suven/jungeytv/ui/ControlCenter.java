package dev.suven.jungeytv.ui;

import dev.suven.jungeytv.tv.Remote;
import dev.suven.jungeytv.tv.SamsungTv;
import dev.suven.jungeytv.tv.ScreenCast;
import dev.suven.jungeytv.tv.TvException;
import dev.suven.jungeytv.tv.TvInfo;
import dev.suven.jungeytv.tv.TvSettings;
import dev.suven.jungeytv.tv.YouTube;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Jungey TV's window: a control centre for the TV, every part of it on one screen as a
 * card - the TV and its power, sound, casting this computer to it, inputs, getting around
 * its menus, and its apps. YouTube is searched from the bar at the top.
 *
 * <p>Every action goes to one worker thread in the order it was made, over a connection
 * kept open while the window is, so it answers as fast as the TV does. The state is read
 * every few seconds, so turning the TV off by its own remote shows here too. The keyboard
 * works: arrows, Enter, Backspace for back, + and - for volume, M to mute, H for home.
 */
public final class ControlCenter extends Application {

    private static final PseudoClass ON = PseudoClass.getPseudoClass("on");
    private static final PseudoClass STANDBY = PseudoClass.getPseudoClass("standby");

    /** Apps worth finding first; the rest follow alphabetically. */
    private static final List<String> FAVOURITES = List.of("youtube", "netflix", "prime video", "disney",
            "spotify", "hulu", "apple tv", "paramount", "peacock", "tubi");

    /** Tiles shown before "All apps" - a TV can have forty. */
    private static final int FIRST_APPS = 9;

    private final TvSettings settings = TvSettings.load();
    private final SamsungTv tv = new SamsungTv(settings);
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> daemon(r, "jungey-tv-actions"));
    private final ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "jungey-tv-watch"));

    // The parts that change.
    private final Label chipText = new Label("Looking…");
    private final HBox chip = new HBox(8);
    private final Button pairButton = new Button("Pair");
    private final StackPane screen = new StackPane();
    private final Label screenText = new Label("");
    private final Label tvName = new Label("Your TV");
    private final Label tvModel = new Label("");
    private final Label tvAddress = new Label("");
    private final StackPane powerSwitch = new StackPane();
    private final Label powerLabel = new Label("Off");
    private final TilePane appTiles = new TilePane(14, 16);
    private final TextField search = new TextField();
    private final SearchSheet sheet = new SearchSheet(new SheetHost());
    private Node page;
    private final Label toast = new Label();
    private final StackPane castScreen = new StackPane();
    private final Label castState = new Label("Not casting");
    private final Button castButton = new Button("Start casting");
    private final StackPane castSoundSwitch = new StackPane();
    private final Timeline castClock = new Timeline(new KeyFrame(Duration.seconds(1), e -> showCastTime()));

    private String power = "off";
    private List<Remote.App> apps = List.of();
    private boolean allApps;
    /** The cast while it is on; only the window's thread touches it. */
    private ScreenCast cast;
    private boolean castStarting;
    private boolean castSound = true;

    /** A YouTube search to show as soon as the window opens - {@code jungey-tv browse WORDS}. */
    private static String startSearch;

    public static void startWith(String words) {
        startSearch = words;
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    @Override
    public void start(Stage stage) {
        GridPane grid = new GridPane();
        grid.setHgap(18);
        grid.setVgap(18);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(100.0 / 3);
            c.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(c);
        }
        grid.add(tvCard(), 0, 0);
        grid.add(soundCard(), 1, 0);
        grid.add(castCard(), 2, 0, 1, 2);
        grid.add(inputsCard(), 0, 1);
        grid.add(navigateCard(), 1, 1);
        grid.add(appsCard(), 0, 2, 3, 1);

        VBox page = new VBox(20, topBar(), grid);
        page.setPadding(new Insets(24, 26, 26, 26));
        page.getStyleClass().add("page");
        this.page = page;

        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("scroller");

        toast.getStyleClass().add("toast");
        toast.setOpacity(0);
        toast.setMouseTransparent(true);
        StackPane root = new StackPane(scroll, sheet, toast);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new Insets(0, 0, 22, 0));
        root.getStyleClass().add("backdrop");

        Scene scene = new Scene(root, 1060, 900);
        scene.getStylesheets().add(getClass().getResource("/styles/tv.css").toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::keyboard);

        for (int size : new int[]{16, 32, 48, 128, 256}) {
            var icon = getClass().getResource("/icons/jungey-tv-" + size + ".png");
            if (icon != null) stage.getIcons().add(new Image(icon.toExternalForm()));
        }
        stage.setTitle("Jungey TV");
        stage.setScene(scene);
        stage.setMinWidth(860);
        stage.setMinHeight(560);
        stage.show();
        page.requestFocus();

        showPower("off");
        showPaired();
        watcher.scheduleWithFixedDelay(this::refresh, 0, 4, TimeUnit.SECONDS);
        if (settings.paired()) loadApps();
        if (startSearch != null && !startSearch.isBlank()) sheet.open(startSearch);
    }

    @Override
    public void stop() {
        castClock.stop();
        if (cast != null) cast.close();
        watcher.shutdownNow();
        worker.shutdownNow();
        tv.close();
    }

    // ------------------------------------------------------------------ top bar

    private Node topBar() {
        ImageView logo = new ImageView(new Image(getClass().getResource("/icons/jungey-tv-128.png").toExternalForm(),
                44, 44, true, true));
        Label title = new Label("Jungey TV");
        title.getStyleClass().add("brand");
        Label subtitle = new Label("CONTROL CENTER");
        subtitle.getStyleClass().add("brand-sub");
        VBox words = new VBox(0, title, subtitle);
        words.setAlignment(Pos.CENTER_LEFT);

        Region dot = new Region();
        dot.getStyleClass().add("chip-dot");
        chip.getChildren().addAll(dot, chipText);
        chip.setAlignment(Pos.CENTER);
        chip.getStyleClass().add("chip");

        pairButton.getStyleClass().add("pair");
        pairButton.setFocusTraversable(false);
        pairButton.setOnAction(e -> run(() -> {
            say("Look at the TV and choose Allow…");
            tv.pair(false);
            Platform.runLater(this::showPaired);
            loadApps();
            return "Paired with the " + settings.spokenName() + ".";
        }));

        search.setPromptText("Search YouTube, or paste a link");
        search.getStyleClass().add("everywhere");
        search.setOnAction(e -> searchYouTube());
        Node mark = youtubeMark();
        mark.setMouseTransparent(true);
        StackPane find = new StackPane(search, mark);
        StackPane.setAlignment(mark, Pos.CENTER_LEFT);
        StackPane.setMargin(mark, new Insets(0, 0, 0, 14));
        find.setMaxWidth(420);
        find.setPrefWidth(420);
        HBox.setHgrow(find, Priority.SOMETIMES);

        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Region grow2 = new Region();
        HBox.setHgrow(grow2, Priority.ALWAYS);
        HBox bar = new HBox(14, logo, words, grow, find, grow2, pairButton, chip);
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    // ------------------------------------------------------------------ cards

    private Node tvCard() {
        screenText.getStyleClass().add("screen-text");
        Region glare = new Region();
        glare.getStyleClass().add("screen-glare");
        glare.setMouseTransparent(true);
        screen.getChildren().addAll(glare, screenText);
        screen.getStyleClass().add("screen");
        screen.setMinHeight(118);
        screen.setPrefHeight(118);
        Region stand = new Region();
        stand.getStyleClass().add("stand");
        stand.setMaxWidth(70);
        stand.setPrefHeight(8);
        VBox set = new VBox(0, screen, stand);
        set.setAlignment(Pos.TOP_CENTER);

        tvName.getStyleClass().add("tv-name");
        tvModel.getStyleClass().add("muted");
        tvAddress.getStyleClass().add("muted");

        Region knob = new Region();
        knob.getStyleClass().add("knob");
        StackPane.setAlignment(knob, Pos.CENTER_LEFT);
        powerSwitch.getChildren().add(knob);
        powerSwitch.getStyleClass().add("switch");
        powerSwitch.setMaxSize(58, 32);
        powerSwitch.setMinSize(58, 32);
        powerSwitch.setOnMouseClicked(e -> run(() -> {
            if (power.equals("on")) {
                tv.turnOff();
                Platform.runLater(() -> showPower("standby"));
                return "TV off.";
            }
            say("Waking the TV…");
            tv.turnOn();
            Platform.runLater(() -> showPower("on"));
            return "The TV is on.";
        }));
        Tooltip.install(powerSwitch, new Tooltip("Turn the TV on or off"));
        powerLabel.getStyleClass().add("power-label");
        powerLabel.setMinWidth(Region.USE_PREF_SIZE);
        VBox toggle = new VBox(4, powerSwitch, powerLabel);
        toggle.setAlignment(Pos.CENTER);
        toggle.setMinWidth(Region.USE_PREF_SIZE);

        VBox names = new VBox(2, tvName, tvModel, tvAddress);
        names.setMinWidth(0);
        HBox.setHgrow(names, Priority.ALWAYS);
        HBox row = new HBox(12, names, toggle);
        row.setAlignment(Pos.CENTER_LEFT);

        return card("Television", Icons.line(Icons.SOURCE, 16), "accent-cyan", new VBox(16, set, row));
    }

    private Node soundCard() {
        Button down = roundKey(Icons.line(Icons.MINUS, 26), "volume-down", "Volume down", "big-round");
        Button up = roundKey(Icons.line(Icons.PLUS, 26), "volume-up", "Volume up", "big-round");
        Label vol = new Label("VOLUME");
        vol.getStyleClass().add("eyebrow");
        HBox steppers = new HBox(18, down, vol, up);
        steppers.setAlignment(Pos.CENTER);

        Button mute = action(Icons.mute(20), "Mute", "tile-wide", () -> {
            tv.press("mute", 1);
            return "Pressed mute.";
        });
        Button fiveDown = action(null, "−5", "tile-wide", () -> {
            tv.press("volume-down", 5);
            return "Volume down 5.";
        });
        Button fiveUp = action(null, "+5", "tile-wide", () -> {
            tv.press("volume-up", 5);
            return "Volume up 5.";
        });
        HBox row = grow(new HBox(10, fiveDown, mute, fiveUp));
        return card("Sound", Icons.solid(Icons.SPEAKER, 16), "accent-violet", new VBox(22, steppers, row));
    }

    /** This computer on the TV: its screen, and its sound unless that is switched off. */
    private Node castCard() {
        Node glyph = Icons.line(Icons.CAST, 40);
        glyph.getStyleClass().add("cast-glyph");
        castState.getStyleClass().add("cast-state");
        VBox middle = new VBox(10, glyph, castState);
        middle.setAlignment(Pos.CENTER);
        Region glare = new Region();
        glare.getStyleClass().add("screen-glare");
        glare.setMouseTransparent(true);
        castScreen.getChildren().addAll(glare, middle);
        castScreen.getStyleClass().addAll("screen", "cast-screen");
        castScreen.setMinHeight(170);
        castScreen.setPrefHeight(170);
        castClock.setCycleCount(Animation.INDEFINITE);

        Label about = new Label("This computer's screen and sound on the TV, like an HDMI cable without the cable.");
        about.getStyleClass().add("cast-about");
        about.setWrapText(true);

        Region knob = new Region();
        knob.getStyleClass().add("knob");
        castSoundSwitch.getChildren().add(knob);
        castSoundSwitch.getStyleClass().add("switch");
        castSoundSwitch.setMaxSize(58, 32);
        castSoundSwitch.setMinSize(58, 32);
        castSoundSwitch.setOnMouseClicked(e -> {
            if (cast != null || castStarting) return;
            castSound = !castSound;
            showCastSound();
        });
        Tooltip.install(castSoundSwitch, new Tooltip("On: the sound plays on the TV only. Off: on this computer too, a moment ahead."));
        Label soundLabel = new Label("Sound on the TV");
        soundLabel.getStyleClass().add("cast-option");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox sound = new HBox(12, soundLabel, gap, castSoundSwitch);
        sound.setAlignment(Pos.CENTER_LEFT);
        showCastSound();

        castButton.setGraphic(Icons.line(Icons.CAST, 17));
        castButton.getStyleClass().add("cast-button");
        castButton.setFocusTraversable(false);
        castButton.setMaxWidth(Double.MAX_VALUE);
        castButton.setOnAction(e -> toggleCast());

        Label tip = new Label("Playing a film in the browser? Press F11 for full screen, and the TV shows just the film.");
        tip.getStyleClass().add("muted");
        tip.setWrapText(true);

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        VBox body = new VBox(14, castScreen, about, sound, grow, tip, castButton);
        return card("Cast", Icons.line(Icons.CAST, 16), "accent-red", body);
    }

    private Node inputsCard() {
        TilePane ports = new TilePane(10, 10);
        ports.setPrefColumns(2);
        for (int i = 1; i <= 4; i++) {
            Button b = action(Icons.line(Icons.SOURCE, 18), "HDMI " + i, "port", pressing("hdmi" + i, "Switched to HDMI " + i + "."));
            b.setMaxWidth(Double.MAX_VALUE);
            ports.getChildren().add(b);
        }
        Button source = action(Icons.line(Icons.GUIDE, 18), "All inputs", "tile-wide", pressing("source", "Showing the inputs."));
        source.setMaxWidth(Double.MAX_VALUE);
        return card("Inputs", Icons.line(Icons.SOURCE, 16), "accent-amber", new VBox(12, ports, source));
    }

    private Node navigateCard() {
        GridPane pad = new GridPane();
        pad.setHgap(8);
        pad.setVgap(8);
        pad.setAlignment(Pos.CENTER);
        pad.add(key(Icons.line(Icons.UP, 22), "up", "Up"), 1, 0);
        pad.add(key(Icons.line(Icons.LEFT, 22), "left", "Left"), 0, 1);
        Button ok = key(null, "ok", "OK");
        ok.setText("OK");
        ok.getStyleClass().add("ok");
        pad.add(ok, 1, 1);
        pad.add(key(Icons.line(Icons.RIGHT, 22), "right", "Right"), 2, 1);
        pad.add(key(Icons.line(Icons.DOWN, 22), "down", "Down"), 1, 2);
        pad.getStyleClass().add("pad");

        Button back = action(Icons.line(Icons.BACK, 18), "Back", "tile-wide", pressing("back", null));
        Button home = action(Icons.line(Icons.HOME, 18), "Home", "tile-wide", pressing("home", null));
        HBox row = grow(new HBox(10, back, home));

        HBox transport = grow(new HBox(10,
                key(Icons.solid(Icons.REWIND, 18), "rewind", "Rewind"),
                key(Icons.solid(Icons.PLAY, 18), "play", "Play"),
                key(Icons.solid(Icons.PAUSE, 18), "pause", "Pause"),
                key(Icons.solid(Icons.FORWARD, 18), "forward", "Fast forward")));
        return card("Navigate", Icons.line(Icons.UP, 16), "accent-cyan", new VBox(14, pad, row, transport));
    }

    private Node appsCard() {
        appTiles.setPrefColumns(10);
        appTiles.setTileAlignment(Pos.TOP_CENTER);
        Label hint = new Label("Pair with the TV to see its apps.");
        hint.getStyleClass().add("muted");
        appTiles.getChildren().add(hint);
        return card("Apps", Icons.solid(Icons.GRID, 16), "accent-green", appTiles);
    }

    // ------------------------------------------------------------------ building blocks

    private static Node card(String title, Node icon, String accent, Node body) {
        StackPane badge = new StackPane(icon);
        badge.getStyleClass().addAll("badge", accent);
        Label name = new Label(title);
        name.getStyleClass().add("card-title");
        HBox head = new HBox(10, badge, name);
        head.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(16, head, body);
        VBox.setVgrow(body, Priority.ALWAYS);
        card.getStyleClass().addAll("card", accent);
        card.setMaxHeight(Double.MAX_VALUE);
        GridPane.setFillHeight(card, true);
        return card;
    }

    private static Node youtubeMark() {
        StackPane mark = new StackPane(Icons.solid(Icons.PLAY, 10));
        mark.getStyleClass().add("yt-mark");
        return mark;
    }

    private static HBox grow(HBox row) {
        for (Node n : row.getChildren()) {
            if (n instanceof Region r) {
                r.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(r, Priority.ALWAYS);
            }
        }
        return row;
    }

    private interface TvAction {
        String run() throws TvException;
    }

    private TvAction pressing(String button, String done) {
        return () -> {
            tv.press(button, 1);
            return done;
        };
    }

    /** A plain key on the TV: no message, it is seen on the TV. */
    private Button key(Node icon, String button, String tip) {
        Button b = action(icon, null, "key", pressing(button, null));
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    private Button roundKey(Node icon, String button, String tip, String style) {
        Button b = action(icon, null, style, pressing(button, null));
        b.setTooltip(new Tooltip(tip));
        return b;
    }

    private Button action(Node icon, String text, String style, TvAction action) {
        Button b = new Button(text == null ? "" : text);
        if (icon != null) b.setGraphic(icon);
        b.getStyleClass().add(style);
        b.setFocusTraversable(false);
        b.setOnAction(e -> run(action));
        return b;
    }

    // ------------------------------------------------------------------ doing things

    private void run(TvAction action) {
        worker.submit(() -> {
            try {
                String done = action.run();
                if (done != null) say(done);
            } catch (TvException e) {
                fail(e);
            } catch (RuntimeException e) {
                fail(new TvException(TvException.Problem.FAILED, String.valueOf(e.getMessage()), e));
            }
        });
    }

    /** A link plays at once; words open the search, with YouTube's results to choose from. */
    private void searchYouTube() {
        String words = search.getText() == null ? "" : search.getText().trim();
        if (words.isEmpty()) {
            say("Type something to search YouTube for first.");
            return;
        }
        if (YouTube.idFromLink(words).isPresent()) {
            playVideo(YouTube.Video.linked(YouTube.idFromLink(words).get()));
        } else {
            sheet.open(words);
        }
        search.clear();
    }

    private void playVideo(YouTube.Video video) {
        run(() -> {
            tv.play(video);
            return video.title() == null ? "Playing it on the TV." : "Playing " + video.title() + ".";
        });
    }

    /** What the search sheet asks of the window. */
    private final class SheetHost implements SearchSheet.Host {
        @Override
        public void play(YouTube.Video video) {
            playVideo(video);
        }

        @Override
        public void press(String button) {
            run(pressing(button, null));
        }

        @Override
        public String country() {
            return settings.country();
        }

        @Override
        public void closed() {
            page.requestFocus();
        }
    }

    // ------------------------------------------------------------------ casting

    /** Which cast the window is showing; an older one's ending is not news. */
    private int castRun;

    private void toggleCast() {
        if (castStarting) return;
        castRun++;
        if (cast != null) {
            ScreenCast ending = cast;
            cast = null;
            showCast();
            run(() -> {
                ending.close();
                return "Stopped casting.";
            });
            return;
        }
        castStarting = true;
        showCast();
        int mine = castRun;
        boolean sound = castSound;
        worker.submit(() -> {
            try {
                ScreenCast started = tv.cast(sound, why -> Platform.runLater(() -> {
                    if (mine != castRun) return;
                    cast = null;
                    showCast();
                    say(why);
                }));
                Platform.runLater(() -> {
                    castStarting = false;
                    cast = started;
                    showCast();
                });
                say("Casting this computer to the TV. It shows there in a few seconds.");
            } catch (TvException e) {
                Platform.runLater(this::castFailed);
                fail(e);
            } catch (RuntimeException e) {
                Platform.runLater(this::castFailed);
                fail(new TvException(TvException.Problem.FAILED, String.valueOf(e.getMessage()), e));
            }
        });
    }

    private void castFailed() {
        castStarting = false;
        showCast();
    }

    private void showCast() {
        boolean on = cast != null;
        castScreen.pseudoClassStateChanged(ON, on);
        castButton.pseudoClassStateChanged(ON, on);
        castButton.setDisable(castStarting);
        castButton.setText(castStarting ? "Starting…" : on ? "Stop casting" : "Start casting");
        castSoundSwitch.setDisable(on || castStarting);
        if (on) {
            castClock.play();
            showCastTime();
        } else {
            castClock.stop();
            castState.setText(castStarting ? "Connecting to the TV…" : "Not casting");
        }
    }

    private void showCastTime() {
        if (cast == null) return;
        long s = cast.elapsed().toSeconds();
        String time = s >= 3600 ? "%d:%02d:%02d".formatted(s / 3600, s / 60 % 60, s % 60) : "%d:%02d".formatted(s / 60, s % 60);
        castState.setText("On the TV  ·  " + time);
    }

    private void showCastSound() {
        StackPane.setAlignment(castSoundSwitch.getChildren().getFirst(), castSound ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        castSoundSwitch.pseudoClassStateChanged(ON, castSound);
    }

    private void loadApps() {
        worker.submit(() -> {
            try {
                List<Remote.App> found = tv.apps().stream()
                        .sorted(Comparator.comparingInt(ControlCenter::rank)
                                .thenComparing(a -> a.name().toLowerCase(Locale.ENGLISH)))
                        .toList();
                Platform.runLater(() -> {
                    apps = found;
                    showApps();
                });
            } catch (TvException e) {
                if (e.problem() != TvException.Problem.OFF && e.problem() != TvException.Problem.UNREACHABLE) fail(e);
            }
        });
    }

    private void showApps() {
        appTiles.getChildren().clear();
        int shown = allApps ? apps.size() : Math.min(FIRST_APPS, apps.size());
        for (Remote.App app : apps.subList(0, shown)) appTiles.getChildren().add(appTile(app));
        if (apps.size() > FIRST_APPS) {
            StackPane face = new StackPane(Icons.solid(Icons.GRID, 26));
            face.getStyleClass().addAll("app-face", "app-more");
            Label name = new Label(allApps ? "Fewer" : "All " + apps.size());
            name.getStyleClass().add("app-name");
            VBox tile = new VBox(8, face, name);
            tile.setAlignment(Pos.TOP_CENTER);
            tile.getStyleClass().add("app");
            tile.setOnMouseClicked(e -> {
                allApps = !allApps;
                showApps();
            });
            appTiles.getChildren().add(tile);
        }
    }

    private Node appTile(Remote.App app) {
        AppTiles.Look look = AppTiles.look(app.name());
        Label mark = new Label(look.mark());
        mark.getStyleClass().add("app-mark");
        if (look.mark().length() > 2) mark.getStyleClass().add("app-mark-word");
        if (look.darkMark()) mark.getStyleClass().add("app-mark-dark");
        StackPane face = new StackPane(mark);
        face.getStyleClass().add("app-face");
        face.setStyle("-fx-background-color: linear-gradient(to bottom right, " + look.from() + ", " + look.to() + ");");

        Label name = new Label(AppTiles.shortName(app.name()));
        name.getStyleClass().add("app-name");
        VBox tile = new VBox(8, face, name);
        tile.setAlignment(Pos.TOP_CENTER);
        tile.getStyleClass().add("app");
        Tooltip.install(tile, new Tooltip("Open " + AppTiles.label(app.name()) + " on the TV"));
        tile.setOnMouseClicked(e -> run(() -> {
            tv.open(app.name());
            return "Opening " + AppTiles.shortName(app.name()) + ".";
        }));
        return tile;
    }

    private static int rank(Remote.App app) {
        String n = app.name().toLowerCase(Locale.ENGLISH);
        for (int i = 0; i < FAVOURITES.size(); i++) {
            if (n.contains(FAVOURITES.get(i)) && !n.contains("kids") && !n.equals("youtube tv")) return i;
        }
        return FAVOURITES.size();
    }

    // ------------------------------------------------------------------ keeping up with the TV

    private void refresh() {
        TvInfo info = settings.host == null ? null : TvInfo.fetch(settings.host);
        if (info == null && settings.host == null) {
            try {
                info = tv.locate();
            } catch (TvException e) {
                Platform.runLater(() -> {
                    showPower("off");
                    chipText.setText("No TV found");
                });
                return;
            }
        }
        String state = info == null ? "off" : info.powerState();
        boolean noApps = apps.isEmpty();
        Platform.runLater(() -> showPower(state));
        if (state.equals("on") && settings.paired() && noApps) loadApps();
    }

    private void showPower(String state) {
        power = state;
        boolean on = state.equals("on");
        boolean standby = state.equals("standby");
        for (Node n : List.of(chip, screen, powerSwitch, powerLabel)) {
            n.pseudoClassStateChanged(ON, on);
            n.pseudoClassStateChanged(STANDBY, standby);
        }
        Node knob = powerSwitch.getChildren().getFirst();
        StackPane.setAlignment(knob, on ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        powerLabel.setText(on ? "On" : "Off");
        chipText.setText(on ? "On" : standby ? "Standby" : "Off");
        screenText.setText(on ? settings.spokenName().toUpperCase(Locale.ENGLISH) : standby ? "STANDBY" : "");

        tvName.setText(settings.spokenName().equals("the TV") ? "Your TV" : settings.spokenName());
        tvModel.setText(settings.model == null ? "" : settings.model);
        tvAddress.setText(settings.host == null ? "Not found yet" : settings.host);
    }

    private void showPaired() {
        boolean paired = settings.paired();
        pairButton.setVisible(!paired);
        pairButton.setManaged(!paired);
    }

    private void say(String text) {
        Platform.runLater(() -> {
            toast.getStyleClass().remove("toast-error");
            flash(text);
        });
    }

    private void fail(TvException e) {
        Platform.runLater(() -> {
            if (!toast.getStyleClass().contains("toast-error")) toast.getStyleClass().add("toast-error");
            flash(e.getMessage());
            if (e.problem() == TvException.Problem.NOT_PAIRED || e.problem() == TvException.Problem.DENIED) {
                pairButton.setVisible(true);
                pairButton.setManaged(true);
            }
        });
    }

    private SequentialTransition showing;

    /** The message rises into view, stays long enough to read, and fades. */
    private void flash(String text) {
        toast.setText(text);
        if (showing != null) showing.stop();
        FadeTransition in = new FadeTransition(Duration.millis(160), toast);
        in.setToValue(1);
        PauseTransition hold = new PauseTransition(Duration.seconds(Math.min(8, 2.5 + text.length() / 25.0)));
        FadeTransition out = new FadeTransition(Duration.millis(600), toast);
        out.setToValue(0);
        showing = new SequentialTransition(in, hold, out);
        showing.play();
    }

    // ------------------------------------------------------------------ keyboard

    private void keyboard(KeyEvent e) {
        boolean typing = e.getTarget() instanceof TextInputControl;
        if (e.getCode() == KeyCode.ESCAPE && sheet.isOpen()) {
            e.consume();
            sheet.close();
            return;
        }
        if (typing) {
            if (e.getCode() == KeyCode.ESCAPE) page.requestFocus();
            return;
        }
        String button = switch (e.getCode()) {
            case UP -> "up";
            case DOWN -> "down";
            case LEFT -> "left";
            case RIGHT -> "right";
            case ENTER -> "ok";
            case BACK_SPACE, ESCAPE -> "back";
            case PLUS, EQUALS, ADD -> "volume-up";
            case MINUS, SUBTRACT -> "volume-down";
            case M -> "mute";
            case H -> "home";
            case PAGE_UP -> "channel-up";
            case PAGE_DOWN -> "channel-down";
            case SPACE -> "play";
            default -> null;
        };
        if (button == null) return;
        e.consume();
        run(pressing(button, null));
    }
}
