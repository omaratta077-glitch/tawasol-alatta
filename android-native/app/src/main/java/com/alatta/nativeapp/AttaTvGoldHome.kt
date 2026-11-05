package com.alatta.nativeapp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The golden TV application's original landing screen, rebuilt as a native
 * Compose screen inside AL-ATTA Media rather than launching a second Activity.
 * The illustration was recovered from the user's working gold APK.
 */
@Composable
internal fun AttaTvGoldHome(
    onOnlineVideos: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    val gold = PremiumGoldLight
    Column(modifier.fillMaxSize().background(Color(0xFF090B0D))) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            Modifier.fillMaxWidth().height(55.dp)
                .background(Color(0xFF111416)).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(43.dp)) {
                    SiteIcon("menu", tint = Color.White, size = 22.dp)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false },
                    containerColor = Color(0xFF151719)) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.logo_premium),
                            contentDescription = "AL-ATTA TV",
                            modifier = Modifier.size(58.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("AL-ATTA TV", color = gold, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(color = PremiumBorder)
                    DropdownMenuItem(
                        text = { Text("ONLINE VIDEOS", color = gold) },
                        leadingIcon = { SiteIcon("monitor", tint = gold, size = 19.dp) },
                        onClick = { menuOpen = false; onOnlineVideos() }
                    )
                    DropdownMenuItem(
                        text = { Text("Share app", color = Color.White) },
                        leadingIcon = { SiteIcon("share", tint = gold, size = 19.dp) },
                        onClick = {
                            menuOpen = false
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "https://tawasol-alatta.omaratta077-4fd.workers.dev")
                            }
                            runCatching {
                                context.startActivity(Intent.createChooser(send, "مشاركة العطا"))
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Privacy Policy", color = Color.White) },
                        leadingIcon = { SiteIcon("lock", tint = gold, size = 19.dp) },
                        onClick = {
                            menuOpen = false
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://tawasol-alatta.omaratta077-4fd.workers.dev/privacy.html")))
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("About AL-ATTA TV", color = Color.White) },
                        leadingIcon = { SiteIcon("monitor", tint = gold, size = 19.dp) },
                        onClick = { menuOpen = false; aboutOpen = true }
                    )
                }
            }
            Text("AL-ATTA TV", color = gold, fontSize = 20.sp,
                fontWeight = FontWeight.Medium)
            IconButton(onClick = onExit, modifier = Modifier.size(43.dp)) {
                SiteIcon("log-out", tint = Color.White, size = 21.dp)
            }
        }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            // The exact source illustration was recovered from the old APK.
            Image(
                painter = painterResource(R.drawable.atta_tv_gold_home),
                contentDescription = "AL-ATTA TV original online videos illustration",
                modifier = Modifier.fillMaxWidth(.93f).aspectRatio(1.48f),
                contentScale = ContentScale.Fit
            )
        }
        Surface(
            modifier = Modifier.align(Alignment.CenterHorizontally)
                .padding(bottom = 20.dp).width(205.dp).height(117.dp)
                .clickable(onClick = onOnlineVideos),
            color = Color(0xFF111315),
            shape = RoundedCornerShape(2.dp)
        ) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                SiteIcon("monitor", tint = Color.White, size = 39.dp)
                Spacer(Modifier.height(9.dp))
                Text("ONLINE VIDEOS", color = gold, fontSize = 15.sp,
                    textAlign = TextAlign.Center)
            }
        }
    }
    if (aboutOpen) {
        AlertDialog(
            onDismissRequest = { aboutOpen = false },
            title = { Text("AL-ATTA TV") },
            text = { Text("واجهة العطا TV الذهبية داخل العطا Media. افتح ONLINE VIDEOS لاستعراض القنوات.") },
            confirmButton = {
                TextButton(onClick = { aboutOpen = false }) { Text("حسنًا") }
            }
        )
    }
}
