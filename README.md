# 🎤 Karaoke For Fun

A party karaoke app for **Android TV / Google TV**. The TV shows a QR code, guests scan it with their phones,
pick a song, and join the line. Songs play one after another from YouTube karaoke videos.

No accounts, no servers, no app for guests to install: the TV app hosts a small web page on your home Wi-Fi.

## How it works

1. Open **Videoke Night** on the TV. It shows a QR code and the queue.
2. Guests scan the QR code with their phone camera (they must be on the same Wi-Fi as the TV).
3. They type their name, search for a song, and tap **Add**. Their phone shows their place in line.
4. The TV plays each song through YouTube's official embedded player, then moves to the next one.

**On the phone:** search with quick suggestion chips, add songs, see who's singing and your place in line,
pause, skip, move songs up or remove them.

**On the TV remote:** OK = pause/play · Right = skip · Up/Down = hide/show the side panel.

Videos that can't play outside YouTube are filtered out before they're shown (embeddable + syndicated only,
public, not age-restricted, not region-blocked). If a video still fails on the TV, it is skipped and
remembered so it never appears in search again.

## Requirements

### To build
| What | Version |
|---|---|
| JDK | 17 to 22 (JDK 23+ is not supported by Gradle 8.9) |
| Android SDK | Platform 34 and Build-Tools 34.x |
| Gradle | Included (the wrapper downloads Gradle 8.9) |
| YouTube Data API v3 key | Free, from Google Cloud ([how to get one](#get-a-youtube-api-key)) |

On macOS with Homebrew:
```bash
brew install openjdk@17 android-commandlinetools
sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"
```

### To run
- An Android TV / Google TV device running **Android 8.0 (API 26) or newer**
  (tested on a Chromecast with Google TV 4K, Android 14)
- The TV connected to the internet
- Guests' phones on the **same Wi-Fi network** as the TV
- **Developer options → USB debugging** (or Wireless debugging) turned on, to install with `adb`
- Optional: a Bluetooth karaoke microphone with its own speaker (the TV doesn't amplify a mic)

## Get a YouTube API key

1. Go to [console.cloud.google.com](https://console.cloud.google.com) and create a project.
2. **APIs & Services → Library →** search **YouTube Data API v3** → **Enable**.
3. **APIs & Services → Credentials → Create credentials → API key**.
4. Edit the key → **API restrictions → Restrict key →** tick only **YouTube Data API v3** → Save.

The free quota is 10,000 units a day: about **100 song searches a day** (each search costs about 101 units).
Playing videos costs nothing. Repeated searches are cached on the TV.

## Build

Give the build your key in **one** of these ways (none of them are committed to git):

```bash
# Option 1: environment variable
export YT_API_KEY=your-key-here

# Option 2: add it to local.properties (git-ignored)
echo "youtube.apiKey=your-key-here" >> local.properties

# Option 3: a file next to settings.gradle (git-ignored)
echo "your-key-here" > youtube-api-key.txt
```

Then:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17                       # your JDK 17-22
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools   # or ~/Library/Android/sdk
echo "sdk.dir=$ANDROID_HOME" >> local.properties
./gradlew assembleRelease
```

The APK is at `app/build/outputs/apk/release/app-release.apk`.

> ⚠️ The key ends up inside the APK. Don't share your built APK publicly, and keep the key restricted to the
> YouTube Data API v3. If it ever leaks, delete it in Google Cloud and create a new one.

## Install on the TV

```bash
adb connect <tv-ip>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.videokenight.app/.MainActivity
```

The app appears in the TV's app list as **Videoke Night**.

> Installing an update restarts the app and clears the current queue, so don't update mid-song.

## Project layout

```
app/src/main/java/com/videokenight/app/
  MainActivity.java   full-screen WebView for the TV page, remote control keys
  WebServer.java      tiny HTTP server on port 8080: pages, queue API, YouTube search, QR code
  Queue.java          the shared song queue
  Blocklist.java      videos that failed on this TV, hidden from future searches
app/src/main/assets/
  tv.html             TV screen: YouTube player, QR code, now singing, up next
  phone.html          guest page: name, search, add, queue
```

## Limitations

- For **personal and family use**. YouTube's terms don't allow charging for access to YouTube videos,
  so don't sell or paywall this app.
- Song quality depends on each YouTube video, and YouTube ads may play between songs.
- The queue lives in memory: it clears if the app is closed or the TV restarts.
- Guests must be on the same Wi-Fi as the TV. Anyone on that Wi-Fi can open the page and control the queue.

## Credits

Uses [ZXing](https://github.com/zxing/zxing) (Apache 2.0) for QR codes and the
[YouTube IFrame Player API](https://developers.google.com/youtube/iframe_api_reference).
