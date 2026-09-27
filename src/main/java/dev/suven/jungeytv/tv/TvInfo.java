package dev.suven.jungeytv.tv;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * What the TV says about itself, unasked for any permission: its name, model, network
 * address and whether it is on. Samsung TVs answer this on port 8001 whenever their
 * network is up, standby included.
 */
public record TvInfo(String host, String name, String model, String mac, String powerState, boolean samsung) {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // HTTP/1.1 only: the TV's web server hangs up on the HTTP/2 upgrade Java offers by default.
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(1500))
            .build();

    /** The TV's own description, or null if nothing answered at that address. */
    public static TvInfo fetch(String host) {
        if (host == null || host.isBlank()) return null;
        try {
            HttpResponse<String> res = CLIENT.send(
                    HttpRequest.newBuilder(URI.create("http://" + host + ":8001/api/v2/"))
                            .timeout(Duration.ofMillis(2500)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) return null;
            return parse(host, res.body());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    static TvInfo parse(String host, String json) throws java.io.IOException {
        JsonNode root = MAPPER.readTree(json);
        JsonNode device = root.path("device");
        String mac = device.path("wifiMac").asText("");
        if (mac.isBlank()) mac = device.path("wiredMac").asText("");
        String power = device.path("PowerState").asText("on");
        return new TvInfo(host,
                device.path("name").asText(root.path("name").asText("Samsung TV")),
                device.path("modelName").asText(""),
                mac.isBlank() ? null : mac,
                power.isBlank() ? "on" : power.toLowerCase(java.util.Locale.ENGLISH),
                device.path("type").asText(root.path("type").asText("")).toLowerCase().contains("samsung"));
    }

    public boolean on() {
        return powerState.equals("on");
    }
}
