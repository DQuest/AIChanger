package dev.dquest.aichanger

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * ADB-клиент приложения для подключения к собственному adbd по беспроводной отладке.
 * Ключ и сертификат лежат в хранилище, доступном до первой разблокировки: автозапуск идёт на экране блокировки.
 */
class AdbManager private constructor(context: Context) : AbsAdbConnectionManager() {

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        api = Build.VERSION.SDK_INT
        // Среди найденных перебором портов могут быть чужие, которые на CNXN не ответят: не ждём их долго.
        setTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        val dir = File(context.createDeviceProtectedStorageContext().filesDir, "adb").apply { mkdirs() }
        val keyFile = File(dir, "key.pk8")
        val certFile = File(dir, "cert.der")
        // Битый ключ иначе ломал бы каждое подключение, и даже новое сопряжение не помогло бы.
        val (key, cert) = runCatching { load(keyFile, certFile) }
            .onFailure { Log.w(TAG, "adb key is unreadable, generating a new one", it) }
            .getOrNull() ?: generate(keyFile, certFile)
        privateKey = key
        certificate = cert
    }

    /** null — ключа ещё нет. */
    private fun load(keyFile: File, certFile: File): Pair<PrivateKey, Certificate>? {
        if (!keyFile.exists() || !certFile.exists()) return null
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        return key to cert
    }

    /**
     * Сертификат удаляем первым и пишем последним, каждый файл — через временный и переименование. Если процесс
     * убьют посреди записи, при следующем запуске сертификата не будет и ключ просто создастся заново.
     */
    private fun generate(keyFile: File, certFile: File): Pair<PrivateKey, Certificate> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=AIChanger")
        val now = System.currentTimeMillis()
        val holder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - DAY_MS), Date(now + CERT_VALIDITY_MS), name, keyPair.public
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val cert = JcaX509CertificateConverter().getCertificate(holder)
        certFile.delete()
        keyFile.writeAtomically(keyPair.private.encoded)
        certFile.writeAtomically(cert.encoded)
        // Новый ключ adbd ещё не знает: прежнее сопряжение, если оно было, больше не действует, и мастер предложит новое.
        PowerKeyController.setPaired(false)
        return keyPair.private to cert
    }

    private fun File.writeAtomically(bytes: ByteArray) {
        val temp = File(parentFile, "$name.tmp")
        FileOutputStream(temp).use {
            it.write(bytes)
            it.fd.sync()
        }
        if (!temp.renameTo(this)) throw IOException("не удалось сохранить $name")
    }

    override fun getPrivateKey() = privateKey

    override fun getCertificate() = certificate

    override fun getDeviceName() = "AIChanger"

    companion object {
        private const val TAG = "AIChanger"
        private const val DAY_MS = 24 * 60 * 60 * 1000L
        private const val CONNECT_TIMEOUT_S = 5L
        private const val CERT_VALIDITY_MS = 30 * 365 * DAY_MS

        @Volatile private var instance: AdbManager? = null

        fun get(context: Context): AdbManager =
            instance ?: synchronized(this) { instance ?: AdbManager(context.applicationContext).also { instance = it } }
    }
}
