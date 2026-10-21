package com.alatta.nativeapp

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class InlineImageTest {
    @Test fun rejectsMalformedAndOversizedDataBeforeAllocation() {
        for (value in listOf("https://test/image.png", "data:image/png,no-base64", "data:image/png;base64,?", "data:image/png;base64," + "A".repeat(2_000_001))) {
            assertNull(decodeInlineImageData(value))
        }
    }
    @Test fun coilSamplesLargeInlineImagesToTheViewInsteadOfDecodingFullResolution() = runBlocking {
        val bitmap = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)
        val packed = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, packed)
        bitmap.recycle()
        val data = "data:image/png;base64," + Base64.encodeToString(packed.toByteArray(), Base64.NO_WRAP)
        val bytes = requireNotNull(decodeInlineImageData(data))
        val context = RuntimeEnvironment.getApplication()
        val loader = ImageLoader(context)
        try {
            val result = loader.execute(ImageRequest.Builder(context).data(bytes).size(128, 128).allowHardware(false).build())
            assertTrue(result.toString(), result is SuccessResult)
            val decoded = (result.drawable as BitmapDrawable).bitmap
            assertTrue(decoded.width <= 256 && decoded.height <= 256)
        } finally { loader.shutdown() }
    }
}
