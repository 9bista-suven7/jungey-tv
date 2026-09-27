package dev.suven.jungeytv.tv;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The TV's media player, as any phone or computer on the network sees it: a DLNA renderer
 * that is handed the address of something to play, fetches it itself, and plays it.
 *
 * <p>Samsung TVs describe it at {@code http://TV:9197/dmr}; playing is two SOAP calls to
 * its AVTransport service, SetAVTransportURI and Play. No token is needed.
 */
public final class MediaRenderer {

    private static final String AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1";
    /** Where Samsung TVs keep the renderer's description. */
    private static final int PORT = 9197;
    private static final Pattern STATE = Pattern.compile("<CurrentTransportState>([A-Z_]+)</CurrentTransportState>");

    /** The TV's web servers hang up on the HTTP/2 upgrade Java offers by default. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private final String control;

    private MediaRenderer(String control) {
        this.control = control;
    }

    /** The media player of the TV at this address. */
    public static MediaRenderer of(String host) throws TvException {
        String location = "http://" + host + ":" + PORT + "/dmr";
        String description = get(location);
        String control = controlUrl(description, location);
        if (control == null) {
            throw new TvException(TvException.Problem.FAILED, "The TV's media player does not take videos from the network.");
        }
        return new MediaRenderer(control);
    }

    /** The AVTransport control address in a renderer's description, made absolute, or null. */
    static String controlUrl(String description, String location) {
        Document doc = parse(description);
        if (doc == null) return null;
        NodeList services = doc.getElementsByTagName("service");
        for (int i = 0; i < services.getLength(); i++) {
            Element s = (Element) services.item(i);
            if (!AV_TRANSPORT.equals(text(s, "serviceType"))) continue;
            String url = text(s, "controlURL");
            if (url == null) return null;
            String base = text(doc.getDocumentElement(), "URLBase");
            return URI.create(base != null ? base : location).resolve(url).toString();
        }
        return null;
    }

    /** Hand the player an address to fetch, with a title for its screen. */
    public void load(String url, String title, String mime) throws TvException {
        soap("SetAVTransportURI", "<CurrentURI>" + escape(url) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + escape(didl(url, title, mime)) + "</CurrentURIMetaData>");
    }

    public void play() throws TvException {
        soap("Play", "<Speed>1</Speed>");
    }

    public void stop() throws TvException {
        soap("Stop", "");
    }

    /** PLAYING, STOPPED, TRANSITIONING, PAUSED_PLAYBACK or NO_MEDIA_PRESENT. */
    public String state() throws TvException {
        Matcher m = STATE.matcher(soap("GetTransportInfo", ""));
        return m.find() ? m.group(1) : "UNKNOWN";
    }

    /** What the player shows about what it plays: a video item, streamed as it is made. */
    static String didl(String url, String title, String mime) {
        return "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\""
                + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\""
                + " xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">"
                + "<item id=\"0\" parentID=\"-1\" restricted=\"1\">"
                + "<dc:title>" + escape(title) + "</dc:title>"
                + "<upnp:class>object.item.videoItem</upnp:class>"
                + "<res protocolInfo=\"http-get:*:" + mime + ":" + ScreenCast.DLNA_FEATURES + "\">" + escape(url) + "</res>"
                + "</item></DIDL-Lite>";
    }

    private String soap(String action, String arguments) throws TvException {
        String envelope = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + AV_TRANSPORT + "\"><InstanceID>0</InstanceID>" + arguments
                + "</u:" + action + "></s:Body></s:Envelope>";
        HttpRequest req = HttpRequest.newBuilder(URI.create(control))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPACTION", "\"" + AV_TRANSPORT + "#" + action + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(envelope))
                .build();
        try {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new TvException(TvException.Problem.FAILED,
                        "The TV's media player refused (" + action + ", " + res.statusCode() + ").");
            }
            return res.body();
        } catch (IOException e) {
            throw new TvException(TvException.Problem.UNREACHABLE, "The TV's media player is not answering.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TvException(TvException.Problem.FAILED, "Interrupted.");
        }
    }

    private static String get(String url) throws TvException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build();
        try {
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new TvException(TvException.Problem.FAILED, "The TV has no media player to cast to.");
            }
            return res.body();
        } catch (IOException e) {
            throw new TvException(TvException.Problem.UNREACHABLE, "The TV's media player is not answering. Is the TV on?", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TvException(TvException.Problem.FAILED, "Interrupted.");
        }
    }

    /** Parsed without DTDs: the description comes from the network. */
    private static Document parse(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            var builder = f.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());   // quiet: a bad description is just null
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            return null;
        }
    }

    private static String text(Element parent, String tag) {
        NodeList found = parent.getElementsByTagName(tag);
        if (found.getLength() == 0) return null;
        String t = found.item(0).getTextContent();
        return t == null || t.isBlank() ? null : t.trim();
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
