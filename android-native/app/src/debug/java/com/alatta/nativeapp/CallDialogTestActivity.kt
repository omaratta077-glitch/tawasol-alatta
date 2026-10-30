package com.alatta.nativeapp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
/** مضيف للاختبار في إصدار التصحيح فقط؛ يختبر نافذة الإنتاج بلا حسابات أو اتصالات شبكة. */
class CallDialogTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { IncomingCallDialog("عمر عطا", "audio", onClick = { finish() }, onReject = { finish() }) } }
    }
}
