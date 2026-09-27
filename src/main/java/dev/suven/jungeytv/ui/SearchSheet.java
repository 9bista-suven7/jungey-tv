package dev.suven.jungeytv.ui;

import dev.suven.jungeytv.tv.Catalog;
import dev.suven.jungeytv.tv.Remote;
import dev.suven.jungeytv.tv.Services;
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
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Searching, over the whole window, with results to choose from: YouTube's videos with
 * their thumbnails, length, channel and views, filtered the way the website filters them;
 * and films and series with their posters and which of the TV's apps have them.
 *
 * <p>Both searches run at once, off the window's thread. A newer search makes the older
 * one's answer go unseen, so typing quickly never shows stale results.
 */
final class SearchSheet extends StackPane {

    enum Tab { YOUTUBE, STREAMING }

    /** What the sheet needs from the window around it. */
    interface Host {
        void play(YouTube.Video video);

        void watch(Catalog.Title title, Catalog.Offer offer);

        /** The TV's apps, or empty while they are not known. */
        List<Remote.App> apps();

        String country();

        void closed();
    }

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");
    private static final double VIDEO_W = 230;
    private static final double VIDEO_H = VIDEO_W * 9 / 16;
    private static final double POSTER_W = 148;
    private static final double POSTER_H = POSTER_W * 3 / 2;

    private final Host host;
    private final ExecutorService searcher = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "jungey-tv-search");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger videoSearch = new AtomicInteger();
    private final AtomicInteger titleSearch = new AtomicInteger();

    private final TextField query = new TextField();
    private final Button youtubeTab = new Button("YouTube");
    private final Button streamingTab = new Button("Movies & shows");
    private final HBox filters = new HBox(8);
    private final TilePane videoGrid = new TilePane(18, 26);
    private final TilePane titleGrid = new TilePane(16, 28);
    private final Button more = new Button("More results");
    private final VBox youtubePane;
    private final VBox streamingPane;
    private final VBox status = new VBox(12);
    private final Label statusText = new Label();
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final ScrollPane scroll = new ScrollPane();

    private Tab tab = Tab.YOUTUBE;
    private YouTube.Filter filter = YouTube.Filter.ALL;
    private String searched = "";
    private String nextPage;
    private boolean videosLoading, titlesLoading, moreLoading;
    private String videosError, titlesError;
    private int videoCount = -1, titleCount = -1;
    /** Opened for films: if none turn up, show YouTube's results instead. */
    private boolean fallBackToVideos;
    private List<Catalog.Title> lastTitles = List.of();

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

        query.setPromptText("Search YouTube, movies and shows");
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

        // Tabs, and YouTube's filters beside them.
        youtubeTab.setGraphic(youtubeMark());
        streamingTab.setGraphic(Icons.line(Icons.FILM, 16));
        for (Button b : List.of(youtubeTab, streamingTab)) {
            b.getStyleClass().add("tab-button");
            b.setFocusTraversable(false);
        }
        youtubeTab.setOnAction(e -> select(Tab.YOUTUBE));
        streamingTab.setOnAction(e -> select(Tab.STREAMING));

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
        HBox tabs = new HBox(10, youtubeTab, streamingTab, grow, filters);
        tabs.setAlignment(Pos.CENTER_LEFT);

        // Results.
        videoGrid.setPrefTileWidth(VIDEO_W);
        videoGrid.setTileAlignment(Pos.TOP_LEFT);
        more.getStyleClass().add("more-results");
        more.setFocusTraversable(false);
        more.setOnAction(e -> loadMore());
        HBox moreRow = new HBox(more);
        moreRow.setAlignment(Pos.CENTER);
        youtubePane = new VBox(24, videoGrid, moreRow);

        titleGrid.setPrefTileWidth(POSTER_W);
        titleGrid.setTileAlignment(Pos.TOP_LEFT);
        Label source = new Label("Where to watch: listings from JustWatch");
        source.getStyleClass().add("attribution");
        streamingPane = new VBox(24, titleGrid, source);

        spinner.getStyleClass().add("sheet-spinner");
        spinner.setMaxSize(46, 46);
        statusText.getStyleClass().add("sheet-status");
        statusText.setWrapText(true);
        status.getChildren().addAll(spinner, statusText);
        status.setAlignment(Pos.TOP_CENTER);
        status.setPadding(new Insets(80, 0, 0, 0));
        status.setMouseTransparent(true);

        StackPane results = new StackPane(youtubePane, streamingPane, status);
        StackPane.setAlignment(youtubePane, Pos.TOP_LEFT);
        StackPane.setAlignment(streamingPane, Pos.TOP_LEFT);
        results.setPadding(new Insets(6, 2, 30, 2));
        scroll.setContent(results);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("scroller");
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox page = new VBox(18, header, tabs, scroll);
        page.setPadding(new Insets(22, 26, 0, 26));
        getChildren().add(page);
        select(Tab.YOUTUBE);
    }

    boolean isOpen() {
        return isVisible();
    }

    /** Show the sheet and search for these words, starting on this tab. */
    void open(String words, Tab start) {
        if (!isVisible()) {
            setOpacity(0);
            setVisible(true);
            FadeTransition in = new FadeTransition(Duration.millis(160), this);
            in.setToValue(1);
            in.play();
        }
        select(start);
        fallBackToVideos = start == Tab.STREAMING;
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

    /** The TV's apps arrived or changed: which services can be opened may be different now. */
    void appsChanged() {
        if (isVisible() && !lastTitles.isEmpty()) showTitles(lastTitles);
    }

    // ------------------------------------------------------------------ searching

    private void search(String words) {
        String text = words == null ? "" : words.trim();
        if (text.isEmpty()) {
            statusText.setText("Type what you want to watch.");
            return;
        }
        Optional<String> link = YouTube.idFromLink(text);
        if (link.isPresent()) {
            host.play(YouTube.Video.linked(link.get()));
            return;
        }
        searched = text;
        searchVideos(text);
        searchTitles(text);
    }

    private void searchVideos(String text) {
        if (text.isBlank()) return;
        int mine = videoSearch.incrementAndGet();
        videoGrid.getChildren().clear();
        nextPage = null;
        videoCount = -1;
        videosError = null;
        videosLoading = true;
        refresh();
        YouTube.Filter f = filter;
        String country = host.country();
        searcher.submit(() -> {
            try {
                YouTube.Page page = YouTube.search(text, f, country);
                Platform.runLater(() -> {
                    if (mine != videoSearch.get()) return;
                    videosLoading = false;
                    videoCount = page.videos().size();
                    nextPage = page.next();
                    page.videos().forEach(v -> videoGrid.getChildren().add(videoCard(v)));
                    refresh();
                });
            } catch (TvException e) {
                Platform.runLater(() -> {
                    if (mine != videoSearch.get()) return;
                    videosLoading = false;
                    videosError = e.getMessage();
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

    private void searchTitles(String text) {
        int mine = titleSearch.incrementAndGet();
        titleGrid.getChildren().clear();
        lastTitles = List.of();
        titleCount = -1;
        titlesError = null;
        titlesLoading = true;
        refresh();
        String country = host.country();
        searcher.submit(() -> {
            try {
                List<Catalog.Title> titles = Catalog.search(text, country);
                Platform.runLater(() -> {
                    if (mine != titleSearch.get()) return;
                    titlesLoading = false;
                    titleCount = titles.size();
                    lastTitles = titles;
                    showTitles(titles);
                    if (fallBackToVideos && titles.isEmpty()) select(Tab.YOUTUBE);
                    fallBackToVideos = false;
                    refresh();
                });
            } catch (TvException e) {
                Platform.runLater(() -> {
                    if (mine != titleSearch.get()) return;
                    titlesLoading = false;
                    titlesError = e.getMessage();
                    if (fallBackToVideos) select(Tab.YOUTUBE);
                    fallBackToVideos = false;
                    refresh();
                });
            }
        });
    }

    private void showTitles(List<Catalog.Title> titles) {
        titleGrid.getChildren().clear();
        titles.forEach(t -> titleGrid.getChildren().add(titleCard(t)));
    }

    private void select(Tab next) {
        tab = next;
        youtubeTab.pseudoClassStateChanged(SELECTED, next == Tab.YOUTUBE);
        streamingTab.pseudoClassStateChanged(SELECTED, next == Tab.STREAMING);
        youtubePane.setVisible(next == Tab.YOUTUBE);
        streamingPane.setVisible(next == Tab.STREAMING);
        filters.setVisible(next == Tab.YOUTUBE);
        scroll.setVvalue(0);
        refresh();
    }

    /** Counts on the tabs, and the spinner or a message where there are no results to show. */
    private void refresh() {
        youtubeTab.setText(videoCount > 0 ? "YouTube  " + videoCount + (nextPage != null ? "+" : "") : "YouTube");
        streamingTab.setText(titleCount > 0 ? "Movies & shows  " + titleCount : "Movies & shows");
        more.setVisible(nextPage != null && videoCount > 0);
        more.setManaged(more.isVisible());

        boolean videos = tab == Tab.YOUTUBE;
        boolean loading = videos ? videosLoading : titlesLoading;
        String error = videos ? videosError : titlesError;
        int count = videos ? videoCount : titleCount;
        spinner.setVisible(loading);
        spinner.setManaged(loading);
        if (loading) {
            statusText.setText(videos ? "Searching YouTube…" : "Looking through the streaming apps…");
        } else if (error != null) {
            statusText.setText(error);
        } else if (count == 0) {
            statusText.setText("Nothing found for \"" + searched + "\"" + (videos && filter != YouTube.Filter.ALL
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
        card.setOnMouseClicked(e -> host.play(v));
        return card;
    }

    private Node titleCard(Catalog.Title t) {
        AppTiles.Look look = AppTiles.look(t.name());
        Label initials = new Label(look.mark());
        initials.getStyleClass().add("poster-initials");
        StackPane poster = new StackPane(initials);
        poster.setStyle("-fx-background-color: linear-gradient(to bottom right, " + look.from() + ", " + look.to() + ");");
        poster.getStyleClass().add("poster");
        size(poster, POSTER_W, POSTER_H);
        clip(poster, POSTER_W, POSTER_H, 16);
        if (t.poster() != null) {
            ImageView image = new ImageView(new Image(t.poster(), POSTER_W * 1.5, POSTER_H * 1.5, true, true, true));
            image.setFitWidth(POSTER_W);
            image.setFitHeight(POSTER_H);
            poster.getChildren().add(image);
        }
        Label kind = new Label(t.kind().equals("show") ? "SERIES" : "FILM");
        kind.getStyleClass().add("kind-badge");
        poster.getChildren().add(kind);
        StackPane.setAlignment(kind, Pos.TOP_LEFT);
        StackPane.setMargin(kind, new Insets(8, 0, 0, 8));

        Label name = new Label(t.name());
        name.getStyleClass().add("poster-title");
        name.setWrapText(true);
        name.setPrefHeight(36);
        name.setMaxHeight(36);
        name.setAlignment(Pos.TOP_LEFT);
        Label year = new Label((t.year() > 0 ? t.year() + "  ·  " : "") + (t.kind().equals("show") ? "Series" : "Film"));
        year.getStyleClass().add("video-meta");

        // The TV's apps that have it, as buttons; the other services it is on, as words.
        List<Remote.App> apps = host.apps();
        Map<String, Catalog.Offer> onTv = new LinkedHashMap<>();
        Map<String, Remote.App> appFor = new LinkedHashMap<>();
        List<String> elsewhere = new ArrayList<>();
        for (Catalog.Offer o : t.offers()) {
            if (!Services.known(o)) continue;
            if (apps.isEmpty()) {
                // The TV's apps are not known yet: offer every service, and let the TV say.
                onTv.putIfAbsent(o.service(), o);
                continue;
            }
            Optional<Remote.App> app = Services.app(o, apps);
            if (app.isPresent()) {
                if (!appFor.containsKey(app.get().id())) {
                    appFor.put(app.get().id(), app.get());
                    onTv.put(app.get().id(), o);
                }
            } else if (!elsewhere.contains(o.service())) {
                elsewhere.add(o.service());
            }
        }

        FlowPane chips = new FlowPane(6, 6);
        chips.setPrefWrapLength(POSTER_W);
        for (Map.Entry<String, Catalog.Offer> e : onTv.entrySet()) {
            if (chips.getChildren().size() == 4) break;   // the best four; a fifth is only another shop
            Catalog.Offer o = e.getValue();
            Remote.App app = appFor.get(e.getKey());
            String label = app != null ? AppTiles.shortName(app.name()) : o.service();
            if (!o.included()) label += o.type().equals("rent") ? " · rent" : " · buy";
            else if (o.type().startsWith("free")) label += " · free";
            Button chip = new Button(label);
            AppTiles.Look service = AppTiles.look(app != null ? app.name() : o.service());
            chip.setStyle("-fx-background-color: linear-gradient(to bottom right, " + service.from() + ", " + service.to() + ");"
                    + (service.darkMark() ? " -fx-text-fill: #04260f;" : ""));
            chip.getStyleClass().add("service-chip");
            chip.setFocusTraversable(false);
            chip.setTooltip(new Tooltip("Open in " + label.replaceFirst(" · .*", "") + " on the TV"));
            chip.setOnAction(ev -> host.watch(t, o));
            chips.getChildren().add(chip);
        }

        VBox card = new VBox(8, poster, name, year);
        if (!chips.getChildren().isEmpty()) card.getChildren().add(chips);
        if (!elsewhere.isEmpty()) {
            Label also = new Label("Also on " + String.join(", ", elsewhere.subList(0, Math.min(3, elsewhere.size()))));
            also.getStyleClass().add("also-on");
            also.setWrapText(true);
            card.getChildren().add(also);
        }
        if (chips.getChildren().isEmpty() && elsewhere.isEmpty()) {
            Label none = new Label("Not streaming right now");
            none.getStyleClass().add("also-on");
            card.getChildren().add(none);
        }
        card.getStyleClass().add("title-card");
        card.setPrefWidth(POSTER_W);
        card.setMaxWidth(POSTER_W);
        Catalog.Offer first = onTv.isEmpty() ? null : onTv.values().iterator().next();
        poster.setOnMouseClicked(e -> {
            if (first != null) host.watch(t, first);
        });
        if (first != null) Tooltip.install(poster, new Tooltip("Open " + t.name() + " on the TV"));
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
