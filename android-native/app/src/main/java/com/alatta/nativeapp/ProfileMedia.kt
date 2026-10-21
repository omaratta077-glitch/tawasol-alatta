package com.alatta.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Bound the base64 input before allocation. Bitmap sampling/caching belongs to Coil. */
internal fun decodeInlineImageData(value: String): ByteArray? {
    if (!value.startsWith("data:image/") || !value.contains(";base64,")) return null
    val start = value.indexOf(";base64,") + 8
    if (value.length - start !in 1..2_000_000) return null
    return try { Base64.decode(value.substring(start), Base64.DEFAULT).takeIf { it.isNotEmpty() } }
    catch (_: IllegalArgumentException) { null }
}

/**
 * Web accounts store avatar and cover as data:image/jpeg;base64,...; R2 images use
 * /media/... paths. Handle BOTH without assuming Coil can fetch data: URLs.
 */
@Composable
internal fun ProfileMediaImage(
    value: String,
    description: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val resolved = media(value)
    if (resolved.startsWith("data:image/")) {
        val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
        var inlineFailed by remember(resolved) { mutableStateOf(false) }
        val request by produceState<ImageRequest?>(null, resolved) {
            this.value = null
            this.value = withContext(Dispatchers.IO) {
                decodeInlineImageData(resolved)?.let { bytes ->
                    // Coil sizes the decode to the actual view and shares its memory
                    // cache across rows. No full-resolution bitmap decode on the UI thread.
                    val key = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    ImageRequest.Builder(context).data(bytes).memoryCacheKey("inline:$key").build()
                }
            }
        }
        if (request != null && !inlineFailed) {
            AsyncImage(model = request, contentDescription = description,
                modifier = modifier, contentScale = contentScale,
                onError = { inlineFailed = true })
        } else {
            Box(modifier.background(Color(0xFF282119)), contentAlignment = Alignment.Center) {
                Text("الصورة غير متاحة", color = Color(0xFFE6B94E), fontSize = 11.sp)
            }
        }
    } else if (resolved.isNotBlank()) {
        var hasError by remember(resolved) { mutableStateOf(false) }
        Box(modifier) {
            AsyncImage(model = resolved, contentDescription = description,
                modifier = Modifier.fillMaxSize(), contentScale = contentScale,
                onSuccess = { hasError = false }, onError = { hasError = true })
            if (hasError) {
                Box(Modifier.fillMaxSize().background(Color(0xFF282119)),
                    contentAlignment = Alignment.Center) {
                    Text("تعذر تحميل الصورة", color = Color(0xFFE6B94E), fontSize = 11.sp)
                }
            }
        }
    } else {
        Box(modifier.background(Color(0xFF282119)))
    }
}


/** View an avatar, cover or feed image full-screen. Pinch to zoom, drag and reset. */
@Composable
internal fun ExpandableProfileMedia(
    value: String,
    description: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    var fullscreen by remember(value) { mutableStateOf(false) }
    ProfileMediaImage(value, description,
        modifier.then(if (media(value).isNotBlank()) Modifier.clickable { fullscreen = true } else Modifier),
        contentScale)
    if (fullscreen) {
        var zoom by remember(value) { mutableFloatStateOf(1f) }
        var shift by remember(value) { mutableStateOf(Offset.Zero) }
        Dialog(onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF030405))) {
                ProfileMediaImage(value, description,
                    Modifier.fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = shift.x
                            translationY = shift.y
                        }
                        .pointerInput(value) {
                            detectTransformGestures { _, pan, gestureZoom, _ ->
                                zoom = (zoom * gestureZoom).coerceIn(1f, 5f)
                                shift = if (zoom <= 1f) Offset.Zero else shift + pan
                            }
                        }, ContentScale.Fit)

            }
        }
    }
}

/**
 * Keeps the JPEG data URI strictly BELOW the backend's 250k/500k character limits.
 * Compression runs on Dispatchers.IO; no storage permission is required with GetContent.
 */
internal fun encodeProfileImage(context: Context, uri: Uri, kind: String): String {
    val isAvatar = kind == "avatar"
    require(isAvatar || kind == "cover") { "نوع الصورة غير صحيح" }
    val maxWidth = if (isAvatar) 520 else 1400
    val maxHeight = if (isAvatar) 520 else 700
    val maxChars = if (isAvatar) 150_000 else 390_000
    val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val w = info.size.width
            val h = info.size.height
            require(w > 0 && h > 0) { "الصورة غير صالحة" }
            val scale = minOf(1.0, maxWidth.toDouble() / w, maxHeight.toDouble() / h)
            decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "الصورة غير صالحة" }
        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val original = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalArgumentException("تعذر قراءة الصورة")
        val scale = minOf(1.0, maxWidth.toDouble() / original.width, maxHeight.toDouble() / original.height)
        val w = max(1, (original.width * scale).roundToInt())
        val h = max(1, (original.height * scale).roundToInt())
        if (w == original.width && h == original.height) original
        else Bitmap.createScaledBitmap(original, w, h, true).also { original.recycle() }
    }
    var current = bitmap
    try {
        repeat(10) { attempt ->
            val jpeg = ByteArrayOutputStream()
            current.compress(Bitmap.CompressFormat.JPEG, max(52, 88 - attempt * 5), jpeg)
            val base64 = Base64.encodeToString(jpeg.toByteArray(), Base64.NO_WRAP)
            val data = "data:image/jpeg;base64,$base64"
            if (data.length <= maxChars) return data
            if (attempt >= 3) {
                val reduced = Bitmap.createScaledBitmap(current,
                    max(160, (current.width * .80).roundToInt()),
                    max(160, (current.height * .80).roundToInt()), true)
                if (current !== bitmap) current.recycle()
                current = reduced
            }
        }
        throw IllegalArgumentException("الصورة كبيرة جدًا، اختر صورة أخرى")
    } finally {
        if (current !== bitmap) current.recycle()
        bitmap.recycle()
    }
}
