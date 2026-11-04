package com.alatta.tv;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class LauncherActivity extends Activity {
    // The working split APK stays installed and untouched, including its signature and native libs.
    private static final ComponentName PLAYER = new ComponentName(
        "com.ytv.pronew", "dev.google.ytvplayer.ui.splash.SplashActivity");
    private static final int GOLD = Color.rgb(240, 194, 84);
    private TextView status;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        showGoldScreen();
        if (getIntent() != null && getIntent().hasExtra("id")) {
            long channel = getIntent().getLongExtra("id", -1L);
            int type = getIntent().getIntExtra("type", 1);
            if (channel > 0 && type == 1) {
                // Forward the original ACTION_VIEW contract without reconstructing URLs.
                openOriginal(channel, true);
            } else {
                status.setText("رقم القناة غير صحيح");
            }
        }
    }

    private void showGoldScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(dp(22), dp(32), dp(22), dp(32));
        root.setBackgroundColor(Color.rgb(7, 7, 7));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.mipmap.ic_launcher);
        root.addView(logo, new LinearLayout.LayoutParams(dp(220), dp(220)));

        TextView title = new TextView(this);
        title.setText("AL-ATTA TV");
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(GOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, fullWidth(dp(64)));

        TextView details = new TextView(this);
        details.setText("واجهة العطا الذهبية\nمشغّل القنوات العامل يظل مثبتًا بدون أي تعديل");
        details.setTextSize(13f);
        details.setTextColor(Color.LTGRAY);
        details.setGravity(Gravity.CENTER);
        root.addView(details, fullWidth(dp(85)));

        Button open = new Button(this);
        open.setAllCaps(false);
        open.setText("فتح مشغّل القنوات");
        open.setTextSize(17f);
        open.setTextColor(Color.BLACK);
        GradientDrawable background = new GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            new int[] { Color.rgb(166, 113, 29), GOLD, Color.rgb(178, 126, 36) });
        background.setCornerRadius(dp(14));
        open.setBackground(background);
        open.setOnClickListener(v -> openOriginal(-1L, false));
        root.addView(open, fullWidth(dp(56)));

        status = new TextView(this);
        status.setTextSize(12f);
        status.setTextColor(GOLD);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(20), 0, 0);
        status.setText("لا يعيد هذا التطبيق توقيع YTV ولا يغيّر روابط البث");
        root.addView(status, fullWidth(dp(84)));

        setContentView(root);
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void openOriginal(long channelId, boolean finishAfterLaunch) {
        Intent forward = new Intent(Intent.ACTION_VIEW);
        forward.setComponent(PLAYER);
        if (channelId > 0) {
            forward.putExtra("id", channelId);
            forward.putExtra("type", 1);
        }
        try {
            startActivity(forward);
            if (finishAfterLaunch) finish();
        } catch (ActivityNotFoundException | SecurityException error) {
            status.setText("المشغّل الأصلي غير مثبت أو لا يسمح بالفتح. ثبّت النسخة التي نجح فيها البث.");
        } catch (RuntimeException error) {
            status.setText("تعذر فتح المشغّل العامل. جرّبه مباشرة من أيقونته.");
        }
    }
}
