package com.alatta.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AL-ATTA TV is the channel chooser; AL-ATTA Media owns playback.
 * No YTV UI is launched, and no URL or user credential crosses the handoff.
 */
public final class LauncherActivity extends Activity {
    private static final String CATALOG =
        "https://tawasol-alatta.omaratta077-4fd.workers.dev/api/football";
    private static final String TARGET_PACKAGE = "com.alatta.nativeapp";
    private static final String TARGET_ACTIVITY = "com.alatta.nativeapp.GoldPlaybackReceiverActivity";
    private static final String ACTION = "com.alatta.nativeapp.action.OPEN_GOLD_PLAYER";
    private static final String CHANNEL_ID = "com.alatta.nativeapp.extra.GOLD_SOURCE_NUMBER";
    private static final int GOLD = 0xFFE6B64D;
    private static final int PAGE = 0xFF070707;
    private static final int REQUIRED_VERSION = 374;

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Channel> channels = new ArrayList<>();
    private LinearLayout channelRows;
    private EditText search;
    private TextView state;
    private boolean loading;

    static final class Channel {
        final String id, name, category, format, number;
        Channel(String id, String name, String category, String format, String number) {
            this.id = id; this.name = name; this.category = category;
            this.format = format; this.number = number;
        }
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        createLayout();
        refresh();
    }

    @Override protected void onDestroy() {
        network.shutdownNow();
        super.onDestroy();
    }

    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private GradientDrawable background(int color, int border) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(12));
        shape.setStroke(dp(1), border);
        return shape;
    }

    private void createLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(13), dp(14), dp(10));
        root.setBackgroundColor(PAGE);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        heading.addView(logo, new LinearLayout.LayoutParams(dp(66), dp(66)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("AL-ATTA TV", 25, GOLD, true);
        titles.addView(title);
        titles.addView(text("القنوات والأرقام هنا · المشاهدة داخل العطا Media", 12, Color.LTGRAY, false));
        heading.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(heading, new LinearLayout.LayoutParams(-1, dp(77)));

        LinearLayout tools = new LinearLayout(this);
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(Color.WHITE);
        search.setHintTextColor(Color.GRAY);
        search.setHint("ابحث باسم القناة أو رقمها...");
        search.setPadding(dp(10), 0, dp(10), 0);
        search.setBackground(background(0xFF1A1A1A, 0xFF665128));
        tools.addView(search, new LinearLayout.LayoutParams(0, dp(48), 1));
        Button reload = new Button(this);
        reload.setText("تحديث");
        reload.setTextSize(13);
        reload.setAllCaps(false);
        reload.setTextColor(Color.BLACK);
        reload.setBackground(background(GOLD, GOLD));
        reload.setOnClickListener(v -> refresh());
        LinearLayout.LayoutParams reloadParams = new LinearLayout.LayoutParams(dp(86), dp(48));
        reloadParams.setMargins(dp(6), 0, 0, 0);
        tools.addView(reload, reloadParams);
        root.addView(tools, new LinearLayout.LayoutParams(-1, dp(50)));

        state = text("جارٍ تحميل القنوات...", 13, GOLD, false);
        state.setPadding(dp(6), dp(13), dp(6), dp(14));
        root.addView(state, new LinearLayout.LayoutParams(-1, dp(64)));

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        channelRows = new LinearLayout(this);
        channelRows.setOrientation(LinearLayout.VERTICAL);
        channelRows.setPadding(0, 0, 0, dp(24));
        scroller.addView(channelRows);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { drawChannels(); }
            public void afterTextChanged(Editable s) {}
        });
        setContentView(root);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void refresh() {
        if (loading || network.isShutdown()) return;
        loading = true;
        state.setText("جارٍ تحديث قائمة القنوات من خادم العطا...");
        network.execute(() -> {
            try {
                List<Channel> next = fetchCatalog();
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    channels.clear();
                    channels.addAll(next);
                    loading = false;
                    state.setText("عدد القنوات: " + channels.size() + " · اختر قناة للمشاهدة داخل العطا");
                    drawChannels();
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    loading = false;
                    state.setText("تعذر جلب القنوات. تحقق من الإنترنت ثم اضغط تحديث.");
                });
            }
        });
    }

    private static List<Channel> fetchCatalog() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(CATALOG).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Accept", "application/json");
        try {
            if (conn.getResponseCode() != 200) throw new IllegalStateException("Catalog HTTP error");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream body = conn.getInputStream()) {
                byte[] buf = new byte[8192];
                int size;
                while ((size = body.read(buf)) != -1) {
                    if (bytes.size() + size > 512 * 1024) throw new IllegalStateException("Catalog too large");
                    bytes.write(buf, 0, size);
                }
            }
            JSONObject data = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (!data.optBoolean("ok", false)) throw new IllegalStateException("Catalog unavailable");
            JSONArray entries = data.optJSONArray("channels");
            List<Channel> list = new ArrayList<>();
            if (entries == null) return list;
            for (int i = 0; i < entries.length() && i < 500; ++i) {
                JSONObject item = entries.optJSONObject(i);
                if (item == null || !item.optBoolean("enabled", false)) continue;
                String format = item.optString("format", "");
                if (!format.equals("ytv")) continue;
                String id = item.optString("id", "");
                if (!id.matches("[A-Za-z0-9._-]{1,80}")) continue;
                String name = item.optString("name", "").trim();
                if (name.isEmpty()) continue;
                String stream = item.optString("streamUrl", "");
                String number = stream.matches("[0-9]{1,19}") ? stream : "";
                try { if (Long.parseLong(number) <= 0) continue; }
                catch (NumberFormatException invalidNumber) { continue; }
                list.add(new Channel(id, name, item.optString("category", ""), format, number));
            }
            return list;
        } finally { conn.disconnect(); }
    }

    private void drawChannels() {
        if (channelRows == null) return;
        channelRows.removeAllViews();
        String filter = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int visible = 0;
        for (Channel channel : channels) {
            String keywords = channel.name + " " + channel.category + " " + channel.number;
            if (!keywords.toLowerCase(Locale.ROOT).contains(filter)) continue;
            visible++;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            row.setBackground(background(0xFF171717, 0xFF695327));
            LinearLayout labels = new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            labels.addView(text(channel.name, 17, 0xFFFFD678, true));
            String detail = channel.category + " · " + (channel.number.isEmpty() ?
                channel.format.toUpperCase(Locale.ROOT) : "رقم " + channel.number);
            labels.addView(text(detail, 12, Color.LTGRAY, false));
            row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            TextView play = text("▶", 24, GOLD, true);
            play.setGravity(Gravity.CENTER);
            row.addView(play, new LinearLayout.LayoutParams(dp(45), dp(50)));
            row.setOnClickListener(v -> openInAlatta(channel));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(9);
            channelRows.addView(row, params);
        }
        if (!loading && visible == 0) {
            TextView empty = text("لا توجد قنوات مطابقة. حاول تحديث القائمة.", 14, Color.LTGRAY, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(8), dp(24), dp(8), dp(24));
            channelRows.addView(empty);
        }
    }

    private void openInAlatta(Channel channel) {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            long version = Build.VERSION.SDK_INT >= 28 ?
                packageInfo.getLongVersionCode() : packageInfo.versionCode;
            if (version < REQUIRED_VERSION) {
                state.setText("النسخة الحالية من Media لا تدعم استقبال المحرّك الذهبي. يلزم إصدار الدمج بعد اكتماله.");
                return;
            }
        } catch (PackageManager.NameNotFoundException error) {
            state.setText("ثبّت تواصل العطا أولًا للمشاهدة.");
            return;
        }
        Intent intent = new Intent(ACTION);
        intent.setComponent(new ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY));
        intent.putExtra(CHANNEL_ID, channel.number);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException error) {
            state.setText("تعذر فتح العطا. تأكد من تثبيت تحديثه الجديد.");
        }
    }
}
