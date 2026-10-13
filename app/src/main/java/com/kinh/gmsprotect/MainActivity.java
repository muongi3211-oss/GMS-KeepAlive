package com.kinh.gmsprotect;

import com.kinh.gmsprotect.R;
import com.kinh.gmsprotect.BatterySetupState;
import com.gmsviet.util.XiaomiSetupState;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;

public class MainActivity extends Activity {
    private static final String PREFS = "setup_v1";
    private static final String KEY_SETUP_DONE = "setup_done";
    private static final String KEY_BATTERY_USER_CONFIRMED = "battery_user_confirmed_v1";
    private static final String KEY_DONATE_START_MS = "donate_start_ms";
    private static final String KEY_DONATE_LAUNCH_COUNT = "donate_launch_count";
    private static final String KEY_DONATE_LAST_PROMPT_MS = "donate_last_prompt_ms";
    private static final String KEY_DONATE_PROMPT_COUNT = "donate_prompt_count";

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final long DONATE_FIRST_DELAY_MS = 7L * DAY_MS;
    private static final long DONATE_REPEAT_DELAY_MS = 30L * DAY_MS;
    private static final int DONATE_MIN_LAUNCHES = 3;
    private static final int DONATE_MAX_AUTO_PROMPTS = 3;

    private GmsProtector.Result protectResult = GmsProtector.Result.FAILED;
    private int onboardingStep = 0;
    private boolean waitingAutostartSettingsReturn = false;
    private boolean waitingWriteSettingsReturn = false;
    private boolean waitingBatterySettingsReturn = false;
    private boolean donatePromptCheckedThisSession = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(UIKit.PRIMARY_DARK);
        getWindow().setNavigationBarColor(UIKit.SURFACE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        protectResult = GmsProtector.protect(this);
        AurogonAllowlistService.startIfEnabled(this);
        WilletWatchScheduler.schedule(this);

        if (isSetupDone()) {
            recordReturningLaunchForDonate();
            showMainScreen();
        } else {
            continueSetupFlow();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (waitingAutostartSettingsReturn) {
            waitingAutostartSettingsReturn = false;
            checkAutostartAfterReturn(0);
            return;
        }

        if (waitingWriteSettingsReturn) {
            waitingWriteSettingsReturn = false;
            checkWriteSettingsAfterReturn(0);
            return;
        }

        if (waitingBatterySettingsReturn) {
            waitingBatterySettingsReturn = false;
            if (isSetupDone()) showMainScreen();
            else showOnboarding(2);
        }
    }

    private void continueSetupFlow() {
        XiaomiSetupState.AutostartState autostart = XiaomiSetupState.getAutostartState(this);
        boolean autostartManuallyConfirmed = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean("autostart_manual_confirmed", false);

        if (autostart == XiaomiSetupState.AutostartState.DENIED
                || (autostart == XiaomiSetupState.AutostartState.UNKNOWN
                && !autostartManuallyConfirmed
                && !isSetupDone())) {
            showOnboarding(0);
            return;
        }

        if (!WilletNoRestrict.canWrite(this)) {
            showOnboarding(1);
            return;
        }

        if (!isBatteryUserConfirmed()) {
            showOnboarding(2);
            return;
        }

        finishSetup();
    }

    private void checkAutostartAfterReturn(int attempt) {
        XiaomiSetupState.AutostartState state = XiaomiSetupState.getAutostartState(this);
        if (state == XiaomiSetupState.AutostartState.ALLOWED) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putBoolean("autostart_manual_confirmed", true).apply();
            continueSetupFlow();
            return;
        }

        if (state == XiaomiSetupState.AutostartState.UNKNOWN) {
            showOnboarding(0);
            return;
        }

        if (attempt < 2) {
            long delayMs = attempt == 0 ? 450L : 900L;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing()) checkAutostartAfterReturn(attempt + 1);
            }, delayMs);
            return;
        }

        showOnboarding(0);
        toast(getString(R.string.autostart_not_enabled));
    }

    private void checkWriteSettingsAfterReturn(int attempt) {
        if (WilletNoRestrict.canWrite(this)) {
            WilletNoRestrict.ensureGms(this);
            continueSetupFlow();
            return;
        }

        if (attempt < 2) {
            long delayMs = attempt == 0 ? 500L : 1000L;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (!isFinishing()) checkWriteSettingsAfterReturn(attempt + 1);
            }, delayMs);
            return;
        }

        showOnboarding(1);
        toast(getString(R.string.system_access_not_granted));
    }

    private void finishSetup() {
        WilletNoRestrict.ensureGms(this);
        protectResult = GmsProtector.protect(this);
        setSetupDone(true);
        showMainScreen();
    }

    private boolean isSetupDone() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SETUP_DONE, false);
    }

    private boolean isBatteryUserConfirmed() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_BATTERY_USER_CONFIRMED, false);
    }

    private void setSetupDone(boolean done) {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        android.content.SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY_SETUP_DONE, done);
        if (done && prefs.getLong(KEY_DONATE_START_MS, 0L) == 0L) {
            editor.putLong(KEY_DONATE_START_MS, System.currentTimeMillis());
            editor.putInt(KEY_DONATE_LAUNCH_COUNT, Math.max(1, prefs.getInt(KEY_DONATE_LAUNCH_COUNT, 0)));
        }
        editor.apply();
    }

    private void recordReturningLaunchForDonate() {
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        int launches = prefs.getInt(KEY_DONATE_LAUNCH_COUNT, 0);
        prefs.edit().putInt(KEY_DONATE_LAUNCH_COUNT, launches + 1).apply();
    }

    private View buildHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(20), dp(20), dp(18));
        header.setBackground(UIKit.verticalGradient());
        UIKit.applyTopSystemInset(header);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(25f);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        header.addView(title);

        TextView sub = new TextView(this);
        sub.setText(R.string.header_subtitle);
        sub.setTextColor(0xFFFAD9FF);
        sub.setTextSize(12f);
        sub.setPadding(0, dp(3), 0, 0);
        header.addView(sub);
        return header;
    }

    private LinearLayout shell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(UIKit.BG);
        root.addView(buildHeader(), matchWidth());
        return root;
    }

    private void showOnboarding(int step) {
        onboardingStep = Math.max(0, Math.min(2, step));
        LinearLayout root = shell();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(22), dp(18), dp(28));
        UIKit.applyBottomSystemInset(page);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        TextView stepText = UIKit.muted(this, getString(R.string.step_format, onboardingStep + 1, 3), 12f);
        stepText.setGravity(Gravity.CENTER);
        page.addView(stepText, matchWidthWithBottom(dp(10)));

        LinearLayout card = UIKit.card(this);
        TextView icon = new TextView(this);
        icon.setGravity(Gravity.CENTER);
        icon.setTextSize(46f);
        icon.setTextColor(UIKit.PRIMARY);
        icon.setText(onboardingStep == 0 ? "⚡" : onboardingStep == 1 ? "🔒" : "🔋");
        card.addView(icon, matchWidthWithBottom(dp(10)));

        TextView title = new TextView(this);
        title.setGravity(Gravity.CENTER);
        title.setTextSize(22f);
        title.setTextColor(UIKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        if (onboardingStep == 0) {
            title.setText(R.string.autostart_title);
        } else if (onboardingStep == 1) {
            title.setText(R.string.system_access_title);
        } else {
            title.setText(R.string.battery_title);
        }
        card.addView(title, matchWidthWithBottom(dp(9)));

        TextView body = new TextView(this);
        if (onboardingStep == 0) {
            body.setText(R.string.autostart_body_auto);
        } else if (onboardingStep == 1) {
            body.setText(R.string.system_access_message_auto);
        } else {
            body.setText(R.string.battery_body);
        }
        body.setGravity(Gravity.CENTER);
        body.setLineSpacing(0f, 1.2f);
        card.addView(body, matchWidthWithBottom(dp(20)));

        if (onboardingStep == 0) {
            XiaomiSetupState.AutostartState state = XiaomiSetupState.getAutostartState(this);
            card.addView(UIKit.primaryButton(this, getString(R.string.autostart_button), v -> {
                waitingAutostartSettingsReturn = true;
                openAutostartSettings();
            }), matchWidthWithBottom(dp(9)));

            if (state == XiaomiSetupState.AutostartState.UNKNOWN) {
                card.addView(UIKit.secondaryButton(this, getString(R.string.autostart_manual_continue), v -> {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                            .putBoolean("autostart_manual_confirmed", true).apply();
                    continueSetupFlow();
                }), matchWidthWithBottom(dp(9)));
            }
        } else if (onboardingStep == 1) {
            card.addView(UIKit.primaryButton(this, getString(R.string.system_access_button), v ->
                    openWriteSettingsAccess()), matchWidthWithBottom(dp(9)));
        } else {
            boolean ignoringDoze = BatterySetupState.isIgnoringBatteryOptimizations(this);
            int dozeText = ignoringDoze ? R.string.battery_doze_granted : R.string.battery_doze_missing;
            TextView doze = UIKit.muted(this, getString(dozeText), 12f);
            doze.setGravity(Gravity.CENTER);
            card.addView(doze, matchWidthWithBottom(dp(10)));
            TextView warning = UIKit.muted(this, getString(R.string.battery_hyperos_check_limited), 11f);
            warning.setGravity(Gravity.CENTER);
            card.addView(warning, matchWidthWithBottom(dp(14)));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !ignoringDoze) {
                card.addView(UIKit.primaryButton(this, getString(R.string.battery_button),
                        v -> requestAndroidBatteryExemption()), matchWidthWithBottom(dp(9)));
            }
            card.addView(UIKit.secondaryButton(this, getString(R.string.battery_continue), v -> {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putBoolean(KEY_BATTERY_USER_CONFIRMED, true).apply();
                continueSetupFlow();
            }), matchWidthWithBottom(dp(9)));
        }

        page.addView(card, matchWidth());
    }

    private void showMainScreen() {
        LinearLayout root = shell();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(18), dp(16), dp(28));
        UIKit.applyBottomSystemInset(page);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        LinearLayout success = UIKit.card(this);
        success.setGravity(Gravity.CENTER_HORIZONTAL);
        success.setPadding(dp(20), dp(25), dp(20), dp(22));

        boolean ok = protectResult == GmsProtector.Result.SENT;
        TextView check = new TextView(this);
        check.setText(ok ? "✓" : "!");
        check.setTextSize(76f);
        check.setGravity(Gravity.CENTER);
        check.setTypeface(check.getTypeface(), Typeface.BOLD);
        check.setTextColor(ok ? UIKit.SUCCESS : UIKit.ERROR);
        success.addView(check, matchWidth());

        TextView status = UIKit.pill(this,
                getString(ok ? R.string.status_protected
                        : protectResult == GmsProtector.Result.GMS_MISSING ? R.string.status_unavailable : R.string.status_failed)
        );
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = Gravity.CENTER_HORIZONTAL;
        pillLp.bottomMargin = dp(12);
        success.addView(status, pillLp);

        TextView title = new TextView(this);
        if (ok) {
            title.setText(R.string.success_title);
        } else {
            if (protectResult == GmsProtector.Result.GMS_MISSING) {
                title.setText(R.string.gms_missing);
            } else {
                title.setText(R.string.protect_failed);
            }
        }
        title.setTextSize(ok ? 23f : 19f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        success.addView(title, matchWidthWithBottom(dp(9)));

        if (ok) {
            TextView body = UIKit.muted(this, getString(R.string.success_body), 13f);
            body.setGravity(Gravity.CENTER);
            body.setLineSpacing(0f, 1.2f);
            success.addView(body, matchWidthWithBottom(dp(9)));

            TextView detail = UIKit.muted(this, getString(R.string.success_detail), 11.5f);
            detail.setGravity(Gravity.CENTER);
            success.addView(detail, matchWidthWithBottom(dp(10)));

            boolean writeAllowed = WilletNoRestrict.canWrite(this);
            boolean gmsNoRestrict = WilletNoRestrict.containsGms(this);
            boolean playStoreNoRestrict = WilletNoRestrict.containsPlayStore(this);

            LinearLayout protectionNotes = new LinearLayout(this);
            protectionNotes.setOrientation(LinearLayout.VERTICAL);
            protectionNotes.setGravity(Gravity.START);

            TextView limiterStatus = UIKit.pill(this,
                    getString(gmsNoRestrict
                            ? R.string.gms_no_restrict_active
                            : writeAllowed
                            ? R.string.gms_no_restrict_missing
                            : R.string.gms_no_restrict_permission_needed),
                    gmsNoRestrict ? UIKit.SUCCESS_SOFT : UIKit.ERROR_SOFT,
                    gmsNoRestrict ? UIKit.SUCCESS : UIKit.ERROR
            );
            limiterStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams limiterLp = new LinearLayout.LayoutParams(-2, -2);
            limiterLp.gravity = Gravity.START;
            limiterLp.bottomMargin = dp(6);
            protectionNotes.addView(limiterStatus, limiterLp);

            TextView greezerStatus = UIKit.pill(this,
                    getString(R.string.greezer_protection_active),
                    UIKit.SUCCESS_SOFT,
                    UIKit.SUCCESS
            );
            greezerStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams greezerLp = new LinearLayout.LayoutParams(-2, -2);
            greezerLp.gravity = Gravity.START;
            greezerLp.bottomMargin = dp(6);
            protectionNotes.addView(greezerStatus, greezerLp);

            TextView playStoreStatus = UIKit.pill(this,
                    getString(playStoreNoRestrict
                            ? R.string.play_store_no_restrict_active
                            : writeAllowed
                            ? R.string.play_store_no_restrict_missing
                            : R.string.play_store_no_restrict_permission_needed),
                    playStoreNoRestrict ? UIKit.SUCCESS_SOFT : UIKit.ERROR_SOFT,
                    playStoreNoRestrict ? UIKit.SUCCESS : UIKit.ERROR
            );
            playStoreStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams playStoreLp = new LinearLayout.LayoutParams(-2, -2);
            playStoreLp.gravity = Gravity.START;
            protectionNotes.addView(playStoreStatus, playStoreLp);

            LinearLayout.LayoutParams notesLp = new LinearLayout.LayoutParams(-1, -2);
            notesLp.gravity = Gravity.START;
            success.addView(protectionNotes, notesLp);
        } else {
            success.addView(UIKit.primaryButton(this, getString(R.string.retry), v -> {
                protectResult = GmsProtector.protect(this);
                showMainScreen();
            }), matchWidthWithTop(dp(14)));
        }
        page.addView(success, cardMargin());

        page.addView(buildAdvancedCard(), cardMargin());
        page.addView(buildDonateCard(), cardMargin());
        page.addView(buildCompatibilityCard(), cardMargin());

        View setupCard = buildSetupCardIfNeeded();
        if (setupCard != null) {
            page.addView(setupCard, cardMargin());
        }
        page.addView(buildAppInfoCard(), cardMargin());
        page.post(this::maybeShowDonatePrompt);
    }

    private View buildSetupCardIfNeeded() {
        XiaomiSetupState.AutostartState autostart = XiaomiSetupState.getAutostartState(this);
        boolean writeAllowed = WilletNoRestrict.canWrite(this);
        boolean gmsOk = WilletNoRestrict.containsGms(this);
        boolean playStoreOk = WilletNoRestrict.containsPlayStore(this);
        boolean selfOk = WilletNoRestrict.containsSelf(this);
        boolean batteryPending = !isBatteryUserConfirmed()
                || !BatterySetupState.isIgnoringBatteryOptimizations(this);

        boolean autostartMissing = autostart != XiaomiSetupState.AutostartState.ALLOWED;
        boolean writeMissing = !writeAllowed;
        boolean milletMissing = writeAllowed && (!gmsOk || !playStoreOk || !selfOk);

        if (!autostartMissing && !writeMissing && !milletMissing && !batteryPending) {
            return null;
        }

        LinearLayout setup = UIKit.card(this);
        TextView setupTitle = new TextView(this);
        setupTitle.setText(R.string.setup);
        setupTitle.setTextSize(15f);
        setupTitle.setTextColor(UIKit.TEXT);
        setupTitle.setTypeface(setupTitle.getTypeface(), Typefac
