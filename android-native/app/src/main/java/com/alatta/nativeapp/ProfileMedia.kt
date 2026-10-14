package com.alatta.nativeapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

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
        val bitmap = remember(resolved) {
            runCatching {
                val payload = resolved.substringAfter("base64,", "")
                if (payload.isBlank() || payload.length > 2_000_000) null
                else {
                    val bytes = Base64.decode(payload, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }.getOrNull()
        }
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = description,
                modifier = modifier, contentScale = contentScale)
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
