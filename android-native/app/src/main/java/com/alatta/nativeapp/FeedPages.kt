package com.alatta.nativeapp

import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject

internal data class FeedPage(val posts: List<JSONObject>, val nextCursor: String?, val replace: Boolean)
internal object FeedPages {
    const val SIZE = 10
    suspend fun fetch(get: suspend (String) -> JSONObject, cursor: String? = null, author: String? = null): FeedPage {
        val legacyLimit = cursor?.removePrefix("legacy:")?.toIntOrNull()?.coerceIn(SIZE, 50) ?: SIZE
        val path = "/api/posts?limit=$legacyLimit&page=1&compact=1" +
            (if (cursor != null && !cursor.startsWith("legacy:")) "&before=" + enc(cursor) else "") +
            (if (!author.isNullOrBlank()) "&author=" + enc(author) else "")
        var response = get(path)
        val paged = response.optBoolean("paged")
        // تتجاهل Workers القديمة مرشح الكاتب. حافظ على نافذة منشورات المستخدم السابقة
        // إلى أن يكتمل نشر تحديثي التطبيق الأصلي والخادم كلٌّ على حدة.
        if (!paged && !author.isNullOrBlank()) response = get("/api/posts?limit=50")
        val array = response.optJSONArray("posts")
        val all = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
        val posts = all.filter { author.isNullOrBlank() || it.optString("author").equals(author, true) }
        val next = if (paged) response.optString("nextCursor").takeIf { it.isNotBlank() && it != "null" }
            else if (author.isNullOrBlank() && all.size >= legacyLimit && legacyLimit < 50) "legacy:${(legacyLimit + SIZE).coerceAtMost(50)}" else null
        return FeedPage(posts, next, cursor == null || !paged)
    }
    fun merge(existing: List<JSONObject>, page: FeedPage): List<JSONObject> =
        (if (page.replace) page.posts else existing + page.posts).distinctBy { it.optString("id") }
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")
}

/** يمكن أن يفشل أي جزء من دون حجب النتيجة الناجحة للجزء الآخر. */
internal suspend fun loadAccountScreen(get: suspend (String) -> JSONObject, author: String,
    account: (JSONObject) -> Unit, posts: (FeedPage) -> Unit) = supervisorScope {
    if (author.isBlank()) {
        // تحقّق من الهوية الحقيقية قبل التصفية؛ ولا تعرض الموجز العام
        // على أنه منشورات المستخدم إذا كانت جلسة محفوظة قديمة تفتقر إلى اسم المستخدم.
        val user = get("/api/me").getJSONObject("user")
        account(user)
        val username = user.getString("username").takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("تعذر تحديد الحساب. أعد المحاولة.")
        posts(FeedPages.fetch(get, author = username))
        return@supervisorScope
    }
    val results = listOf(
        async { try { account(get("/api/me").getJSONObject("user")); null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failure } },
        async { try { posts(FeedPages.fetch(get, author = author)); null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failure } }
    ).awaitAll()
    results.firstOrNull { it != null }?.let { throw it }
    Unit
}
