package com.daybook.mobile

import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** In-memory stand-in for the device-only AndroidKeyStore, so JVM tests can use SessionVault. */
class FakeKeyStoreSpi : KeyStoreSpi() {
    override fun engineGetKey(alias: String, password: CharArray?): Key? = KEYS[alias]
    override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) { KEYS[alias] = key }
    override fun engineSetKeyEntry(alias: String, key: ByteArray?, chain: Array<out Certificate>?) = throw UnsupportedOperationException()
    override fun engineContainsAlias(alias: String) = KEYS.containsKey(alias)
    override fun engineAliases() = Collections.enumeration(KEYS.keys)
    override fun engineSize() = KEYS.size
    override fun engineDeleteEntry(alias: String) { KEYS.remove(alias) }
    override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    override fun engineStore(stream: OutputStream?, password: CharArray?) {}
    override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
    override fun engineGetCertificate(alias: String): Certificate? = null
    override fun engineGetCreationDate(alias: String): Date? = null
    override fun engineIsKeyEntry(alias: String) = KEYS.containsKey(alias)
    override fun engineIsCertificateEntry(alias: String) = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineSetCertificateEntry(alias: String, cert: Certificate?) = throw UnsupportedOperationException()
    companion object { val KEYS = mutableMapOf<String, Key>() }
}

class FakeKeyGeneratorSpi : KeyGeneratorSpi() {
    private var alias = "key"
    override fun engineInit(random: SecureRandom?) {}
    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        alias = (params as? android.security.keystore.KeyGenParameterSpec)?.keystoreAlias ?: alias
    }
    override fun engineInit(keysize: Int, random: SecureRandom?) {}
    override fun engineGenerateKey(): SecretKey {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return SecretKeySpec(bytes, "AES").also { FakeKeyStoreSpi.KEYS[alias] = it }
    }
}

object FakeAndroidKeyStore {
    fun install() {
        if (Security.getProvider("AndroidKeyStore") != null) return
        Security.addProvider(object : Provider("AndroidKeyStore", 1.0, "In-memory test keystore") {
            init {
                put("KeyStore.AndroidKeyStore", FakeKeyStoreSpi::class.java.name)
                put("KeyGenerator.AES", FakeKeyGeneratorSpi::class.java.name)
            }
        })
    }
}
