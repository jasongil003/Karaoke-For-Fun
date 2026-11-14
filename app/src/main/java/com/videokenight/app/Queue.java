package com.videokenight.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** The party queue, shared by the TV page and every phone. All methods are synchronized. */
public final class Queue {
    public static final Queue INSTANCE = new Queue();

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
    }

    private final List<Song> upcoming = new ArrayList<>();
    private Song current;
    private long nextId = 1;
    private boolean paused;
    private long version = 1;   // bumps on every change, so pages can tell when to redraw

    private Queue() {}

    public synchronized long add(String videoId, String title, String thumb, String singer) {
        if (upcoming.size() >= 200) return -1;
        Song s = new Song(nextId++, videoId, title, thumb, singer);
        if (current == null) current = s; else upcoming.add(s);
        version++;
        return s.id;
    }

    /** Ends the current song (finished or skipped) and starts the next one. */
    public synchronized void next(long endingId) {
        if (current != null && endingId != 0 && current.id != endingId) return;  // already moved on
        current = upcoming.isEmpty() ? null : upcoming.remove(0);
        paused = false;
        version++;
    }

    public synchronized void remove(long id) {
        if (current != null && current.id == id) { next(id); return; }
        if (upcoming.removeIf(s -> s.id == id)) version++;
    }

    public synchronized void moveUp(long id) {
        for (int i = 1; i < upcoming.size(); i++) {
            if (upcoming.get(i).id == id) {
                upcoming.add(i - 1, upcoming.remove(i));
                version++;
                return;
            }
        }
    }

    public synchronized void togglePause() { if (current != null) { paused = !paused; version++; } }

    public synchronized void clear() { upcoming.clear(); current = null; paused = false; version++; }

    public synchronized JSONObject state() {
        try {
            JSONArray list = new JSONArray();
            for (Song s : upcoming) list.put(s.json());
            return new JSONObject()
                    .put("version", version)
                    .put("paused", paused)
                    .put("current", current == null ? JSONObject.NULL : current.json())
                    .put("upcoming", list);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
