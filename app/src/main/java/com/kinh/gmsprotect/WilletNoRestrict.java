package com.kinh.gmsprotect;

import android.content.Context;

public class WilletNoRestrict {

    public enum Result {
        ADDED,
        FAILED
    }

    public static boolean canWrite(Context context) {
        return true;
    }

    public static Result ensureGms(Context context) {
        return Result.ADDED;
    }

    public static boolean containsGms(Context context) {
        return true;
    }

    public static boolean containsPlayStore(Context context) {
        return true;
    }

    public static boolean containsSelf(Context context) {
        return true;
    }
}
