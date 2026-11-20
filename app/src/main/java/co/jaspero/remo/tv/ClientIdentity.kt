package co.jaspero.remo.tv

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The key and self-signed certificate this phone presents to the TV. The TV remembers the
 * certificate when pairing, so it must stay the same for the life of the install.
 */
class ClientIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {

    val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(arrayOf(FixedKeyManager(privateKey, arrayOf(certificate))), arrayOf(AcceptAnyTrustManager), SecureRandom())
        }
    }

    fun save(file: File) {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, privateKey, PASSWORD, arrayOf(certificate))
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { ks.store(it, PASSWORD) }
        if (!tmp.renameTo(file)) throw java.io.IOException("Could not save the client certificate")
    }

    companion object {
        private const val ALIAS = "remo"
        private val PASSWORD = "remo".toCharArray()

        fun loadOrCreate(file: File, commonName: String): ClientIdentity {
            if (file.exists()) {
                runCatching {
                    val ks = KeyStore.getInstance("PKCS12")
                    file.inputStream().use { ks.load(it, PASSWORD) }
                    val key = ks.getKey(ALIAS, PASSWORD) as PrivateKey
                    val cert = ks.getCertificate(ALIAS) as X509Certificate
                    return ClientIdentity(key, cert)
                }
            }
            return generate(commonName).also { it.save(file) }
        }

        fun generate(commonName: String): ClientIdentity {
            val kpg = KeyPairGenerator.getInstance("RSA")
            kpg.initialize(2048, SecureRandom())
            val kp = kpg.generateKeyPair()
            val name = X500Name("CN=" + commonName.replace(Regex("[,=+<>#;\"\\\\]"), " "))
            val now = System.currentTimeMillis()
            val day = 24L * 3600 * 1000
            val holder = JcaX509v3CertificateBuilder(
                name,
                BigInteger(63, SecureRandom()).add(BigInteger.ONE),
                Date(now - day),
                Date(now + 20 * 365 * day),
                name,
                kp.public,
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate
            return ClientIdentity(kp.private, cert)
        }

        /** Fingerprint of a TV's public key, remembered at pairing so a look-alike device is refused later. */
        fun pin(cert: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
                .joinToString("") { "%02x".format(it) }
    }
}

/** Always offers our one certificate, whatever issuers the TV asks for. */
internal class FixedKeyManager(
    private val key: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    private val alias = "remo"
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = alias
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
    override fun getCertificateChain(alias: String?) = chain
    override fun getPrivateKey(alias: String?) = key
}

/**
 * TVs use self-signed certificates, so the chain can't be verified. Instead the TV's key is pinned
 * when pairing (see [ClientIdentity.pin]) and checked on every connection.
 */
internal object AcceptAnyTrustManager : X509ExtendedTrustManager() {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}
