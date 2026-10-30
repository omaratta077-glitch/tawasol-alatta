package com.alatta.nativeapp

import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * منتقي الحساب الأصلي في Android ‏(Credential Manager / Google Play Services).
 * يُسترجع معرّف عميل الويب وقت التشغيل من /api/google-config الحالي.
 * يُرسل رمز الهوية فقط داخل متن POST إلى /api/google-login الحالي.
 * يتحقق الخادم من الجمهور والجهة المصدرة والتوقيع ومدة صلاحية الرمز.
 *
 * مهم: أعد إعداد عميل OAuth لنظام Android في مشروع Google Cloud نفسه،
 * للحزمة com.alatta.nativeapp، وبصمة SHA-1 لشهادة توقيع الإصدار.
 */
internal object GoogleNativeSignIn {
    suspend fun pickAccount(activity: ComponentActivity, webClientId: String): String? {
        require(webClientId.endsWith(".apps.googleusercontent.com")) {
            "معرّف Google Web Client غير صالح. راجع إعداد GOOGLE_CLIENT_ID بالموقع."
        }
        val manager = CredentialManager.create(activity)

        // يظهر مربع تسجيل الدخول الأصلي من Google بعد نقر المستخدم صراحةً.
        val buttonOption = GetSignInWithGoogleOption.Builder(webClientId).build()
        val buttonRequest = GetCredentialRequest.Builder()
            .addCredentialOption(buttonOption)
            .build()
        val response = try {
            manager.getCredential(context = activity, request = buttonRequest)
        } catch (cancelled: GetCredentialCancellationException) {
            return null // Closing the sheet is not a login error.
        } catch (noCredential: NoCredentialException) {
            // اعرض جميع حسابات Google على هذا الجهاز، بما فيها حسابات المستخدمين الجدد.
            val allAccounts = GetGoogleIdOption.Builder()
                .setServerClientId(webClientId)
                .setFilterByAuthorizedAccounts(false)
                .build()
            val retry = GetCredentialRequest.Builder()
                .addCredentialOption(allAccounts)
                .build()
            try {
                manager.getCredential(context = activity, request = retry)
            } catch (cancelled: GetCredentialCancellationException) {
                return null
            } catch (e: Exception) {
                throw nativeError(e)
            }
        } catch (e: Exception) {
            throw nativeError(e)
        }

        val credential = response.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            throw IllegalStateException("لم تُرجع Google بيانات حساب صحيحة. حاول مرة أخرى.")
        }
        val token = try {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } catch (e: Exception) {
            throw IllegalStateException("تعذّر قراءة إثبات حساب Google. حاول مرة أخرى.", e)
        }
        if (token.isBlank()) throw IllegalStateException("لم يصل إثبات حساب Google.")
        return token
    }

    private fun nativeError(cause: Exception): IllegalStateException {
        val failureType = (cause as? GetCredentialException)?.type
            ?.substringAfterLast('.')?.take(90).orEmpty()
        val detail = if (failureType.isNotBlank()) " ($failureType)" else ""
        return IllegalStateException(
            "تعذّر فتح قائمة حسابات Google$detail. حدّث خدمات Google Play، " +
                "وتأكد من Android OAuth وSHA-1 الرسمي في نفس مشروع الموقع.",
            cause
        )
    }
}
