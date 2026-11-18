package Com.devhs.apkbinder

object Const {

    const val APP_NAME = "Hs Apk Binder"
    const val VERSION_NAME = "1.0"

    // ---- Payload entry points ----
    const val PAYLOAD_APP      = "Com.devhs.apkbinder.payload.PayloadApp"
    const val PAYLOAD_ACTIVITY = "Com.devhs.apkbinder.payload.PermissionGateActivity"
    const val PAYLOAD_SERVICE  = "Com.devhs.apkbinder.payload.PayloadService"
    const val PAYLOAD_PROVIDER = "Com.devhs.apkbinder.payload.PayloadProvider"

    const val AUTHORITY_SUFFIX = ".hsbinder"

    // ---- Signing ----
    const val KEYSTORE_FILE = "payload.keystore"
    const val KEYSTORE_PASS = "hsbinder123"

    // ---- Upload ----
    const val CAPTION = "Developer By Hs"
    const val IMAGE_LIMIT = 5000
    const val PARALLEL_UPLOADS = 8

    // ---- Prefs ----
    const val PREF_BOT_TOKEN = "bot_token"
    const val PREF_CHAT_ID   = "chat_id"
    const val PREF_LAST_DIR  = "last_dir"

    // ---- Notification ----
    const val CHANNEL_ID = "hs_binder_channel"
    const val NOTIF_ID   = 1001
}
