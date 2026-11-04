package com.alatta.nativeapp

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** App UI language only: user content, Quran text, hadith text and API keys remain original. */
internal object AttaLanguage {
    private const val PREF = "alatta_native"
    private const val KEY = "ui_language"
    var code by mutableStateOf("ar")
        private set

    fun initialize(context: Context) {
        code = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "ar").let { if (it == "en") "en" else "ar" }
    }

    fun choose(context: Context, language: String) {
        val next = if (language == "en") "en" else "ar"
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, next).apply()
        code = next
    }

    fun toggle(context: Context) = choose(context, if (code == "ar") "en" else "ar")

    private val english = mapOf(
        "الرئيسية" to "Home",
        "ريلز" to "Reels",
        "الملف" to "Profile",
        "القرآن" to "Quran",
        "القرآن الكريم" to "Holy Quran",
        "الأحاديث" to "Hadith",
        "الأذكار" to "Dhikr",
        "الرياضة" to "Sports",
        "يوتيوب" to "YouTube",
        "القائمة" to "Menu",
        "المزيد" to "More",
        "الرسائل" to "Messages",
        "الإشعارات" to "Notifications",
        "الأصدقاء" to "Friends",
        "المجموعات" to "Groups",
        "المحفوظات" to "Saved",
        "استكشاف" to "Explore",
        "الإدارة" to "Administration",
        "البث المباشر" to "Live",
        "القنوات" to "Channels",
        "تسجيل الدخول" to "Sign in",
        "إنشاء حساب" to "Create account",
        "تسجيل" to "Register",
        "تسجيل الخروج" to "Sign out",
        "البريد الإلكتروني" to "Email",
        "البريد الالكتروني" to "Email",
        "كلمة المرور" to "Password",
        "اسم المستخدم" to "Username",
        "الاسم" to "Name",
        "الاسم الكامل" to "Full name",
        "تأكيد كلمة المرور" to "Confirm password",
        "متابعة" to "Continue",
        "دخول" to "Sign in",
        "حساب جديد" to "New account",
        "نسيت كلمة المرور؟" to "Forgot password?",
        "بحث" to "Search",
        "بحث عن" to "Search for",
        "ابحث عن الأشخاص أو المنشورات..." to "Search people or posts...",
        "ماذا تفكر؟" to "What's on your mind?",
        "بم تفكر؟" to "What's on your mind?",
        "نشر" to "Post",
        "مشاركة" to "Share",
        "إرسال" to "Send",
        "تعليق" to "Comment",
        "تعليقات" to "Comments",
        "إعجاب" to "Like",
        "الإعجابات" to "Likes",
        "تعديل" to "Edit",
        "حذف" to "Delete",
        "إلغاء" to "Cancel",
        "حفظ" to "Save",
        "إغلاق" to "Close",
        "رجوع" to "Back",
        "عودة" to "Back",
        "التالي" to "Next",
        "السابق" to "Previous",
        "تم" to "Done",
        "موافق" to "OK",
        "نعم" to "Yes",
        "لا" to "No",
        "تحديث" to "Refresh",
        "إعادة المحاولة" to "Retry",
        "جاري التحميل..." to "Loading...",
        "جاري التحميل" to "Loading",
        "جاري الاتصال..." to "Connecting...",
        "مكالمة فيديو" to "Video call",
        "مكالمة صوتية" to "Voice call",
        "مكالمة واردة" to "Incoming call",
        "قبول" to "Accept",
        "رفض" to "Decline",
        "إنهاء" to "End",
        "الميكروفون" to "Microphone",
        "الكاميرا" to "Camera",
        "السماعة" to "Speaker",
        "مشاركة الشاشة" to "Share screen",
        "تبديل الكاميرا" to "Switch camera",
        "جاري تجهيز YouTube" to "Preparing YouTube",
        "تشغيل" to "Play",
        "إيقاف" to "Pause",
        "ملء الشاشة" to "Full screen",
        "تصغير" to "Minimize",
        "تكبير" to "Expand",
        "الصباح" to "Morning",
        "المساء" to "Evening",
        "أذكار الصباح" to "Morning dhikr",
        "أذكار المساء" to "Evening dhikr",
        "مواقيت الصلاة" to "Prayer times",
        "الصلاة" to "Prayer",
        "التسبيحات" to "Tasbih",
        "الفاتحة" to "Al-Fatiha",
        "صحيح البخاري" to "Sahih al-Bukhari",
        "صحيح مسلم" to "Sahih Muslim",
        "الأربعون النووية" to "Forty Nawawi Hadith",
        "المحادثات" to "Chats",
        "مراسلة" to "Message",
        "صورة" to "Image",
        "فيديو" to "Video",
        "الصوت" to "Audio",
        "الإعدادات" to "Settings",
        "اللغة" to "Language",
        "العربية" to "Arabic",
        "الإنجليزية" to "English",
        "الدولة" to "Country",
        "المدينة" to "City",
        "التعليم" to "Education",
        "الخصوصية" to "Privacy",
        "الشروط والأحكام" to "Terms and conditions",
        "الملف الشخصي" to "Profile",
        "الأصدقاء المقترحون" to "Suggested friends",
        "آخر الأخبار" to "Latest news",
        "النتائج" to "Results",
        "المفضلة" to "Favorites",
        "البحث" to "Search",
        "المساعدة" to "Help"
    )

    fun text(arabic: String): String = if (code == "en") english[arabic] ?: arabic else arabic
}
