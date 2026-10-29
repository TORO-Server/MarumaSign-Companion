package net.torosaba.signmap

import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** テスト用の証明書 (JDK の keytool で作る) と、それを使う HTTPS サーバー */
internal class TestTls private constructor(
    @JvmField val serverContext: SSLContext,
    /** この証明書 (の発行元) を信用するクライアント側。ホスト名の検証は、SafeHttp が endpoint identification で行う */
    @JvmField val trustingClientContext: SSLContext,
) {

    @Throws(IOException::class)
    fun startServer(handler: HttpHandler): HttpsServer {
        val s = HttpsServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        s.httpsConfigurator = HttpsConfigurator(serverContext)
        s.createContext("/", handler)
        s.start()
        return s
    }

    companion object {
        private val PASSWORD = "changeit".toCharArray()
        private const val SAN = "SAN=dns:localhost,dns:site.example"

        private fun keytool(vararg args: String) {
            val keytool = Path.of(System.getProperty("java.home"), "bin",
                if (System.getProperty("os.name").lowercase().contains("win")) "keytool.exe" else "keytool")
            val cmd = ArrayList<String>()
            cmd.add(keytool.toString())
            cmd.addAll(args)
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.readAllBytes()
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) throw IOException("keytool failed: " + String(out))
        }

        /**
         * 自己署名の証明書 (SAN は localhost と site.example)。
         * expired が true なら、テスト用の CA が発行した、すでに期限が切れた証明書 (自己署名は、信頼の起点なので期限が見られない)。
         */
        @JvmStatic
        fun create(dir: Path, expired: Boolean): TestTls {
            val leaf = dir.resolve(if (expired) "expired-leaf.p12" else "self.p12")
            val trust = KeyStore.getInstance("PKCS12")
            trust.load(null, null)
            if (!expired) {
                keytool("-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-ext", SAN,
                    "-validity", "2", "-keystore", leaf.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit")
                val ks = load(leaf)
                trust.setCertificateEntry("t", ks.getCertificate("t"))
            } else {
                val ca = dir.resolve("ca.p12")
                val caPem = dir.resolve("ca.pem")
                val csr = dir.resolve("t.csr")
                val signed = dir.resolve("t.pem")
                keytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=TestCA", "-ext", "bc:c", "-validity", "30",
                    "-keystore", ca.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit")
                keytool("-exportcert", "-rfc", "-alias", "ca", "-file", caPem.toString(), "-keystore", ca.toString(), "-storepass", "changeit")
                keytool("-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-ext", SAN, "-validity", "30",
                    "-keystore", leaf.toString(), "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit")
                keytool("-certreq", "-alias", "t", "-file", csr.toString(), "-keystore", leaf.toString(), "-storepass", "changeit")
                keytool("-gencert", "-alias", "ca", "-infile", csr.toString(), "-outfile", signed.toString(), "-rfc", "-ext", SAN,
                    "-startdate", "-5d", "-validity", "1", "-keystore", ca.toString(), "-storepass", "changeit", "-keypass", "changeit")
                keytool("-importcert", "-alias", "ca", "-file", caPem.toString(), "-noprompt", "-keystore", leaf.toString(), "-storepass", "changeit")
                keytool("-importcert", "-alias", "t", "-file", signed.toString(), "-keystore", leaf.toString(), "-storepass", "changeit")
                Files.newInputStream(caPem).use { input ->
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(input)
                    trust.setCertificateEntry("ca", cert)
                }
            }
            val store = load(leaf)
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(store, PASSWORD)
            val server = SSLContext.getInstance("TLS")
            server.init(kmf.keyManagers, null, null)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(trust)
            val client = SSLContext.getInstance("TLS")
            client.init(null, tmf.trustManagers, null)
            return TestTls(server, client)
        }

        private fun load(file: Path): KeyStore {
            val store = KeyStore.getInstance("PKCS12")
            Files.newInputStream(file).use { input -> store.load(input, PASSWORD) }
            return store
        }
    }
}
