package com.alatta.nativeapp

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
        placeholder = { Text(hint, color = Color(0xFFBFC0C0), fontSize = 14.sp, maxLines = 1) },
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 15.sp),
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        singleLine = true, shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = AuthGold, unfocusedBorderColor = AuthBorder,
            focusedContainerColor = AuthPanel, unfocusedContainerColor = AuthPanel,
            cursorColor = AuthGold, focusedTextColor = Color.White, unfocusedTextColor = Color.White
        ),
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
    )
}

@Composable
internal fun PremiumAuthGoldButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val shine = rememberInfiniteTransition(label = "gold-button-shine")
    val progress by shine.animateFloat(
        initialValue = -0.4f, targetValue = 1.4f,
        animationSpec = infiniteRepeatable(animation = tween(3000, easing = LinearEasing), repeatMode = RepeatMode.Restart),
        label = "button-glimmer"
    )
    Button(onClick = onClick, enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(18.dp))
            .drawWithContent {
                drawContent()
                if (enabled) {
                    val x = size.width * progress
                    drawLine(color = Color.White.copy(alpha = .22f),
                        start = Offset(x - 44f, 0f), end = Offset(x + 44f, size.height), strokeWidth = 28f)
                }
            },
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
internal fun PremiumGoogleButton(enabled: Boolean = true, label: String = "المواصلة باستخدام جوجل", onClick: () -> Unit) {
    // RTL: the icon stays on the right. The label may wrap instead of being
    // silently cropped by a fixed 47dp height on phones with larger font scale.
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp),
        shape = RoundedCornerShape(25.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
        border = BorderStroke(1.dp, Color(0xFF555960)),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = Color(0xFF202124), contentColor = Color.White
        )
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Image(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.google_g_mark),
                    contentDescription = "Google", modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(9.dp))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 2,
                softWrap = true,
                overflow = TextOverflow.Clip,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }
    }
}
