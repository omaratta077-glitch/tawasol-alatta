package com.alatta.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val AuthGold = Color(0xFFE6B94E)
private val AuthGoldLight = Color(0xFFF6D776)
private val AuthBorder = Color(0xFF6B5429)
private val AuthPanel = Color(0xFF0D1113)

@Composable
internal fun PremiumAuthTextField(value: String, onChange: (String) -> Unit, hint: String, password: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        placeholder = { Text(hint, color = Color(0xFFBFC0C0), fontSize = 13.sp, maxLines = 2) },
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        singleLine = true, shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AuthGold, unfocusedBorderColor = AuthBorder,
            focusedContainerColor = AuthPanel, unfocusedContainerColor = AuthPanel,
            cursorColor = AuthGold, focusedTextColor = Color.White, unfocusedTextColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 61.dp)
    )
}

@Composable
internal fun PremiumAuthGoldButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AuthGold, contentColor = Color(0xFF090909),
            disabledContainerColor = Color(0xFF2A2A2A), disabledContentColor = Color.Gray)
    ) { Text(label, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp) }
}

@Composable
internal fun PremiumAuthOutlineButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, AuthGoldLight),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = AuthGoldLight)) {
        Text(label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun PremiumGoogleButton(enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color(0xFF555960)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color(0xFF202124), contentColor = Color.White)) {
        Box(Modifier.size(28.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Image(painter = androidx.compose.ui.res.painterResource(R.drawable.google_g_mark),
                contentDescription = "Google", modifier = Modifier.size(23.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text("المواصلة باستخدام Google", fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}
