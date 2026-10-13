package com.kinh.gmsprotect;

import android.content.Context;
import android.content.SharedPreferences;

public class AuroganAllowlistService {
    private static final String PREFS = "setup_v1";
    private static final String KEY_AUROGAN_ENABLED = "aurogan_allowlist_enabled";

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUROGAN_ENABLED, false);
    }

    public static boolean setEnabled(Context context, boolean enabled) {
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUROGAN_ENABLED, enabled)
                    .apply();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void startIfEnabled(Context context) {
        if (isEnabled(context)) {
            // Logic khởi chạy dịch vụ nền nếu cần
        }
    }
}
