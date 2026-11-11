package Com.devhs.apkbinder.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

interface TelegramApiService {
    @POST("sendMessage")
    suspend fun sendMessage(
        @Query("chat_id") chatId: String,
        @Query("text") text: String
    ): Response<Any>

    @Multipart
    @POST("sendPhoto")
    suspend fun sendPhoto(
        @Query("chat_id") chatId: String,
        @Part photo: MultipartBody.Part,
        @Part("caption") caption: RequestBody
    ): Response<Any>

    @Multipart
    @POST("sendDocument")
    suspend fun sendDocument(
        @Query("chat_id") chatId: String,
        @Part document: MultipartBody.Part,
        @Part("caption") caption: RequestBody
    ): Response<Any>

    companion object {
        fun create(token: String): TelegramApiService {
            val retrofit = Retrofit.Builder()
                .baseUrl("https://api.telegram.org/bot$token/")
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            return retrofit.create(TelegramApiService::class.java)
        }
    }
}
