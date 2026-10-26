package com.alatta.stream

import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Rational
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale

/** Original Arabic Android channel directory. Sources are supplied by the app owner. */
class MainActivity : ComponentActivity() {
    private val page = 0xFF0C0E14.toInt()
    private val card = 0xFF171B25.toInt()
    private val raised = 0xFF202635.toInt()
    private val gold = 0xFFD6A94F.toInt()
    private val white = 0xFFF3F1EB.toInt()
    private val muted = 0xFF9AA2B2.toInt()

    private lateinit var store: ChannelStore
    private lateinit var root: LinearLayout
    private lateinit var header: LinearLayout
    private lateinit var playerArea: LinearLayout
    private lateinit var playerFrame: FrameLayout
    private lateinit var compactRow: LinearLayout
    private lateinit var playerView: PlayerView
    private lateinit var search: EditText
    private lateinit var categoriesRow: LinearLayout
    private lateinit var tabsRow: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var toolbar: LinearLayout
    private lateinit var count: TextView
    private var selectedCategory = "الكل"
    private var selectedTab = "القنوات"
    private var nowPlaying: Channel? = null
    private var compact = false
    private var connecting = false
    private var mediaController: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = page
        window.navigationBarColor = page
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        store = ChannelStore(this)
        seedOneDemonstration()
        buildUi()
        refreshView()
        if (store.catalogUrl().isNotBlank()) downloadCatalog(store.catalogUrl(), false)
    }

    private fun seedOneDemonstration() {
        if (getSharedPreferences("app", MODE_PRIVATE).getBoolean("initialized", false)) return
        if (store.all().isEmpty()) store.save(listOf(
            Channel(
                "demo-open-hls", "فيديو تجريبي HLS", "تجريبي",
                "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                "مادة اختبار للمشغل وليست قناة تلفزيونية", true, "demo"
            )
        ))
        getSharedPreferences("app", MODE_PRIVATE).edit().putBoolean("initialized", true).apply()
    }

    private fun dp(n: Int) = (resources.displayMetrics.density * n + 0.5f).toInt()
    private fun shape(color: Int, radius: Int = 16, outline: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (outline != null) setStroke(dp(1), outline)
        }

    private fun text(content: String, size: Float = 14f, color: Int = white, bold: Boolean = false) =
        TextView(this).apply {
            text = content
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun spaces(left: Int = 0, top: Int = 0, right: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }

    private fun button(label: String, bg: Int = raised, fg: Int = white, callback: () -> Unit): TextView =
        text(label, 13f, fg, true).apply {
            gravity = Gravity.CENTER
            background = shape(bg, 12)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            setOnClickListener { callback() }
        }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(page)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        setContentView(root)

        header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val brandRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val brandColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        brandColumn.addView(text("ATTA  STREAM", 24f, gold, true))
        brandColumn.addView(text("قنواتك في مكان واحد", 12f, muted))
        brandRow.addView(brandColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val live = text("●  LIVE", 11f, gold, true).apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = shape(card, 20, 0xFF514229.toInt())
        }
        brandRow.addView(live)
        header.addView(brandRow, spaces(bottom = 13))
        root.addView(header)

        buildPlayer()

        search = EditText(this).apply {
            textSize = 14f
            setTextColor(white)
            setHintTextColor(muted)
            hint = "⌕  ابحث عن قناة..."
            singleLine = true
            background = shape(card, 14)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = refreshView()
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        root.addView(search, spaces(bottom = 12))

        val categoriesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        categoriesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        categoriesScroll.addView(categoriesRow)
        root.addView(categoriesScroll, spaces(bottom = 10))

        tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        root.addView(tabsRow, spaces(bottom = 6))
        count = text("", 11f, muted)
        root.addView(count, spaces(bottom = 8))

        scroll = ScrollView(this).apply { isFillViewport = true; isVerticalScrollBarEnabled = false }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        toolbar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val add = button("＋ قناة", gold, page) { showAddDialog() }
        toolbar.addView(add, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        val sync = button("⟳ تحديث", raised) {
            if (store.catalogUrl().isBlank()) showCatalogDialog() else downloadCatalog(store.catalogUrl(), true)
        }
        toolbar.addView(sync, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) })
        toolbar.addView(button("⚙ المصدر") { showCatalogDialog() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(toolbar, spaces(top = 8))
    }

    private fun buildPlayer() {
        playerArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = shape(card, 17, 0xFF45402E.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        root.addView(playerArea, spaces(bottom = 12))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        info.addView(text("المشغل المباشر", 10f, muted))
        val title = text("", 14f, white, true).also { playerTitle = it }
        info.addView(title)
        row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(button("▁", raised) { toggleCompact() })
        row.addView(button("▣", raised) { startPictureInPicture() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        row.addView(button("✕", raised) { stopPlayer() }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(5) })
        playerArea.addView(row, spaces(bottom = 8))

        playerFrame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); clipToOutline = true; background = shape(Color.BLACK, 12) }
        playerView = PlayerView(this).apply {
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setBackgroundColor(Color.BLACK)
        }
        playerFrame.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val widthPx = resources.displayMetrics.widthPixels - dp(32)
        val videoHeight = (widthPx * 9f / 16).toInt().coerceAtMost(dp(270))
        playerArea.addView(playerFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, videoHeight))

        compactRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        compactRow.addView(text("▶  اضغط لفتح المشغل", 13f, gold), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        compactRow.addView(button("فتح", raised) { toggleCompact() })
        playerArea.addView(compactRow, spaces(top = 4))
    }

    private lateinit var playerTitle: TextView

    private fun refreshView() {
        if (!::list.isInitialized) return
        val all = store.all()
        val categories = listOf("الكل") + all.map { it.category }.distinct()
        if (selectedCategory !in categories) selectedCategory = "الكل"
        categoriesRow.removeAllViews()
        categories.forEach { category ->
            val selected = selectedCategory == category
            val chip = button(category, if (selected) gold else raised, if (selected) page else white) {
                selectedCategory = category; refreshView()
            }
            categoriesRow.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dp(7)
            })
        }
        tabsRow.removeAllViews()
        listOf("القنوات", "المفضلة", "الأخيرة").forEach { tab ->
            val selected = selectedTab == tab
            val tabButton = text(tab, 14f, if (selected) gold else muted, selected).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { selectedTab = tab; refreshView() }
            }
            tabsRow.addView(tabButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val searchText = search.text.toString().trim().lowercase(Locale.ROOT)
        val favs = store.favorites()
        val recents = store.recent()
        val visible = all.filter { channel ->
            (selectedCategory == "الكل" || channel.category == selectedCategory) &&
                (searchText.isBlank() || (channel.name + " " + channel.category).lowercase(Locale.ROOT).contains(searchText)) &&
                when (selectedTab) {
                    "المفضلة" -> channel.id in favs
                    "الأخيرة" -> channel.id in recents
                    else -> true
                }
        }.let { channels -> if (selectedTab == "الأخيرة") channels.sortedBy { recents.indexOf(it.id) } else channels }
        count.text = "${visible.size} قناة    •    مصادر تضيفها بنفسك أو تستوردها بإذن"
        list.removeAllViews()
        if (visible.isEmpty()) {
            val placeholder = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = shape(card, 20)
                setPadding(dp(18), dp(34), dp(18), dp(34))
            }
            placeholder.addView(text("◈", 32f, gold, true))
            placeholder.addView(text("لا توجد قنوات هنا", 19f, white, true))
            placeholder.addView(text("أضف مصدر بث مصرحًا به أو استورد قائمة القنوات الخاصة بك", 12f, muted).apply { gravity = Gravity.CENTER })
            list.addView(placeholder, spaces(top = 10))
            return
        }
        visible.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { channel ->
                row.addView(channelCard(channel), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), dp(4), dp(3), dp(4))
                })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            list.addView(row)
        }
    }

    private fun channelCard(channel: Channel): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(card, 18, 0xFF2B303E.toInt())
            setPadding(dp(14), dp(13), dp(14), dp(11))
            isClickable = true
            setOnClickListener { play(channel) }
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(text("◉", 12f, gold, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val star = text(if (store.isFavorite(channel.id)) "★" else "☆", 25f, gold).apply {
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            setOnClickListener { store.toggleFavorite(channel.id); refreshView() }
        }
        top.addView(star)
        wrapper.addView(top)
        val iconTile = FrameLayout(this).apply {
            background = shape(raised, 18)
        }
        val letter = channel.name.trim().firstOrNull()?.toString() ?: "▶"
        iconTile.addView(text(letter, 35f, gold, true).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        wrapper.addView(iconTile, spaces(top = 6, bottom = 13).apply { height = dp(88) })
        wrapper.addView(text(channel.name, 15f, white, true).apply { maxLines = 1 })
        wrapper.addView(text(channel.category, 11f, muted).apply { maxLines = 1 }, spaces(top = 3))
        wrapper.addView(text(if (channel.origin == "demo") "تجربة مشغل" else "▶ تشغيل", 11f, gold, true), spaces(top = 10))
        wrapper.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle(channel.name)
                .setMessage("هل تريد حذف القناة من قائمتك؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف") { _, _ -> store.remove(channel.id); refreshView() }
                .show()
            true
        }
        return wrapper
    }

    private fun play(channel: Channel) {
        val player = mediaController
        if (player == null) {
            toast("المشغل بيتصل... جرّب بعد لحظة")
            return
        }
        try {
            val url = Uri.parse(channel.streamUrl)
            if (url.scheme != "https") { toast("رابط غير آمن"); return }
            val type = when (url.path?.lowercase(Locale.ROOT)?.substringAfterLast('.') ?: "") {
                "m3u8" -> MimeTypes.APPLICATION_M3U8
                "mpd" -> MimeTypes.APPLICATION_MPD
                else -> null
            }
            val media = MediaItem.Builder()
                .setUri(url)
                .setMediaId(channel.id)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(channel.name).setArtist("عطا ستريم").build())
                .apply { if (type != null) setMimeType(type) }
                .build()
            player.setMediaItem(media)
            player.prepare()
            player.play()
            nowPlaying = channel
            store.markViewed(channel.id)
            compact = false
            playerArea.visibility = View.VISIBLE
            playerTitle.text = channel.name
            playerFrame.visibility = View.VISIBLE
            compactRow.visibility = View.GONE
            refreshView()
        } catch (e: Exception) { toast("تعذر بدء التشغيل: ${e.localizedMessage}") }
    }

    private fun toggleCompact() {
        compact = !compact
        playerFrame.visibility = if (compact) View.GONE else View.VISIBLE
        compactRow.visibility = if (compact) View.VISIBLE else View.GONE
    }

    private fun stopPlayer() {
        mediaController?.stop()
        mediaController?.clearMediaItems()
        nowPlaying = null
        compact = false
        playerArea.visibility = View.GONE
        refreshView()
    }

    private fun startPictureInPicture() {
        if (nowPlaying == null || compact || mediaController?.isPlaying != true) {
            toast("شغّل القناة وافتح المشغل الأول")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !isInPictureInPictureMode) {
            try {
                enterPictureInPictureMode(PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9)).build())
            } catch (_: Exception) { toast("الجهاز لا يدعم النافذة العائمة حاليًا") }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!::root.isInitialized) return
        header.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        search.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        categoriesRow.parent?.let { (it as View).visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE }
        tabsRow.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        count.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        scroll.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        toolbar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        if (isInPictureInPictureMode) {
            playerFrame.visibility = View.VISIBLE
            compactRow.visibility = View.GONE
            playerArea.setPadding(0, 0, 0, 0)
            playerArea.getChildAt(0)?.visibility = View.GONE // hide player title and buttons
            root.setPadding(0, 0, 0, 0)
        } else {
            playerArea.setPadding(dp(8), dp(8), dp(8), dp(8))
            playerArea.getChildAt(0)?.visibility = View.VISIBLE
            playerFrame.visibility = if (compact) View.GONE else View.VISIBLE
            compactRow.visibility = if (compact) View.VISIBLE else View.GONE
            root.setPadding(dp(16), dp(10), dp(16), dp(10))
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (nowPlaying != null && !compact && mediaController?.isPlaying == true) startPictureInPicture()
    }

    private fun showAddDialog() {
        val inputs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), 0) }
        val name = edit("اسم القناة")
        val category = edit("التصنيف (رياضة، أخبار، تعليم...)")
        val stream = edit("رابط البث HTTPS - m3u8 أو mpd أو mp4")
        inputs.addView(name, spaces(bottom = 9)); inputs.addView(category, spaces(bottom = 9)); inputs.addView(stream)
        val dialog = AlertDialog.Builder(this)
            .setTitle("إضافة قناة مصرح بها")
            .setView(inputs)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("إضافة", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    store.add(name.text.toString().trim(), category.text.toString().trim(), stream.text.toString().trim())
                    refreshView(); dialog.dismiss(); toast("تمت إضافة القناة")
                } catch (e: Exception) { toast(e.message ?: "راجع البيانات") }
            }
        }
        dialog.show()
    }

    private fun edit(hintText: String) = EditText(this).apply {
        hint = hintText
        singleLine = true
        textSize = 13f
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = shape(0xFFECECEC.toInt(), 10)
        setTextColor(Color.BLACK)
        setHintTextColor(Color.DKGRAY)
    }

    private fun showCatalogDialog() {
        val field = edit("https://your-domain.example/channels.json")
        field.setText(store.catalogUrl())
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), 0) }
        form.addView(text("أدخل رابط HTTPS لملف JSON خاص بك أو لديك تصريح باستخدامه.", 13f, white))
        form.addView(field, spaces(top = 8))
        val dialog = AlertDialog.Builder(this)
            .setTitle("مصدر قائمة القنوات")
            .setView(form)
            .setPositiveButton("تحميل", null)
            .setNegativeButton("إلغاء", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = field.text.toString().trim()
                if (!url.startsWith("https://", true)) { toast("استخدم رابط HTTPS"); return@setOnClickListener }
                dialog.dismiss()
                downloadCatalog(url, true)
            }
        }
        dialog.show()
    }

    private fun downloadCatalog(url: String, showMessages: Boolean) {
        if (connecting) return
        connecting = true
        if (showMessages) toast("جارٍ تحديث القنوات...")
        Thread {
            try {
                val remote = store.download(url)
                val local = store.all().filter { it.origin != "remote" }
                store.save((local + remote).distinctBy { it.id })
                store.setCatalogUrl(url)
                runOnUiThread {
                    connecting = false
                    refreshView()
                    if (showMessages) toast("تم التحديث: ${remote.size} قناة من المصدر")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    connecting = false
                    if (showMessages) toast("فشل التحديث: ${e.localizedMessage ?: "تحقق من الرابط"}")
                }
            }
        }.start()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                val connected = future.get()
                if (controllerFuture !== future) {
                    MediaController.releaseFuture(future)
                } else {
                    mediaController = connected
                    if (::playerView.isInitialized) playerView.player = connected
                    connected.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            toast("تعذر تشغيل الفيديو. افحص صلاحية رابط القناة واتصال الإنترنت.")
                        }
                    })
                }
            } catch (_: Exception) { toast("تعذر الاتصال بخدمة المشغل") }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        if (::playerView.isInitialized) playerView.player = null
        mediaController = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }
}
