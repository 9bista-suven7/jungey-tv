package dev.suven.jungeytv.ui;

import dev.suven.jungeytv.tv.TvException;
import dev.suven.jungeytv.tv.YouTube;
import javafx.animation.FadeTransition;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Searching YouTube, over the whole window: videos with their thumbnails, length, channel
 * and views, filtered the way the website filters them. Beside the results, the TV's
 * remote - YouTube on the TV can ask who is watching, and that is answered from here.
 *
 * <p>Searches run off the window's thread. A newer search makes the older one's answer go
 * unseen, so typing quickly never shows stale results.
 */
final class SearchSheet extends StackPane {

    /** What the sheet needs from the window around it. */
    interface Host {
        void play(YouTube.Video video);

        /** Press a button on the TV's remote, by any name the TV knows. */
        void press(String button);

        String country();

        void closed();
    }

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final double VIDEO_W = 230;
    private static final double VIDEO_H = VIDEO_W * 9 / 16;
    private static final double PANEL_W = 196;

    private final Host host;
    private final ExecutorService searcher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jungey-tv-search");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger videoSearch = new AtomicInteger();

    private final TextField query = new TextField();
    private final Label heading = new Label("YouTube");
    private final HBox filters = new HBox(8);
    private final TilePane videoGrid = new TilePane(18, 26);
    private final Button more = new Button("More results");
    private final VBox status = new VBox(12);
    private final Label statusText = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final ScrollPane scroll = new ScrollPane();
    private final ImageView nowImage = new ImageView();
    private final Label nowTitle = new Label("Pick a video to play it on the TV.");

    private YouTube.Filter filter = YouTube.Filter.ALL;
    private String searched = "";
    private String nextPage;
    private boolean loading, moreLoading;
    private String error;
    private int videoCount = -1;

    SearchSheet(Host host) {
        this.host = host;
        getStyleClass().add("sheet");
        setVisible(false);

        // Header: back, the search box, search.
        Button back = new Button();
        back.setGraphic(Icons.line(Icons.LEFT, 22));
        back.getStyleClass().add("back-button");
        back.setFocusTraversable(false);
        back.setTooltip(new Tooltip("Back to the control center (Esc)"));
        back.setOnAction(e -> close());

        query.setPromptText("Search YouTube, or paste a link");
        query.getStyleClass().add("sheet-query");
        query.setOnAction(e -> search(query.getText()));
        HBox.setHgrow(query, Priority.ALWAYS);
        StackPane box = new StackPane(query, searchIcon());
        box.getStyleClass().add("sheet-search");
        HBox.setHgrow(box, Priority.ALWAYS);

        Button go = new Button("Search");
        go.getStyleClass().add("sheet-go");
        go.setFocusTraversable(false);
        go.setOnAction(e -> search(query.getText()));

        HBox header = new HBox(12, back, box, go);
        header.setAlignment(Pos.CENTER_LEFT);

        // YouTube, and its filters.
        heading.setGraphic(youtubeMark());
        heading.getStyleClass().add("sheet-heading");
        for (YouTube.Filter f : YouTube.Filter.values()) {
            Button chip = new Button(f.label);
            chip.getStyleClass().add("filter-chip");
            chip.setFocusTraversable(false);
            chip.pseudoClassStateChanged(SELECTED, f == filter);
            chip.setOnAction(e -> {
                filter = f;
                filters.getChildren().forEach(n -> n.pseudoClassStateChanged(SELECTED, n == chip));
                searchVideos(searched);
            });
            filters.getChildren().add(chip);
        }
        filters.setAlignment(Pos.CENTER_RIGHT);
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bar = new HBox(10, heading, grow, filters);
        bar.setAlignment(Pos.CENTER_LEFT);

        // Results.
        videoGrid.setPrefTileWidth(VIDEO_W);
        videoGrid.setTileAlignment(Pos.TOP_LEFT);
        more.getStyleClass().add("more-results");
        more.setFocusTraversable(false);
        more.setOnAction(e -> loadMore());
        HBox moreRow = new HBox(more);
        moreRow.setAlignment(Pos.CENTER);
        VBox videos = new VBox(24, videoGrid, moreRow);

        spinner.getStyleClass().add("sheet-spinner");
        spinner.setMaxSize(46, 46);
        statusText.getStyleClass().add("sheet-status");
        statusText.setWrapText(true);
        status.getChildren().addAll(spinner, statusText);
        status.setAlignment(Pos.TOP_CENTER);
        status.setPadding(new Insets(80, 0, 0, 0));
        status.setMouseTransparent(true);

        StackPane results = new StackPane(videos, status);
        StackPane.setAlignment(videos, Pos.TOP_LEFT);
        results.setPadding(new Insets(6, 2, 30, 2));
        scroll.setContent(results);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("scroller");
        HBox.setHgrow(scroll, Priority.ALWAYS);

        HBox body = new HBox(22, scroll, remotePanel());
        VBox.setVgrow(body, Priority.ALWAYS);

        VBox page = new VBox(18, header, bar, body);
        page.setPadding(new Insets(22, 26, 22, 26));
        getChildren().add(page);
    }

    boolean isOpen() {
        return isVisible();
    }

    /** Show the sheet and search YouTube for these words. */
    void open(String words) {
        if (!isVisible()) {
            setOpacity(0);
            setVisible(true);
            FadeTransition in = new FadeTransition(Duration.millis(160), this);
            in.setToValue(1);
            in.play();
        }
        query.setText(words);
        search(words);
        query.requestFocus();
        query.end();
    }

    void close() {
        FadeTransition out = new FadeTransition(Duration.millis(140), this);
        out.setToValue(0);
        out.setOnFinished(e -> {
            setVisible(false);
            host.closed();
        });
        out.play();
    }

    // ------------------------------------------------------------------ the TV's remote

    /** What is playing, and the buttons to get around YouTube on the TV - its "Who's watching?" too. */
    private Node remotePanel() {
        Label title = new Label("ON THE TV");
        title.getStyleClass().add("eyebrow");

        nowImage.setFitWidth(PANEL_W - 32);
        nowImage.setFitHeight((PANEL_W - 32) * 9 / 16);
        nowImage.setPreserveRatio(false);
        StackPane thumb = new StackPane(nowImage);
        thumb.getStyleClass().add("thumb");
        thumb.setMaxWidth(PANEL_W - 32);
        clip(thumb, PANEL_W - 32, (PANEL_W - 32) * 9 / 16, 12);
        thumb.visibleProperty().bind(nowImage.imageProperty().isNotNull());
        thumb.managedProperty().bind(thumb.visibleProperty());
        nowTitle.getStyleClass().add("now-title");
        nowTitle.setWrapText(true);
        nowTitle.setMaxHeight(58);

        HBox transport = new HBox(6,
                key(Icons.solid(Icons.REWIND, 15), "rewind", "Rewind", "mini-key"),
                key(Icons.solid(Icons.PLAY, 15), "play", "Play", "mini-key"),
                key(Icons.solid(Icons.PAUSE, 15), "pause", "Pause", "mini-key"),
                key(Icons.solid(Icons.FORWARD, 15), "forward", "Fast forward", "mini-key"));
        transport.setAlignment(Pos.CENTER);

        GridPane pad = new GridPane();
        pad.setHgap(6);
        pad.setVgap(6);
        pad.setAlignment(Pos.CENTER);
        pad.add(key(Icons.line(Icons.UP, 20), "up", "Up", "key"), 1, 0);
        pad.add(key(Icons.line(Icons.LEFT, 20), "left", "Left", "key"), 0, 1);
        Button ok = key(null, "ok", "OK", "key");
        ok.setText("OK");
        ok.getStyleClass().add("ok");
        pad.add(ok, 1, 1);
        pad.add(key(Icons.line(Icons.RIGHT, 20), "right", "Right", "key"), 2, 1);
        pad.add(key(Icons.line(Icons.DOWN, 20), "down", "Down", "key"), 1, 2);

        Button back = key(Icons.line(Icons.BACK, 16), "back", "Back", "tile-wide");
        back.setText("Back");
        Button home = key(Icons.line(Icons.HOME, 16), "home", "Home", "tile-wide");
        home.setText("Home");
        HBox row = new HBox(6, back, home);
        for (Button b : List.of(back, home)) {
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }

        Label hint = new Label("Your arrow keys, Enter and Backspace work here too, once the search box is left.");
        hint.getStyleClass().add("muted");
        hint.setWrapText(true);

        VBox panel = new VBox(12, title, thumb, nowTitle, transport, pad, row, hint);
        panel.getStyleClass().add("remote-panel");
        panel.setMinWidth(PANEL_W);
        panel.setPrefWidth(PANEL_W);
        panel.setMaxWidth(PANEL_W);
        panel.setMaxHeight(Region.USE_PREF_SIZE);
        return panel;
    }

    private Button key(Node icon, String button, String tip, String style) {
        Button b = new Button();
        if (icon != null) b.setGraphic(icon);
        b.getStyleClass().add(style);
        b.setFocusTraversable(false);
        b.setTooltip(new Tooltip(tip));
        b.setOnAction(e -> host.press(button));
        return b;
    }

    // ------------------------------------------------------------------ searching

    private void search(String words) {
        String text = words == null ? "" : words.trim();
        if (text.isEmpty()) {
            statusText.setText("Type what you want to watch.");
            status.setVisible(true);
            return;
        }
        Optional<String> link = YouTube.idFromLink(text);
        if (link.isPresent()) {
            play(YouTube.Video.linked(link.get()));
            return;
        }
        searched = text;
        searchVideos(text);
    }

    private void play(YouTube.Video v) {
        nowImage.setImage(new Image(v.thumbnail(), true));
        nowTitle.setText(v.title() == null ? "A YouTube video" : AppTiles.label(v.title()));
        host.play(v);
        // The arrow keys go to the TV now, for YouTube's "Who's watching?".
        requestFocus();
    }

    private void searchVideos(String text) {
        if (text.isBlank()) return;
        int mine = videoSearch.incrementAndGet();
        videoGrid.getChildren().clear();
        nextPage = null;
        videoCount = -1;
        error = null;
        loading = true;
        scroll.setVvalue(0);
        refresh();
        YouTube.Filter f = filter;
        String country = host.country();
        searcher.submit(() -> {
            try {
                YouTube.Page page = YouTube.search(text, f, country);
                Platform.runLater(() -> {
                    if (mine != videoSearch.get()) return;
                    loading = false;
                    videoCount = page.videos().size();
                    nextPage = page.next();
                    page.videos().forEach(v -> videoGrid.getChildren().add(videoCard(v)));
                    refresh();
                });
            } catch (TvException e) {
                Platform.runLater(() -> {
                    if (mine != videoSearch.get()) return;
                    loading = false;
                    error = e.getMessage();
                    refresh();
                });
            }
        });
    }

    private void loadMore() {
        if (nextPage == null || moreLoading) return;
        int mine = videoSearch.get();
        String token = nextPage;
        moreLoading = true;
        more.setText("Loading…");
        String country = host.country();
        searcher.submit(() -> {
            try {
                YouTube.Page page = YouTube.more(token, country);
                Platform.runLater(() -> {
                    moreLoading = false;
                    more.setText("More results");
                    if (mine != videoSearch.get()) return;
                    nextPage = page.next();
                    page.videos().forEach(v -> videoGrid.getChildren().add(videoCard(v)));
                    videoCount = videoGrid.getChildren().size();
                    refresh();
                });
            } catch (TvException e) {
                Platform.runLater(() -> {
                    moreLoading = false;
                    more.setText("More results");
                });
            }
        });
    }

    /** The count by the heading, and the spinner or a message where there are no results to show. */
    private void refresh() {
        heading.setText(videoCount > 0 ? "YouTube  " + videoCount + (nextPage != null ? "+" : "") : "YouTube");
        more.setVisible(nextPage != null && videoCount > 0);
        more.setManaged(more.isVisible());

        spinner.setVisible(loading);
        spinner.setManaged(loading);
        if (loading) {
            statusText.setText("Searching YouTube…");
        } else if (error != null) {
            statusText.setText(error);
        } else if (videoCount == 0) {
            statusText.setText("Nothing found for \"" + searched + "\"" + (filter != YouTube.Filter.ALL
                    ? " with the " + filter.label + " filter." : "."));
        } else {
            statusText.setText("");
        }
        status.setVisible(!statusText.getText().isEmpty());
    }

    // ------------------------------------------------------------------ cards

    private Node videoCard(YouTube.Video v) {
        ImageView image = new ImageView(new Image(v.thumbnail(), VIDEO_W * 1.4, VIDEO_H * 1.4, true, true, true));
        image.setFitWidth(VIDEO_W);
        image.setFitHeight(VIDEO_H);
        StackPane thumb = new StackPane(image);
        thumb.getStyleClass().add("thumb");
        size(thumb, VIDEO_W, VIDEO_H);
        clip(thumb, VIDEO_W, VIDEO_H, 16);

        Label corner = null;
        if (v.live()) {
            corner = new Label("LIVE");
            corner.getStyleClass().add("badge-live");
        } else if (v.length() != null) {
            corner = new Label(v.length());
            corner.getStyleClass().add("badge-length");
        }
        if (corner != null) {
            thumb.getChildren().add(corner);
            StackPane.setAlignment(corner, Pos.BOTTOM_RIGHT);
            StackPane.setMargin(corner, new Insets(0, 8, 8, 0));
        }
        StackPane play = new StackPane(Icons.solid(Icons.PLAY, 22));
        play.getStyleClass().add("hover-play");
        play.setMaxSize(54, 54);
        thumb.getChildren().add(play);

        Label title = new Label(v.title() == null ? "A YouTube video" : v.title());
        title.getStyleClass().add("video-title");
        title.setWrapText(true);
        title.setPrefHeight(38);
        title.setMaxHeight(38);
        title.setAlignment(Pos.TOP_LEFT);

        VBox card = new VBox(8, thumb, title);
        if (v.channel() != null) {
            Label channel = new Label(v.channel());
            channel.getStyleClass().add("video-channel");
            card.getChildren().add(channel);
        }
        List<String> meta = new ArrayList<>();
        if (v.movie() && v.detail() != null) meta.add(v.detail());
        if (!v.movie() && v.views() != null) meta.add(v.views());
        if (v.age() != null) meta.add(v.age());
        HBox line = new HBox(8);
        line.setAlignment(Pos.CENTER_LEFT);
        if (!meta.isEmpty()) {
            Label m = new Label(String.join("  ·  ", meta));
            m.getStyleClass().add("video-meta");
            line.getChildren().add(m);
        }
        if (v.badge() != null) {
            Label free = new Label(v.badge());
            free.getStyleClass().add("free-badge");
            line.getChildren().add(free);
        } else if (v.movie()) {
            Label paid = new Label("Rent or buy");
            paid.getStyleClass().add("paid-badge");
            line.getChildren().add(paid);
        }
        if (!line.getChildren().isEmpty()) card.getChildren().add(line);

        card.getStyleClass().add("video-card");
        card.setPrefWidth(VIDEO_W);
        card.setMaxWidth(VIDEO_W);
        Tooltip.install(card, new Tooltip("Play on the TV"));
        card.setOnMouseClicked(e -> play(v));
        return card;
    }

    // ------------------------------------------------------------------ bits

    private static Node searchIcon() {
        Node icon = Icons.line(Icons.SEARCH, 18);
        icon.setMouseTransparent(true);
        StackPane.setAlignment(icon, Pos.CENTER_LEFT);
        StackPane.setMargin(icon, new Insets(0, 0, 0, 16));
        return icon;
    }

    private static Node youtubeMark() {
        StackPane mark = new StackPane(Icons.solid(Icons.PLAY, 9));
        mark.getStyleClass().add("yt-mark");
        return mark;
    }

    private static void size(Region r, double w, double h) {
        r.setMinSize(w, h);
        r.setPrefSize(w, h);
        r.setMaxSize(w, h);
    }

    private static void clip(Region r, double w, double h, double radius) {
        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(radius);
        clip.setArcHeight(radius);
        r.setClip(clip);
    }
}
