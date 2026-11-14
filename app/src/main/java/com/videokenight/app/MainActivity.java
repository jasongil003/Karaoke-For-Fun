package com.videokenight.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/** Full-screen TV page: the player, the queue and the QR code. The remote controls playback. */
public class MainActivity extends Activity {
    private WebView web;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        Queue.INSTANCE.init(this);
        WebServer.start(this);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.setBackgroundColor(0xFF000000);
        setContentView(web);
        // Give the server a moment to bind before the page asks it for anything.
        web.postDelayed(() -> web.loadUrl("http://127.0.0.1:" + WebServer.PORT + "/tv"), 400);
    }

    private long lastToggleAt = 0;

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean handled = isOurKey(e.getKeyCode());
        if (e.getAction() != KeyEvent.ACTION_DOWN) return handled || super.dispatchKeyEvent(e);
        // Holding a button sends repeats: act once per press, or a held OK flips pause on/off/on and a held
        // Right skips several songs.
        if (e.getRepeatCount() > 0) return handled || super.dispatchKeyEvent(e);
        String cmd = null;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                long now = android.os.SystemClock.uptimeMillis();
                if (now - lastToggleAt < 800) return true;   // a double press from one push
                lastToggleAt = now;
                cmd = "pause"; break;
            // Play and Pause signals (from the TV over HDMI-CEC, or a Bluetooth device) set the state; they never
            // toggle, so a "play" can't pause the song.
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                cmd = "play"; break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                cmd = "hold"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                cmd = "skip"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                cmd = "restart"; break;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                cmd = "toggleQr"; break;
        }
        if (cmd != null) {
            String by = keyLabel(e);
            android.util.Log.i("KaraokeForFun", "key " + KeyEvent.keyCodeToString(e.getKeyCode()) + " -> " + cmd + " from " + by);
            web.evaluateJavascript("window.remote && remote('" + cmd + "', '" + by.replace("'", "") + "')", null);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private static boolean isOurKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY: case KeyEvent.KEYCODE_MEDIA_PAUSE: case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_NEXT: case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD: case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS: case KeyEvent.KEYCODE_MEDIA_REWIND: case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return true;
        }
        return false;
    }

    /** "Remote OK", "TV (HDMI) Play", ... so the phones can show who paused. */
    private static String keyLabel(KeyEvent e) {
        String key;
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: key = "OK"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE: key = "Play/Pause"; break;
            case KeyEvent.KEYCODE_MEDIA_PLAY: key = "Play"; break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE: key = "Pause"; break;
            default: key = KeyEvent.keyCodeToString(e.getKeyCode()).replace("KEYCODE_", "");
        }
        android.view.InputDevice d = e.getDevice();
        String dev = d == null ? "" : d.getName().toLowerCase();
        String from = dev.contains("cec") ? "TV (HDMI)" : dev.contains("remote") || dev.contains("btrcu") ? "Remote"
                : dev.isEmpty() ? "TV" : "Bluetooth/other device";
        return from + " " + key;
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
