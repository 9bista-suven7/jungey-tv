package dev.suven.jungeytv.tv;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

/**
 * Trust for the TV's own certificate, and nobody else's.
 *
 * <p>Samsung TVs sign their remote-control connection with a certificate of their own,
 * issued to "SmartViewSDK" rather than to an address, so the usual checks - a known
 * authority, a matching host name - can never pass. Instead the first certificate seen is
 * remembered once pairing succeeds, and only that one is accepted afterwards.
 *
 * <p>This is an {@link X509ExtendedTrustManager} on purpose: Java checks the host name
 * inside the trust manager, and wraps a plain one in a manager that does.
 */
final class PinnedTrust extends X509ExtendedTrustManager {

    private final String pinned;
    private volatile String seen;

    private PinnedTrust(String pinned) {
        this.pinned = pinned;
    }

    /** An SSL context that accepts the pinned certificate, or on first use whatever answers. */
    static PinnedTrust forPin(String pinnedSha256) {
        return new PinnedTrust(pinnedSha256);
    }

    SSLContext context() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{this}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS is unavailable", e);
        }
    }

    /** The fingerprint of the certificate the TV presented, once it has. */
    String seen() {
        return seen;
    }

    private void check(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("the TV sent no certificate");
        String print = sha256(chain[0].getEncoded());
        seen = print;
        if (pinned != null && !pinned.equalsIgnoreCase(print)) {
            throw new CertificateException("certificate changed");
        }
    }

    static String sha256(byte[] der) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(der));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        check(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("not a server");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        throw new CertificateException("not a server");
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        throw new CertificateException("not a server");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }
}
