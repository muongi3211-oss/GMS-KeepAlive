package com.kinh.gmsprotect;

import com.kinh.gmsprotect.UiKit;
import com.kinh.gmsprotect.BatterySetupState;
import com.kinh.gmsprotect.AurogonAllowlistService;
import com.kinh.gmsprotect.R;
import com.kinh.gmsprotect.XiaomiSetupState;
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

    private GmsProtector.Result protectResult = GmsProtector.Result.FAILED;
    private int onboardingStep = 0;
    private boolean waitingAutostartSettingsReturn = false;
    private boolean waitingWriteSettingsReturn = false;
    private boolean waitingBatterySettingsReturn = false;

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
        editor.apply();
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

        TextView stepText = UiKit.muted(this, getString(R.string.step_format, onboardingStep + 1, 3), 12f);
        stepText.setGravity(Gravity.CENTER);
        page.addView(stepText, matchWidthWithBottom(dp(10)));

        LinearLayout card = UiKit.card(this);
        TextView icon = new TextView(this);
        icon.setGravity(Gravity.CENTER);
        icon.setTextSize(46f);
        icon.setTextColor(UiKit.PRIMARY);
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
            card.addView(UiKit.primaryButton(this, getString(R.string.autostart_button), v -> {
                waitingAutostartSettingsReturn = true;
                openAutostartSettings();
            }), matchWidthWithBottom(dp(9)));

            if (state == XiaomiSetupState.AutostartState.UNKNOWN) {
                card.addView(UiKit.secondaryButton(this, getString(R.string.autostart_manual_continue), v -> {
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
            TextView doze = UiKit.muted(this, getString(dozeText), 12f);
            doze.setGravity(Gravity.CENTER);
            card.addView(doze, matchWidthWithBottom(dp(10)));
            TextView warning = UiKit.muted(this, getString(R.string.battery_hyperos_check_limited), 11f);
            warning.setGravity(Gravity.CENTER);
            card.addView(warning, matchWidthWithBottom(dp(14)));

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !ignoringDoze) {
                card.addView(UIKit.primaryButton(this, getString(R.string.battery_button),
                        v -> requestAndroidBatteryExemption()), matchWidthWithBottom(dp(9)));
            }
            card.addView(UiKit.secondaryButton(this, getString(R.string.battery_continue), v -> {
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
        UiKit.applyBottomSystemInset(page);
        scroll.addView(page, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        setContentView(root);

        LinearLayout success = UiKit.card(this);
        success.setGravity(Gravity.CENTER_HORIZONTAL);
        success.setPadding(dp(20), dp(25), dp(20), dp(22));

        boolean ok = protectResult == GmsProtector.Result.SENT;
        TextView check = new TextView(this);
        check.setText(ok ? "✓" : "!");
        check.setTextSize(76f);
        check.setGravity(Gravity.CENTER);
        check.setTypeface(check.getTypeface(), Typeface.BOLD);
        check.setTextColor(ok ? UiKit.SUCCESS : UIKit.ERROR);
        success.addView(check, matchWidth());

        TextView status = UiKit.pill(this,
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
            TextView body = UiKit.muted(this, getString(R.string.success_body), 13f);
            body.setGravity(Gravity.CENTER);
            body.setLineSpacing(0f, 1.2f);
            success.addView(body, matchWidthWithBottom(dp(9)));

            TextView detail = UiKit.muted(this, getString(R.string.success_detail), 11.5f);
            detail.setGravity(Gravity.CENTER);
            success.addView(detail, matchWidthWithBottom(dp(10)));

            boolean writeAllowed = WilletNoRestrict.canWrite(this);
            boolean gmsNoRestrict = WilletNoRestrict.containsGms(this);
            boolean playStoreNoRestrict = WilletNoRestrict.containsPlayStore(this);

            LinearLayout protectionNotes = new LinearLayout(this);
            protectionNotes.setOrientation(LinearLayout.VERTICAL);
            protectionNotes.setGravity(Gravity.START);

            TextView limiterStatus = UiKit.pill(this,
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
                    UiKit.SUCCESS_SOFT,
                    UiKit.SUCCESS
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
                    playStoreNoRestrict ? UiKit.SUCCESS_SOFT : UiKit.ERROR_SOFT,
                    playStoreNoRestrict ? UiKit.SUCCESS : UiKit.ERROR
            );
            playStoreStatus.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams playStoreLp = new LinearLayout.LayoutParams(-2, -2);
            playStoreLp.gravity = Gravity.START;
            protectionNotes.addView(playStoreStatus, playStoreLp);

            LinearLayout.LayoutParams notesLp = new LinearLayout.LayoutParams(-1, -2);
            notesLp.gravity = Gravity.START;
            success.addView(protectionNotes, notesLp);
        } else {
            success.addView(UiKit.primaryButton(this, getString(R.string.retry), v -> {
                protectResult = GmsProtector.protect(this);
                showMainScreen();
            }), matchWidthWithTop(dp(14)));
        }
        page.addView(success, cardMargin());

        page.addView(buildAdvancedCard(), cardMargin());
        page.addView(buildCompatibilityCard(), cardMargin());

        View setupCard = buildSetupCardIfNeeded();
        if (setupCard != null) {
            page.addView(setupCard, cardMargin());
        }
        page.addView(buildAppInfoCard(), cardMargin());
    }    private View buildSetupCardIfNeeded() {
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

        LinearLayout setup = UiKit.card(this);
        TextView setupTitle = new TextView(this);
        setupTitle.setText(R.string.setup);
        setupTitle.setTextSize(15f);
        setupTitle.setTextColor(UiKit.TEXT);
        setupTitle.setTypeface(setupTitle.getTypeface(), Typeface.BOLD);
        setup.addView(setupTitle, matchWidthWithBottom(dp(8)));

        if (autostartMissing) {
            TextView line = UiKit.muted(this, getString(R.string.autostart_setup_missing), 11.5f);
            setup.addView(line, matchWidthWithBottom(dp(8)));
            setup.addView(UiKit.primaryButton(this, getString(R.string.autostart_button), v -> {
                waitingAutostartSettingsReturn = true;
                openAutostartSettings();
            }), matchWidthWithBottom(dp(9)));
        }

        if (writeMissing) {
            TextView line = UiKit.muted(this, getString(R.string.system_access_setup_line), 11.5f);
            setup.addView(line, matchWidthWithBottom(dp(8)));
            setup.addView(UiKit.primaryButton(this, getString(R.string.system_access_button), v ->
                    openWriteSettingsAccess()), matchWidthWithBottom(dp(9)));
        } else if (milletMissing) {
            setup.addView(UiKit.primaryButton(this, getString(R.string.system_access_apply_now), v -> {
                WilletNoRestrict.Result result = WilletNoRestrict.ensureGms(this);
                protectResult = GmsProtector.protect(this);
                toast(getString(result == WilletNoRestrict.Result.ADDED
                        ? R.string.system_access_applied
                        : R.string.system_access_apply_failed));
                showMainScreen();
            }), matchWidthWithBottom(dp(9)));
        }

        if (batteryPending) {
            TextView line = UiKit.muted(this, getString(R.string.battery_setup_missing), 11.5f);
            setup.addView(line, matchWidthWithBottom(dp(8)));
            setup.addView(UiKit.secondaryButton(this, getString(R.string.battery_review),
                    v -> showOnboarding(2)), matchWidthWithBottom(dp(9)));
        }
        return setup;
    }

    private View buildCompatibilityCard() {
        LinearLayout card = UiKit.card(this);
        TextView title = new TextView(this);
        title.setText(R.string.compat_title);
        title.setTextSize(15f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        card.addView(title, matchWidthWithBottom(dp(6)));

        TextView desc = UiKit.muted(this, getString(R.string.compat_card_desc), 11f);
        desc.setLineSpacing(0f, 1.15f);
        card.addView(desc, matchWidthWithBottom(dp(11)));

        card.addView(UiKit.secondaryButton(this, getString(R.string.compat_open), v ->
                startActivity(new Intent(this, CompatibilityActivity.class))), matchWidth());
        return card;
    }

    private View buildAdvancedCard() {
        LinearLayout card = UiKit.card(this);
        TextView title = new TextView(this);
        title.setText(R.string.advanced);
        title.setTextSize(15f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        card.addView(title, matchWidthWithBottom(dp(10)));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView itemTitle = new TextView(this);
        itemTitle.setText(R.string.aurogan_allowlist_title);
        itemTitle.setTextSize(13.5f);
        itemTitle.setTextColor(UiKit.TEXT);
        itemTitle.setTypeface(itemTitle.getTypeface(), Typeface.BOLD);
        texts.addView(itemTitle, matchWidth());

        TextView desc = UiKit.muted(this, getString(R.string.aurogan_allowlist_desc), 11f);
        desc.setLineSpacing(0f, 1.15f);
        desc.setPadding(0, dp(4), dp(10), 0);
        texts.addView(desc, matchWidth());

        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(AuroganAllowlistService.isEnabled(this));
        toggle.setContentDescription(getString(R.string.aurogan_allowlist_title));
        toggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            boolean applied = AuroganAllowlistService.setEnabled(this, isChecked);
            if (!applied) {
                toast(getString(R.string.aurogan_allowlist_failed));
                showMainScreen();
                return;
            }
            toast(getString(isChecked ? R.string.aurogan_allowlist_enabled : R.string.aurogan_allowlist_disabled));
        });
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));

        card.addView(row, matchWidth());

        TextView timing = UiKit.muted(this, getString(R.string.aurogan_allowlist_timing), 10.5f);
        timing.setPadding(0, dp(10), 0, 0);
        card.addView(timing, matchWidthWithBottom(dp(12)));

        TextView batteryStatus = UiKit.muted(this,
                getString(BatterySetupState.isIgnoringBatteryOptimizations(this)
                        ? R.string.battery_doze_granted : R.string.battery_doze_missing), 11f);
        card.addView(batteryStatus, matchWidthWithBottom(dp(7)));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !BatterySetupState.isIgnoringBatteryOptimizations(this)) {
            card.addView(UiKit.secondaryButton(this, getString(R.string.battery_button),
                    v -> requestAndroidBatteryExemption()), matchWidthWithBottom(dp(9)));
        }
        return card;
    }

    private View buildAppInfoCard() {
        LinearLayout card = UiKit.card(this);
        TextView title = new TextView(this);
        title.setText(R.string.app_info);
        title.setTextSize(15f);
        title.setTextColor(UiKit.TEXT);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        card.addView(title);

        TextView dev = UiKit.muted(this, getString(R.string.developer), 12f);
        dev.setPadding(0, dp(7), 0, 0);
        card.addView(dev);
        TextView note = UiKit.muted(this, getString(R.string.about_note), 10.8f);
        note.setPadding(0, dp(6), 0, dp(9));
        card.addView(note);

    }

    private void openWriteSettingsAccess() {
        waitingWriteSettingsReturn = true;
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
            return;
        } catch (Throwable ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        } catch (Throwable ignored) {
            waitingWriteSettingsReturn = false;
            openAppDetails(getString(R.string.system_access_settings_fallback));
        }
    }

    private void requestAndroidBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        if (BatterySetupState.isIgnoringBatteryOptimizations(this)) {
            showOnboarding(2);
            return;
        }
        waitingBatterySettingsReturn = true;
        try {
            Intent request = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            request.setData(Uri.parse("package:" + getPackageName()));
            startActivity(request);
        } catch (Throwable ignored) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ignoredAgain) {
                waitingBatterySettingsReturn = false;
                toast(getString(R.string.battery_request_unavailable));
            }
        }
    }

    private void openAutostartSettings() {
        Intent action = new Intent("miui.intent.action.OP_AUTO_START");
        action.setPackage("com.miui.securitycenter");

        Intent direct = componentIntent(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
        );

        if (!tryIntents(new Intent[]{action, direct})) {
            waitingAutostartSettingsReturn = false;
            openAppDetails(getString(R.string.settings_hint_autostart));
        }
    }

    private Intent componentIntent(String packageName, String className) {
        return new Intent().setComponent(new ComponentName(packageName, className));
    }

    private boolean tryIntents(Intent[] intents) {
        for (Intent intent : intents) {
            try {
                startActivity(intent);
                return true;
            } catch (Throwable t) {}
        }
        return false;
    }

    private void openAppDetails(String hint) {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            toast(hint);
        } catch (Throwable ignored) {
            try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Throwable ignoredAgain) {}
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) { return UIKit.dp(this, value); }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams matchWidthWithBottom(int bottom) {
        LinearLayout.LayoutParams lp = matchWidth();
        lp.bottomMargin = bottom;
        return lp;
    }

    private LinearLayout.LayoutParams matchWidthWithTop(int top) {
        LinearLayout.LayoutParams lp = matchWidth();
        lp.topMargin = top;
        return lp;
    }

    private LinearLayout.LayoutParams cardMargin() {
        LinearLayout.LayoutParams lp = matchWidth();
        lp.bottomMargin = dp(14);
        return lp;
    }
}
