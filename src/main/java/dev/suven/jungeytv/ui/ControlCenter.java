package dev.suven.jungeytv.ui;

import dev.suven.jungeytv.tv.Catalog;
import dev.suven.jungeytv.tv.Remote;
import dev.suven.jungeytv.tv.SamsungTv;
import dev.suven.jungeytv.tv.TvException;
import dev.suven.jungeytv.tv.TvInfo;
import dev.suven.jungeytv.tv.TvSettings;
import dev.suven.jungeytv.tv.YouTube;
import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
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
import javafx.scene.shape.Rectangle;
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
 * card - the TV and its power, sound, what is playing, inputs, getting around its menus,
 * and its apps.
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
    private final TextField everywhere = new TextField();
    private final TextField typing = new TextField();
    private final SearchSheet sheet = new SearchSheet(new SheetHost());
    private final Label nowWhere = new Label("");
    private Node page;
    private final ImageView thumbnail = new ImageView();
    private final Label nowTitle = new Label("Nothing yet - search for anything below.");
    private final Label toast = new Label();

    private String power = "off";
    private List<Remote.App> apps = List.of();
    private boolean allApps;

    /** A search to show as soon as the window opens - {@code jungey-tv browse WORDS}. */
    private static String startSearch;
    private static boolean startWithVideos;

    public static void startWith(String words, boolean videos) {
        startSearch = words;
        startWithVideos = videos;
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
        grid.add(youtubeCard(), 2, 0, 1, 2);
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
        // The TV opened its keyboard - a search box in Netflix, say: type it here instead.
        tv.onEvent(event -> {
            if (event.startsWith("ms.remote.imeStart")) {
                Platform.runLater(() -> {
                    typing.requestFocus();
                    say("The TV is asking for text. Type it here and press Enter.");
                });
            }
        });
        watcher.scheduleWithFixedDelay(this::refresh, 0, 4, TimeUnit.SECONDS);
        if (settings.paired()) loadApps();
        if (startSearch != null && !startSearch.isBlank()) {
            sheet.open(startSearch, startWithVideos ? SearchSheet.Tab.YOUTUBE : SearchSheet.Tab.STREAMING);
        }
    }

    @Override
    public void stop() {
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

        everywhere.setPromptText("Search YouTube, movies and shows");
        everywhere.getStyleClass().add("everywhere");
        everywhere.setOnAction(e -> {
            String text = everywhere.getText() == null ? "" : everywhere.getText().trim();
            if (text.isEmpty()) return;
            sheet.open(text, SearchSheet.Tab.STREAMING);
            everywhere.clear();
        });
        Node glass = Icons.line(Icons.SEARCH, 17);
        glass.setMouseTransparent(true);
        StackPane find = new StackPane(everywhere, glass);
        StackPane.setAlignment(glass, Pos.CENTER_LEFT);
        StackPane.setMargin(glass, new Insets(0, 0, 0, 16));
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

    private Node youtubeCard() {
        StackPane frame = new StackPane();
        frame.getStyleClass().add("thumb-frame");
        thumbnail.setPreserveRatio(true);
        thumbnail.setFitWidth(300);
        Rectangle clip = new Rectangle();
        clip.setArcWidth(20);
        clip.setArcHeight(20);
        clip.widthProperty().bind(frame.widthProperty());
        clip.heightProperty().bind(frame.heightProperty());
        frame.setClip(clip);
        Node bigPlay = Icons.solid(Icons.PLAY, 44);
        bigPlay.getStyleClass().add("thumb-play");
        frame.getChildren().addAll(thumbnail, bigPlay);
        frame.setMinHeight(170);
        frame.setPrefHeight(170);

        Label now = new Label("NOW PLAYING");
        now.getStyleClass().add("eyebrow");
        nowTitle.getStyleClass().add("now-title");
        nowTitle.setWrapText(true);

        search.setPromptText("Search YouTube, or paste a link");
        search.getStyleClass().add("search");
        Button play = new Button("Search YouTube");
        play.setGraphic(Icons.line(Icons.SEARCH, 15));
        play.getStyleClass().add("yt-play");
        play.setFocusTraversable(false);
        play.setOnAction(e -> searchYouTube());
        search.setOnAction(e -> searchYouTube());
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox controls = new VBox(10, search, play);
        play.setMaxWidth(Double.MAX_VALUE);

        HBox transport = grow(new HBox(10,
                key(Icons.solid(Icons.REWIND, 20), "rewind", "Rewind"),
                key(Icons.solid(Icons.PLAY, 20), "play", "Play"),
                key(Icons.solid(Icons.PAUSE, 20), "pause", "Pause"),
                key(Icons.solid(Icons.FORWARD, 20), "forward", "Fast forward")));

        nowWhere.getStyleClass().add("muted");
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        VBox body = new VBox(12, frame, now, nowTitle, nowWhere, grow, transport, controls);
        return card("YouTube", youtubeMark(), "accent-red", body);
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

        // Typing into the TV's own keyboard: search inside Netflix, Prime Video, anything.
        typing.setPromptText("Type on the TV…");
        typing.getStyleClass().add("typing");
        HBox.setHgrow(typing, Priority.ALWAYS);
        Button send = new Button();
        send.setGraphic(Icons.line(Icons.KEYBOARD, 18));
        send.getStyleClass().add("send");
        send.setFocusTraversable(false);
        send.setTooltip(new Tooltip("Type this into the TV's keyboard, when an app has it open"));
        Runnable type = () -> {
            String text = typing.getText();
            if (text == null || text.isBlank()) return;
            typing.clear();
            run(() -> {
                tv.type(text);
                return "Typed \"" + text + "\" on the TV.";
            });
        };
        typing.setOnAction(e -> type.run());
        send.setOnAction(e -> type.run());
        HBox typeRow = new HBox(8, typing, send);
        typeRow.setAlignment(Pos.CENTER_LEFT);
        return card("Navigate", Icons.line(Icons.UP, 16), "accent-cyan", new VBox(14, pad, row, typeRow));
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
            say("Type something to search for first.");
            return;
        }
        if (YouTube.idFromLink(words).isPresent()) {
            playVideo(YouTube.Video.linked(YouTube.idFromLink(words).get()));
        } else {
            sheet.open(words, SearchSheet.Tab.YOUTUBE);
        }
        search.clear();
    }

    private void playVideo(YouTube.Video video) {
        run(() -> {
            tv.play(video);
            Platform.runLater(() -> showNowPlaying(video.largeThumbnail(),
                    video.title() == null ? "A YouTube video" : AppTiles.label(video.title()),
                    video.channel() == null ? "YouTube" : "YouTube  ·  " + video.channel()));
            return video.title() == null ? "Playing it on the TV." : "Playing " + video.title() + ".";
        });
    }

    private void watchTitle(Catalog.Title title, Catalog.Offer offer) {
        run(() -> {
            say("Opening " + title.name() + "…");
            SamsungTv.Watching w = tv.watch(title, offer);
            String app = w.app() == null ? offer.service() : AppTiles.shortName(w.app().name());
            Platform.runLater(() -> showNowPlaying(title.poster(), title.name(), app));
            return w.atTitle() ? "Opening " + title.name() + " in " + app + "."
                    : "Opened " + app + ". Search for " + title.name() + " there - type it below the arrows.";
        });
    }

    private void showNowPlaying(String image, String title, String where) {
        thumbnail.setImage(image == null ? null : new Image(image, true));
        nowTitle.setText(title);
        nowWhere.setText(where);
    }

    /** What the search sheet asks of the window. */
    private final class SheetHost implements SearchSheet.Host {
        @Override
        public void play(YouTube.Video video) {
            playVideo(video);
        }

        @Override
        public void watch(Catalog.Title title, Catalog.Offer offer) {
            watchTitle(title, offer);
        }

        @Override
        public List<Remote.App> apps() {
            return apps;
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
                    sheet.appsChanged();
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
        if (sheet.isOpen()) {
            if (e.getCode() == KeyCode.ESCAPE) {
                e.consume();
                sheet.close();
            }
            return;
        }
        if (search.isFocused() || everywhere.isFocused() || typing.isFocused()) {
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
