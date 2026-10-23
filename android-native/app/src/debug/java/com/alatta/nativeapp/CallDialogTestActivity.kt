package com.alatta.nativeapp
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
/** Debug-only host: exercises the production dialog without accounts or network calls. */
class CallDialogTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { IncomingCallDialog("عمر عطا", "audio", onClick = { finish() }, onReject = { finish() }) } }
    }
}
