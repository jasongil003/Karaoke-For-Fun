package com.videokenight.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** A tiny HTTP server on the TV: serves the phone page, the TV page, the queue API and the QR code. */
public final class WebServer {
    public static final int PORT = 8080;
    private static final String TAG = "KaraokeForFun";
    private static WebServer instance;

    private final Context ctx;
    private final ExecutorService pool = Executors.newFixedThreadPool(8);
    private ServerSocket server;
    private byte[] qrPng;
    private String qrFor;

    // Searches cost quota (about 100 a day on the free key), so remember recent ones.
    private final Map<String, String> searchCache = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, String> e) { return size() > 300; }
            });

    private WebServer(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    public static synchronized WebServer start(Context ctx) {
        if (instance == null) {
            instance = new WebServer(ctx);
            new Thread(instance::loop, "videoke-http").start();
        }
        return instance;
    }

    /** The TV's address on the home Wi-Fi (not Tailscale's 100.x), for the QR code. */
    public static String lanAddress() {
        String fallback = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    String ip = a.getHostAddress();
                    if (a.isSiteLocalAddress()) {
                        if (ni.getName().startsWith("wlan") || ni.getName().startsWith("eth")) return ip;
                        if (fallback == null) fallback = ip;
                    }
                }
            }
        } catch (Exception ignored) { }
        return fallback;
    }

    public static String phoneUrl() {
        String ip = lanAddress();
        return ip == null ? null : "http://" + ip + ":" + PORT + "/";
    }

    private void loop() {
        while (true) {
            try {
                server = new ServerSocket(PORT);
                while (true) {
                    Socket s = server.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (IOException e) {
                Log.w(TAG, "server error, restarting", e);
                try { if (server != null) server.close(); } catch (IOException ignored) { }
                try { Thread.sleep(2000); } catch (InterruptedException ignored) { }
            }
        }
    }

    private void handle(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            InputStream in = sock.getInputStream();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = r.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String target = parts[1];
            int contentLength = 0;
            String line;
            while ((line = r.readLine()) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Content-Length")) {
                    try { contentLength = Integer.parseInt(line.substring(c + 1).trim()); } catch (NumberFormatException ignored) { }
                }
            }
            String body = "";
            if (contentLength > 0 && contentLength < 65536) {
                char[] buf = new char[contentLength];
                int read = 0;
                while (read < contentLength) {
                    int n = r.read(buf, read, contentLength - read);
                    if (n < 0) break;
                    read += n;
                }
                body = new String(buf, 0, read);
            }
            String path = target;
            Map<String, String> query = new HashMap<>();
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                for (String kv : target.substring(q + 1).split("&")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) query.put(dec(kv.substring(0, eq)), dec(kv.substring(eq + 1)));
                }
            }
            route(sock.getOutputStream(), method, path, query, body);
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        }
    }

    private void route(OutputStream out, String method, String path, Map<String, String> q, String body) throws Exception {
        Queue queue = Queue.INSTANCE;
        switch (path) {
            case "/": case "/index.html":
                asset(out, "phone.html", "text/html; charset=utf-8"); return;
            case "/tv":
                asset(out, "tv.html", "text/html; charset=utf-8"); return;
            case "/qr.png":
                send(out, 200, "image/png", qr()); return;
            case "/api/state": {
                JSONObject st = queue.state();
                st.put("url", phoneUrl() == null ? JSONObject.NULL : phoneUrl());
                json(out, st); return;
            }
            case "/api/search":
                json(out, new JSONObject(search(q.getOrDefault("q", "")))); return;
            case "/api/suggest":
                json(out, new JSONObject().put("items", suggest(q.getOrDefault("q", "")))); return;
        }
        if (!"POST".equals(method)) { send(out, 404, "text/plain", "not found".getBytes()); return; }
        JSONObject b = body.isEmpty() ? new JSONObject() : new JSONObject(body);
        switch (path) {
            case "/api/add": {
                String vid = b.optString("videoId");
                if (!vid.matches("[A-Za-z0-9_-]{6,20}")) { send(out, 400, "text/plain", "bad video".getBytes()); return; }
                long id = queue.add(vid, clip(b.optString("title"), 120), clip(b.optString("thumb"), 300),
                        clip(b.optString("singer", "Guest"), 30));
                json(out, new JSONObject().put("ok", id > 0).put("id", id)); return;
            }
            case "/api/bad": {   // the TV could not play this video: never offer it again
                String vid = b.optString("videoId");
                if (vid.matches("[A-Za-z0-9_-]{6,20}")) { Blocklist.add(ctx, vid); searchCache.clear(); }
                break;
            }
            case "/api/next": queue.next(b.optLong("id")); break;
            case "/api/remove": queue.remove(b.optLong("id")); break;
            case "/api/up": queue.moveUp(b.optLong("id")); break;
            case "/api/pause": {   // mode: toggle (default), play, pause
                String mode = b.optString("mode", "toggle"), by = clip(b.optString("by"), 40);
                if ("play".equals(mode)) queue.setPaused(false, by);
                else if ("pause".equals(mode)) queue.setPaused(true, by);
                else queue.togglePause(by);
                break;
            }
            case "/api/restart": queue.restart(); break;
            case "/api/start": queue.start(); break;
            case "/api/volume":
                if (b.has("value")) queue.setVolume(b.optInt("value")); else queue.changeVolume(b.optInt("delta"));
                break;
            case "/api/progress":   // the TV player reports its position; small reply
                queue.progress(b.optLong("id"), b.optDouble("pos", 0), b.optDouble("dur", 0));
                json(out, new JSONObject().put("ok", true));
                return;
            case "/api/clear": queue.clear(); break;
            default: send(out, 404, "text/plain", "not found".getBytes()); return;
        }
        json(out, queue.state());
    }

    private String search(String term) throws Exception {
        term = term.trim();
        if (term.isEmpty()) return "{\"items\":[]}";
        String key = term.toLowerCase();
        String cached = searchCache.get(key);
        if (cached != null) return cached;
        String qs = term.toLowerCase().contains("karaoke") || term.toLowerCase().contains("videoke") ? term : term + " karaoke";
        // videoEmbeddable + videoSyndicated: only videos the owner allows outside youtube.com.
        URL url = new URL("https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=25"
                + "&videoEmbeddable=true&videoSyndicated=true&regionCode=" + REGION
                + "&safeSearch=moderate&q=" + URLEncoder.encode(qs, "UTF-8")
                + "&key=" + BuildConfig.YT_KEY);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        int code = c.getResponseCode();
        String raw = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        JSONObject res = new JSONObject();
        JSONArray items = new JSONArray();
        if (code >= 400) {
            String msg = "Search is not available right now.";
            if (raw.contains("quotaExceeded")) msg = "Today's search limit is used up. Try again tomorrow.";
            return res.put("items", items).put("error", msg).toString();
        }
        JSONArray src = new JSONObject(raw).optJSONArray("items");
        StringBuilder ids = new StringBuilder();
        for (int i = 0; src != null && i < src.length(); i++) {
            JSONObject id = src.getJSONObject(i).optJSONObject("id");
            if (id != null && !id.optString("videoId").isEmpty()) ids.append(ids.length() > 0 ? "," : "").append(id.optString("videoId"));
        }
        java.util.Set<String> playable = playableIds(ids.toString());
        for (int i = 0; src != null && i < src.length() && items.length() < 15; i++) {
            JSONObject it = src.getJSONObject(i);
            JSONObject sn = it.optJSONObject("snippet");
            String vid = it.optJSONObject("id") == null ? "" : it.optJSONObject("id").optString("videoId");
            if (vid.isEmpty() || sn == null) continue;
            if (playable != null && !playable.contains(vid)) continue;
            JSONObject th = sn.optJSONObject("thumbnails");
            String thumb = th != null && th.optJSONObject("medium") != null ? th.optJSONObject("medium").optString("url") : "";
            items.put(new JSONObject().put("videoId", vid).put("title", unescape(sn.optString("title")))
                    .put("channel", unescape(sn.optString("channelTitle"))).put("thumb", thumb));
        }
        String out = res.put("items", items).toString();
        searchCache.put(key, out);
        return out;
    }

    private final Map<String, JSONArray> suggestCache = Collections.synchronizedMap(
            new LinkedHashMap<String, JSONArray>(64, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, JSONArray> e) { return size() > 500; }
            });

    /**
     * Type-ahead suggestions from YouTube's public suggestion service (the one youtube.com uses). Free: it does
     * not use the API key or its daily quota. " karaoke" is added to the query and removed from the answers.
     */
    private JSONArray suggest(String term) {
        term = term.trim().toLowerCase();
        JSONArray out = new JSONArray();
        if (term.length() < 2 || term.length() > 80) return out;
        JSONArray cached = suggestCache.get(term);
        if (cached != null) return cached;
        try {
            String qs = term.contains("karaoke") || term.contains("videoke") ? term : term + " karaoke";
            URL url = new URL("https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=en&gl="
                    + REGION.toLowerCase() + "&ie=utf-8&oe=utf-8&q=" + URLEncoder.encode(qs, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            if (c.getResponseCode() >= 400) return out;
            JSONArray list = new JSONArray(readAll(c.getInputStream())).optJSONArray(1);
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; list != null && i < list.length() && out.length() < 8; i++) {
                String s = list.optString(i).replaceAll("(?i)\\b(karaoke|videoke)\\b", " ")
                        .replaceAll("\\s+", " ").trim();
                if (s.length() < 2 || !seen.add(s.toLowerCase())) continue;
                out.put(s);
            }
            suggestCache.put(term, out);
        } catch (Exception ignored) { }
        return out;
    }

    /** Country the TV is in: songs blocked there are hidden. */
    private static final String REGION = "PH";

    /**
     * Second check, one quota unit for the whole page: keeps public, embeddable, not age-restricted videos that are
     * not region-blocked here and have not failed on this TV before. Returns null if the check itself failed.
     */
    private java.util.Set<String> playableIds(String ids) {
        if (ids.isEmpty()) return new java.util.HashSet<>();
        try {
            URL url = new URL("https://www.googleapis.com/youtube/v3/videos?part=status,contentDetails&id=" + ids
                    + "&key=" + BuildConfig.YT_KEY);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() >= 400) return null;
            JSONArray arr = new JSONObject(readAll(c.getInputStream())).optJSONArray("items");
            java.util.Set<String> ok = new java.util.HashSet<>();
            java.util.Set<String> bad = Blocklist.get(ctx);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject v = arr.getJSONObject(i);
                String id = v.optString("id");
                JSONObject st = v.optJSONObject("status"), cd = v.optJSONObject("contentDetails");
                if (st == null || cd == null || bad.contains(id)) continue;
                if (!st.optBoolean("embeddable") || !"public".equals(st.optString("privacyStatus"))) continue;
                JSONObject rating = cd.optJSONObject("contentRating");
                if (rating != null && "ytAgeRestricted".equals(rating.optString("ytRating"))) continue;
                JSONObject rr = cd.optJSONObject("regionRestriction");
                if (rr != null) {
                    JSONArray blocked = rr.optJSONArray("blocked"), allowed = rr.optJSONArray("allowed");
                    if (blocked != null && blocked.toString().contains("\"" + REGION + "\"")) continue;
                    if (allowed != null && !allowed.toString().contains("\"" + REGION + "\"")) continue;
                }
                ok.add(id);
            }
            return ok;
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized byte[] qr() throws Exception {
        String url = phoneUrl();
        if (url == null) url = "http://0.0.0.0";
        if (qrPng != null && url.equals(qrFor)) return qrPng;
        Map<EncodeHintType, Object> hints = new HashMap<>();
        hints.put(EncodeHintType.MARGIN, 1);
        BitMatrix m = new QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 400, 400, hints);
        Bitmap bmp = Bitmap.createBitmap(m.getWidth(), m.getHeight(), Bitmap.Config.RGB_565);
        for (int x = 0; x < m.getWidth(); x++)
            for (int y = 0; y < m.getHeight(); y++)
                bmp.setPixel(x, y, m.get(x, y) ? Color.BLACK : Color.WHITE);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        qrPng = bos.toByteArray();
        qrFor = url;
        return qrPng;
    }

    private void asset(OutputStream out, String name, String type) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            send(out, 200, type, readBytes(in));
        }
    }

    private static void json(OutputStream out, JSONObject o) throws IOException {
        send(out, 200, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void send(OutputStream out, int code, String type, byte[] data) throws IOException {
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static String readAll(InputStream in) throws IOException {
        return in == null ? "" : new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String dec(String s) {
        try { return URLDecoder.decode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String clip(String s, int max) {
        s = s == null ? "" : s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">");
    }
}
