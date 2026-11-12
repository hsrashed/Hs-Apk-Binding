package Com.devhs.apkbinder.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore("hs_binder_prefs")

object Prefs {
    private val TOKEN = stringPreferencesKey("bot_token")
    private val CHAT = stringPreferencesKey("chat_id")
    private val TEST = booleanPreferencesKey("test_mode")

    fun token(ctx: Context): Flow<String> = ctx.store.data.map { it[TOKEN] ?: "" }
    fun chatId(ctx: Context): Flow<String> = ctx.store.data.map { it[CHAT] ?: "" }
    fun testMode(ctx: Context): Flow<Boolean> = ctx.store.data.map { it[TEST] ?: false }

    suspend fun save(ctx: Context, token: String, chat: String) {
        ctx.store.edit {
            it[TOKEN] = token
            it[CHAT] = chat
        }
    }

    suspend fun setTestMode(ctx: Context, enabled: Boolean) {
        ctx.store.edit { it[TEST] = enabled }
    }
}
