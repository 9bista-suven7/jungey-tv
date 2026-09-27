package dev.suven.jungeytv.tv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TvPartsTest {

    @Test
    void buttonsByTheNamesPeopleUse() {
        assertEquals("KEY_VOLUP", Keys.code("volume up"));
        assertEquals("KEY_VOLUP", Keys.code("Volume_Up"));
        assertEquals("KEY_HDMI2", Keys.code("hdmi 2"));
        assertEquals("KEY_HDMI2", Keys.code("hdmi2"));
        assertEquals("KEY_ENTER", Keys.code("OK"));
        assertEquals("KEY_RETURN", Keys.code("back"));
        assertEquals("KEY_7", Keys.code("7"));
        assertEquals("KEY_HOME", Keys.code("KEY_HOME"));
        assertNull(Keys.code("launch missiles"));
    }

    @Test
    void wakePacketIsTheAddressSixteenTimesAfterSixFFs() {
        byte[] p = WakeOnLan.packet("f4:fe:fb:12:34:56");
        assertEquals(102, p.length);
        for (int i = 0; i < 6; i++) assertEquals((byte) 0xff, p[i]);
        byte[] mac = {(byte) 0xf4, (byte) 0xfe, (byte) 0xfb, 0x12, 0x34, 0x56};
        for (int r = 0; r < 16; r++) {
            byte[] copy = new byte[6];
            System.arraycopy(p, 6 + r * 6, copy, 0, 6);
            assertArrayEquals(mac, copy);
        }
        assertThrows(IllegalArgumentException.class, () -> WakeOnLan.packet("not a mac"));
    }

    @Test
    void youtubeLinksOfEveryShape() {
        assertEquals(Optional.of("dQw4w9WgXcQ"), YouTube.idFromLink("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        assertEquals(Optional.of("dQw4w9WgXcQ"), YouTube.idFromLink("https://youtu.be/dQw4w9WgXcQ?t=42"));
        assertEquals(Optional.of("dQw4w9WgXcQ"), YouTube.idFromLink("https://youtube.com/watch?feature=share&v=dQw4w9WgXcQ"));
        assertEquals(Optional.of("abcDEF12_-x"), YouTube.idFromLink("https://www.youtube.com/shorts/abcDEF12_-x"));
        assertFalse(YouTube.idFromLink("lofi hip hop radio").isPresent());
    }

    @Test
    void videosFromASearchAnswerWhereverTheySit() throws Exception {
        String json = """
                {"contents":{"twoColumnSearchResultsRenderer":{"primaryContents":{"sectionListRenderer":{"contents":[
                  {"itemSectionRenderer":{"contents":[
                    {"videoRenderer":{"videoId":"jfKfPfyJRdk","title":{"runs":[{"text":"lofi hip hop radio \ud83d\udcda beats"}]},
                      "ownerText":{"runs":[{"text":"Lofi Girl"}]},"shortViewCountText":{"simpleText":"12K watching"},
                      "badges":[{"metadataBadgeRenderer":{"label":"LIVE"}}]}},
                    {"shelfRenderer":{"content":{"verticalListRenderer":{"items":[
                      {"videoRenderer":{"videoId":"abcDEF12_-x","title":{"simpleText":"From a shelf"},
                        "lengthText":{"simpleText":"3:21"},"publishedTimeText":{"simpleText":"2 years ago"}}},
                      {"videoRenderer":{"videoId":"jfKfPfyJRdk","title":{"simpleText":"duplicate"}}}]}}}},
                    {"movieRenderer":{"videoId":"bdEqvgVSI2Y","title":{"runs":[{"text":"Wanted"}]},
                      "lengthText":{"simpleText":"1:49:53"},"topMetadataItems":[{"simpleText":"Action & adventure \u2022 2008"}],
                      "badges":[{"metadataBadgeRenderer":{"label":"Free with ads"}},{"metadataBadgeRenderer":{"label":"R"}}]}},
                    {"reelItemRenderer":{"videoId":"shortsvideo"}}]}},
                  {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"NEXT"}}}}]}}}}}
                """;
        YouTube.Page page = YouTube.parse(new ObjectMapper().readTree(json));
        assertEquals(List.of("jfKfPfyJRdk", "abcDEF12_-x", "bdEqvgVSI2Y"), page.videos().stream().map(YouTube.Video::id).toList());
        assertEquals("NEXT", page.next());

        YouTube.Video live = page.videos().get(0);
        assertEquals("lofi hip hop radio 📚 beats", live.title());
        assertEquals("Lofi Girl", live.channel());
        assertTrue(live.live());
        assertNull(live.length());

        YouTube.Video shelf = page.videos().get(1);
        assertEquals("3:21", shelf.length());
        assertEquals("2 years ago", shelf.age());

        YouTube.Video movie = page.videos().get(2);
        assertTrue(movie.movie());
        assertEquals("Free with ads", movie.badge());
        assertEquals("Action & adventure • 2008", movie.detail());
        assertEquals("https://i.ytimg.com/vi/bdEqvgVSI2Y/mqdefault.jpg", movie.thumbnail());

        assertEquals(YouTube.Filter.MOVIES, YouTube.Filter.named("movies"));
        assertEquals(YouTube.Filter.LONG, YouTube.Filter.named("long"));
        assertEquals(YouTube.Filter.ALL, YouTube.Filter.named("nonsense"));
    }

    @Test
    void titlesAndWhereTheyStream() throws Exception {
        String json = """
                {"data":{"popularTitles":{"edges":[
                  {"node":{"id":"tss20823","objectType":"SHOW",
                    "content":{"title":"Stranger Things","originalReleaseYear":2016,"posterUrl":"/poster/301444843/{profile}/stranger-things.{format}"},
                    "offers":[
                      {"monetizationType":"BUY","standardWebURL":"https://amazon.com/dvd","package":{"clearName":"Amazon DVD","technicalName":"amazondvd"}},
                      {"monetizationType":"FLATRATE","standardWebURL":"https://www.netflix.com/title/80057281","package":{"clearName":"Netflix","technicalName":"netflix"}},
                      {"monetizationType":"FLATRATE","standardWebURL":"https://www.netflix.com/title/80057281","package":{"clearName":"Netflix","technicalName":"netflix"}}]}},
                  {"node":{"id":"tm1","objectType":"MOVIE",
                    "content":{"title":"Inception","originalReleaseYear":2010,"posterUrl":null},
                    "offers":[
                      {"monetizationType":"RENT","standardWebURL":"https://watch.amazon.com/detail?gti=x","package":{"clearName":"Amazon Video","technicalName":"amazon"}},
                      {"monetizationType":"BUY","standardWebURL":"https://watch.amazon.com/detail?gti=x","package":{"clearName":"Amazon Video","technicalName":"amazon"}},
                      {"monetizationType":"FLATRATE","standardWebURL":"https://play.max.com/x","package":{"clearName":"Max","technicalName":"max"}}]}}]}}}
                """;
        List<Catalog.Title> titles = Catalog.parse(new ObjectMapper().readTree(json));
        assertEquals(2, titles.size());

        Catalog.Title st = titles.get(0);
        assertEquals("show", st.kind());
        assertEquals(2016, st.year());
        assertEquals("https://images.justwatch.com/poster/301444843/s332/stranger-things.jpg", st.poster());
        assertEquals(1, st.offers().size(), "one Netflix, and no DVD");
        assertEquals("subscription", st.offers().getFirst().type());
        assertEquals("m=http://api-global.netflix.com/catalog/titles/movies/80057281&source_type=4",
                Services.deepLink(st.offers().getFirst()));

        Catalog.Title inception = titles.get(1);
        assertNull(inception.poster());
        assertEquals(List.of("Max", "Amazon Video"), inception.offers().stream().map(Catalog.Offer::service).toList(),
                "included before rented");
        assertEquals("rent", inception.offers().get(1).type(), "renting is better than buying");
        assertFalse(inception.offers().get(1).included());

        assertThrows(TvException.class, () -> Catalog.parse(new ObjectMapper().readTree("{\"errors\":[{\"message\":\"nope\"}]}")));
    }

    @Test
    void servicesToTheAppsThatPlayThem() {
        List<Remote.App> apps = List.of(
                new Remote.App("1", "Netflix", 2), new Remote.App("2", "Prime Video", 2),
                new Remote.App("3", "Disney+", 2), new Remote.App("4", "Tubi - Free Movies & TV", 2),
                new Remote.App("5", "MagellanTV Documentaries", 2), new Remote.App("6", "YouTube", 2));
        assertEquals("Prime Video", Services.app(new Catalog.Offer("Amazon Video", "amazon", "rent", ""), apps).orElseThrow().name());
        assertEquals("Prime Video", Services.app(new Catalog.Offer("Amazon Prime Video", "amazonprime", "subscription", ""), apps).orElseThrow().name());
        assertEquals("Disney+", Services.app(new Catalog.Offer("Disney Plus", "disneyplus", "subscription", ""), apps).orElseThrow().name());
        assertEquals("Tubi - Free Movies & TV", Services.app(new Catalog.Offer("Tubi TV", "tubitv", "free with ads", ""), apps).orElseThrow().name());
        assertTrue(Services.app(new Catalog.Offer("Max", "max", "subscription", ""), apps).isEmpty(), "not MagellanTV");
        assertEquals(java.util.Optional.of("bdEqvgVSI2Y"),
                Services.youtubeVideo(new Catalog.Offer("YouTube", "youtube", "rent", "https://www.youtube.com/watch?v=bdEqvgVSI2Y")));
        assertNull(Services.deepLink(new Catalog.Offer("Hulu", "hulu", "subscription", "https://www.hulu.com/movie/x")));
    }

    @Test
    void appsBySpokenName() {
        List<Remote.App> apps = List.of(
                new Remote.App("111299001912", "YouTube", 2),
                new Remote.App("3201910019365", "Prime Video", 2),
                new Remote.App("3201907018807", "Netflix", 2),
                new Remote.App("3201901017640", "Disney+", 2),
                new Remote.App("111477001142", "YouTube Kids", 2));
        assertEquals("Netflix", SamsungTv.best(apps, "netflix").orElseThrow().name());
        assertEquals("Prime Video", SamsungTv.best(apps, "amazon prime").orElseThrow().name());
        assertEquals("Prime Video", SamsungTv.best(apps, "prime").orElseThrow().name());
        assertEquals("Disney+", SamsungTv.best(apps, "disney plus").orElseThrow().name());
        assertEquals("YouTube", SamsungTv.best(apps, "YouTube").orElseThrow().name());
        assertEquals("YouTube Kids", SamsungTv.best(apps, "youtube kids").orElseThrow().name());
        assertTrue(SamsungTv.best(apps, "hbo").isEmpty());
    }

    @Test
    void whatTheTvSaysAboutItself() throws Exception {
        String json = """
                {"device":{"name":"[TV] Samsung 7 Series (55)","modelName":"UN55RU7100FXZA","type":"Samsung SmartTV",
                 "wifiMac":"f4:fe:fb:12:34:56","PowerState":"standby"},"type":"Samsung SmartTV","version":"2.0.25"}
                """;
        TvInfo info = TvInfo.parse("192.168.1.20", json);
        assertEquals("UN55RU7100FXZA", info.model());
        assertEquals("f4:fe:fb:12:34:56", info.mac());
        assertEquals("standby", info.powerState());
        assertTrue(info.samsung());
        assertFalse(info.on());

        TvSettings s = new TvSettings();
        s.name = info.name();
        assertEquals("Samsung 7 Series", s.spokenName());
    }
}
