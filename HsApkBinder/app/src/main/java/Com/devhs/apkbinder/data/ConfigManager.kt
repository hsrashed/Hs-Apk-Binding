package Com.devhs.apkbinder.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "settings")

class ConfigManager(private val context: Context) {
    companion object {
        val BOT_TOKEN_KEY = stringPreferencesKey("bot_token")
        val CHAT_ID_KEY = stringPreferencesKey("chat_id")
    }

    val botTokenFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[BOT_TOKEN_KEY] ?: "" }

    val chatIdFlow: Flow<String> = context.dataStore.data
        .map { preferences -> preferences[CHAT_ID_KEY] ?: "" }

    suspend fun saveConfig(token: String, chatId: String) {
        context.dataStore.edit { preferences ->
            preferences[BOT_TOKEN_KEY] = token
            preferences[CHAT_ID_KEY] = chatId
        }
    }
}
