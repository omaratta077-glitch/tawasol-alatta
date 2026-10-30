package com.alatta.nativeapp

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** تخطيط النافذة محدود؛ فأزرار الاتصال لا تدخل في قياس AlertDialog الداخلي للحجم. */
@Composable internal fun IncomingCallDialog(callerName: String, type: String, onClick: () -> Unit, onReject: () -> Unit) {
    Dialog(onDismissRequest = onReject, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 420.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp), color = Color(0xFF202638)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Image(painterResource(R.drawable.logo_premium), "AL-ATTA MEDIA", Modifier.size(48.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (type == "video") "مكالمة فيديو واردة" else "مكالمة صوتية واردة", color = Color.White, fontSize = 14.sp)
                        Text(callerName, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onClick, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF239B56), contentColor = Color.White)) { Text("رد") }
                    Button(onClick = onReject, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F), contentColor = Color.White)) { Text("رفض") }
                }
            }
        }
    }
}
