package com.videokenight.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The party queue, shared by the TV page and every phone. All methods are synchronized.
 * The line is saved on the TV, so it survives the app being closed, updated, or the TV restarting.
 */
public final class Queue {
    public static final Queue INSTANCE = new Queue();
    private static final String PREFS = "videoke", KEY = "queue";

    static final class Song {
        final long id;
        final String videoId, title, thumb, singer;
        Song(long id, String videoId, String title, String thumb, String singer) {
            this.id = id; this.videoId = videoId; this.title = title; this.thumb = thumb; this.singer = singer;
        }
        JSONObject json() throws JSONException {
            return new JSONObject().put("id", id).put("videoId", videoId).put("title", title)
                    .put("thumb", thumb).put("singer", singer);
        }
        static Song from(JSONObject o) {
            return new Song(o.optLong("id"), o.optString("videoId"), o.optString("title"),
                    o.optString("thumb"), o.optString("singer"));
        }
    }

    private final List<Song> upcoming = new ArrayList<>();
    private Song current;
    private long nextId = 1;
    private boolean paused;
    private long version = 1;     // bumps on every change, so pages can tell when to redraw
    private int volume = 100;     // 0..100, the YouTube player's volume
    private long restartSeq = 0;  // bumps when someone asks to start the song over
    private double position, duration;  // reported by the TV player, in seconds
    private boolean held;         // at startup a saved line waits: nothing plays until someone presses Start
    private SharedPreferences prefs;

    private Queue() {}

    /** Loads the saved line. Call once at app start. */
    public synchronized void init(Context ctx) {
        if (prefs != null) return;
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            JSONObject o = new JSONObject(prefs.getString(KEY, "{}"));
            nextId = Math.max(1, o.optLong("nextId", 1));
            volume = o.optInt("volume", 100);
            JSONObject cur = o.optJSONObject("current");
            current = cur == null ? null : Song.from(cur);
            JSONArray up = o.optJSONArray("upcoming");
            for (int i = 0; up != null && i < up.length(); i++) upcoming.add(Song.from(up.getJSONObject(i)));
        } catch (JSONException ignored) { }
        // Never play on startup: the saved line waits behind the full-screen QR code until someone starts it.
        held = current != null;
        version++;
    }

    private void changed() {
        version++;
        if (prefs == null) return;
        try {
            JSONArray up = new JSONArray();
            for (Song s : upcoming) up.put(s.json());
            JSONObject o = new JSONObject().put("nextId", nextId).put("volume", volume)
                    .put("current", current == null ? JSONObject.NULL : current.json()).put("upcoming", up);
            prefs.edit().putString(KEY, o.toString()).apply();
        } catch (JSONException ignored) { }
    }

    public synchronized long add(String videoId, String title, String thumb, String singer) {
        if (upcoming.size() >= 200) return -1;
        Song s = new Song(nextId++, videoId, title, thumb, singer);
        if (current == null) { current = s; position = 0; duration = 0; } else upcoming.add(s);
        changed();
        return s.id;
    }

    /** Ends the current song (finished or skipped) and starts the next one. */
    public synchronized void next(long endingId) {
        if (current != null && endingId != 0 && current.id != endingId) return;  // already moved on
        current = upcoming.isEmpty() ? null : upcoming.remove(0);
        paused = false;
        position = 0; duration = 0;
        changed();
    }

    public synchronized void remove(long id) {
        if (current != null && current.id == id) { next(id); return; }
        if (upcoming.removeIf(s -> s.id == id)) changed();
    }

    public synchronized void moveUp(long id) {
        for (int i = 1; i < upcoming.size(); i++) {
            if (upcoming.get(i).id == id) {
                upcoming.add(i - 1, upcoming.remove(i));
                changed();
                return;
            }
        }
    }

    public synchronized void togglePause() {
        if (held) { start(); return; }
        if (current != null) { paused = !paused; changed(); }
    }

    /** Starts a line that was held at startup. */
    public synchronized void start() { if (held) { held = false; paused = false; changed(); } }

    public synchronized void restart() { if (current != null) { restartSeq++; paused = false; position = 0; changed(); } }

    public synchronized void setVolume(int v) { volume = Math.max(0, Math.min(100, v)); changed(); }

    public synchronized void changeVolume(int delta) { setVolume(volume + delta); }

    /** The TV reports where the song is; not a "change", so pages don't redraw for it. */
    public synchronized void progress(long id, double pos, double dur) {
        if (current != null && current.id == id) { position = pos; duration = dur; }
    }

    public synchronized void clear() { upcoming.clear(); current = null; paused = false; held = false; changed(); }

    public synchronized JSONObject state() {
        try {
            JSONArray list = new JSONArray();
            for (Song s : upcoming) list.put(s.json());
            return new JSONObject()
                    .put("version", version)
                    .put("paused", paused)
                    .put("held", held)
                    .put("volume", volume)
                    .put("restartSeq", restartSeq)
                    .put("position", position)
                    .put("duration", duration)
                    .put("current", current == null ? JSONObject.NULL : current.json())
                    .put("upcoming", list);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
