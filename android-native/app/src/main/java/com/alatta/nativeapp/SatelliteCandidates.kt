package com.alatta.nativeapp

/**
 * Free-to-air satellite channel discovery in AL-ATTA Media.
 * These are OFFICIAL VIEWING PAGES, not playable HLS/DASH URLs.
 * No candidate should enter FootballRepository / Media3 until its
 * direct stream and redistribution/embedding rights have been verified.
 */
internal data class SatelliteCandidate(
    val id: String,
    val name: String,
    val category: String,
    val region: String,
    val officialPage: String,
    val youtubeVideoId: String? = null
)

internal object SatelliteCandidates {
    val all: List<SatelliteCandidate> = listOf(
        // Official broadcaster YouTube live-page video IDs. Embedding may be restricted by the publisher.
        SatelliteCandidate("ar-france24-ar", "فرانس 24 العربية", "أخبار", "العربية", "https://www.youtube.com/watch?v=3ursYA8HMeo", "3ursYA8HMeo"),
        SatelliteCandidate("ar-skynews-ar", "سكاي نيوز عربية", "أخبار", "العربية", "https://www.youtube.com/watch?v=ppgTP8xQQsM", "ppgTP8xQQsM"),
        SatelliteCandidate("ar-sba-quran", "القرآن الكريم السعودية", "دينية", "العربية", "https://aloula.sba.sa/"),
        SatelliteCandidate("ar-sba-sunnah", "السنة النبوية", "دينية", "العربية", "https://aloula.sba.sa/"),
        SatelliteCandidate("ar-sba-saudi", "السعودية الأولى", "عامة", "العربية", "https://aloula.sba.sa/"),
        SatelliteCandidate("ar-sba-sbc", "SBC", "منوعات", "العربية", "https://aloula.sba.sa/"),
        SatelliteCandidate("ar-sba-alekhbariya", "الإخبارية السعودية", "أخبار", "العربية", "https://aloula.sba.sa/"),
        SatelliteCandidate("ar-sba-zekrayat", "ذكريات", "تراث", "العربية", "https://aloula.sba.sa/"),
        SatelliteCandidate("ar-dmi-dubai", "دبي TV", "منوعات", "العربية", "https://www.dubaiplus.net/web"),
        SatelliteCandidate("ar-dmi-sama", "سما دبي", "منوعات", "العربية", "https://www.dubaiplus.net/web"),
        SatelliteCandidate("ar-dmi-dubai-one", "Dubai One", "أفلام وبرامج", "العربية", "https://www.dubaiplus.net/web"),
        SatelliteCandidate("ar-dmi-dubai-sport1", "دبي الرياضية 1", "رياضة", "العربية", "https://www.dubaiplus.net/web"),
        SatelliteCandidate("ar-dmi-dubai-sport2", "دبي الرياضية 2", "رياضة", "العربية", "https://www.dubaiplus.net/web"),
        SatelliteCandidate("ar-dmi-dubai-racing1", "دبي ريسنج 1", "سباقات", "العربية", "https://www.dubaiplus.net/web"),
        SatelliteCandidate("ar-dmi-dubai-racing2", "دبي ريسنج 2", "سباقات", "العربية", "https://www.dubaiplus.net/web"),
        SatelliteCandidate("ar-dmi-dubai-zaman", "دبي زمان", "تراث", "العربية", "https://www.dubaiplus.net/web"),
        SatelliteCandidate("ar-dmi-noor-dubai", "نور دبي", "دينية واجتماعية", "العربية", "https://www.dubaiplus.net/web"),
        SatelliteCandidate("ar-aj-aljazeera", "الجزيرة الإخبارية", "أخبار", "العربية", "https://www.aljazeera360.com/"),
        SatelliteCandidate("ar-aj-aljazeera-mubasher", "الجزيرة مباشر", "أخبار", "العربية", "https://www.aljazeera360.com/"),
        SatelliteCandidate("ar-aj-aljazeera-now", "الجزيرة الآن", "أخبار", "العربية", "https://www.aljazeera360.com/"),
        SatelliteCandidate("eu-arte-de", "ARTE Deutsch", "ثقافة", "الأوروبية", "https://www.arte.tv/de/live/"),
        SatelliteCandidate("eu-arte-fr", "ARTE Français", "ثقافة", "الأوروبية", "https://www.arte.tv/fr/live/"),
        SatelliteCandidate("eu-zdf", "ZDF", "عام", "الأوروبية", "https://www.zdf.de/live-tv"),
        SatelliteCandidate("eu-daserste", "Das Erste (ARD)", "عام", "الأوروبية", "https://www.ardmediathek.de/live"),
        SatelliteCandidate("eu-3sat", "3sat", "ثقافة", "الأوروبية", "https://www.zdf.de/live-tv"),
        SatelliteCandidate("eu-zdfinfo", "ZDFinfo", "وثائقي", "الأوروبية", "https://www.zdf.de/live-tv"),
        SatelliteCandidate("eu-kika", "KiKA", "أطفال", "الأوروبية", "https://www.kika.de/"),
        SatelliteCandidate("eu-france24-fr", "France 24 Français", "أخبار", "الأوروبية", "https://www.france24.com/fr/direct"),
        SatelliteCandidate("eu-tv5monde", "TV5MONDE Europe", "عام", "الأوروبية", "https://www.tv5monde.com/")
    )
}
