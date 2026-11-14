package com.videokenight.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Videos that failed to play on this TV. They are hidden from searches from then on. Survives restarts. */
public final class Blocklist {
    private static final String PREFS = "videoke", KEY = "bad_videos";

    private Blocklist() {}

    public static synchronized Set<String> get(Context ctx) {
        return new HashSet<>(prefs(ctx).getStringSet(KEY, new HashSet<>()));
    }

    public static synchronized void add(Context ctx, String videoId) {
        Set<String> s = get(ctx);
        if (s.size() > 2000) s.clear();
        s.add(videoId);
        prefs(ctx).edit().putStringSet(KEY, s).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
