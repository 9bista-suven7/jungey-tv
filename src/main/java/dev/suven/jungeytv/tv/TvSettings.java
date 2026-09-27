package dev.suven.jungeytv.tv;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * What Jungey TV knows about the TV it drives, kept in ~/.config/jungey-tv/tv.json.
 *
 * <p>The token is the TV's permission to be controlled, given once when someone pressed
 * Allow on it, so the file is readable by its owner only. The certificate fingerprint is
 * the TV's, remembered from the first connection: a different one later is a different
 * machine answering at the TV's address, and is refused.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class TvSettings {

    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    /** The TV's address on the home network. */
    public String host;
    /** What the TV calls itself, e.g. "[TV] Samsung 7 Series (55)". */
    public String name;
    public String model;
    /** Its network card's address, for waking it from standby. */
    public String mac;
    /** The TV's permission, given when Allow was pressed. */
    public String token;
    /** SHA-256 of the TV's certificate, pinned at the first connection. */
    public String certSha256;
    /** Two letters, e.g. "US": which country's listings and YouTube to search. Blank means this computer's. */
    public String country;

    public static Path file() {
        String override = System.getProperty("jungeytv.config");
        if (override != null) return Path.of(override);
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg == null || xdg.isBlank() ? Path.of(System.getProperty("user.home"), ".config") : Path.of(xdg);
        return base.resolve("jungey-tv").resolve("tv.json");
    }

    public static TvSettings load() {
        Path f = file();
        if (!Files.isRegularFile(f)) return new TvSettings();
        try {
            return MAPPER.readValue(f.toFile(), TvSettings.class);
        } catch (IOException e) {
            System.err.println("[jungey-tv] could not read " + f + ": " + e.getMessage());
            return new TvSettings();
        }
    }

    public synchronized void save() {
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.deleteIfExists(tmp);
            try {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(tmp);
            }
            MAPPER.writeValue(tmp.toFile(), this);
            try {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            System.err.println("[jungey-tv] could not write " + f + ": " + e.getMessage());
        }
    }

    /** The country to search in: as set, or this computer's, or the US. */
    public String country() {
        if (country != null && country.matches("[A-Za-z]{2}")) return country.toUpperCase(java.util.Locale.ENGLISH);
        String here = java.util.Locale.getDefault().getCountry();
        return here != null && here.matches("[A-Z]{2}") ? here : "US";
    }

    public boolean paired() {
        return token != null && !token.isBlank();
    }

    /** A short name to say out loud: "Samsung 7 Series", not "[TV] Samsung 7 Series (55)". */
    public String spokenName() {
        if (name == null || name.isBlank()) return "the TV";
        return name.replaceFirst("^\\[TV]\\s*", "").replaceFirst("\\s*\\(\\d+\\)$", "").trim();
    }
}
