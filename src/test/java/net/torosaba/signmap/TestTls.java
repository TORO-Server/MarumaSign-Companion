package net.torosaba.signmap;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** テスト用の証明書 (JDK の keytool で作る) と、それを使う HTTPS サーバー */
final class TestTls {

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final String SAN = "SAN=dns:localhost,dns:site.example";

    final SSLContext serverContext;
    /** この証明書 (の発行元) を信用するクライアント側。ホスト名の検証は、SafeHttp が endpoint identification で行う */
    final SSLContext trustingClientContext;

    private TestTls(SSLContext server, SSLContext client) {
        this.serverContext = server;
        this.trustingClientContext = client;
    }

    private static void keytool(String... args) throws Exception {
        final Path keytool = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
        final List<String> cmd = new ArrayList<>();
        cmd.add(keytool.toString());
        cmd.addAll(List.of(args));
        final Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        final byte[] out = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException("keytool failed: " + new String(out));
    }

    /**
     * 自己署名の証明書 (SAN は localhost と site.example)。
     * expired が true なら、テスト用の CA が発行した、すでに期限が切れた証明書 (自己署名は、信頼の起点なので期限が見られない)。
     */
    static TestTls create(Path dir, boolean expired) throws Exception {
        final Path leaf = dir.resolve(expired ? "expired-leaf.p12" : "self.p12");
        final KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        if (!expired) {
            keytool("-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-ext", SAN,
                    "-validity", "2", "-keystore", leaf.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit");
            final KeyStore ks = load(leaf);
            trust.setCertificateEntry("t", ks.getCertificate("t"));
        } else {
            final Path ca = dir.resolve("ca.p12");
            final Path caPem = dir.resolve("ca.pem");
            final Path csr = dir.resolve("t.csr");
            final Path signed = dir.resolve("t.pem");
            keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=TestCA", "-ext", "bc:c", "-validity", "30",
                    "-keystore", ca.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit");
            keytool("-exportcert", "-rfc", "-alias", "ca", "-file", caPem.toString(), "-keystore", ca.toString(), "-storepass", "changeit");
            keytool("-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-ext", SAN, "-validity", "30",
                    "-keystore", leaf.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit");
            keytool("-certreq", "-alias", "t", "-file", csr.toString(), "-keystore", leaf.toString(), "-storepass", "changeit");
            keytool("-gencert", "-alias", "ca", "-infile", csr.toString(), "-outfile", signed.toString(), "-rfc", "-ext", SAN,
                    "-startdate", "-5d", "-validity", "1", "-keystore", ca.toString(), "-storepass", "changeit", "-keypass", "changeit");
            keytool("-importcert", "-alias", "ca", "-file", caPem.toString(), "-noprompt", "-keystore", leaf.toString(), "-storepass", "changeit");
            keytool("-importcert", "-alias", "t", "-file", signed.toString(), "-keystore", leaf.toString(), "-storepass", "changeit");
            try (InputStream in = Files.newInputStream(caPem)) {
                final Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
                trust.setCertificateEntry("ca", cert);
            }
        }
        final KeyStore store = load(leaf);
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, PASSWORD);
        final SSLContext server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        final SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
        return new TestTls(server, client);
    }

    private static KeyStore load(Path file) throws Exception {
        final KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(file)) {
            store.load(in, PASSWORD);
        }
        return store;
    }

    HttpsServer startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        final HttpsServer s = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        s.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        s.createContext("/", handler);
        s.start();
        return s;
    }
}
