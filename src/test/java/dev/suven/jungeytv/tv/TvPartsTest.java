package dev.suven.jungeytv.tv;

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
    void firstVideoOfASearchPage() {
        String html = "var ytInitialData = {\"contents\":{\"x\":[{\"videoRenderer\":{\"videoId\":\"jfKfPfyJRdk\","
                + "\"thumbnail\":{},\"title\":{\"runs\":[{\"text\":\"lofi hip hop radio \\ud83d\\udcda beats to relax\\u0026study\"}]}}},"
                + "{\"videoRenderer\":{\"videoId\":\"zzzzzzzzzzz\",\"title\":{\"runs\":[{\"text\":\"second\"}]}}}]}};";
        YouTube.Video v = YouTube.parse(html).orElseThrow();
        assertEquals("jfKfPfyJRdk", v.id());
        assertEquals("lofi hip hop radio 📚 beats to relax&study", v.title());
        assertFalse(YouTube.parse("<html>no results</html>").isPresent());
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
