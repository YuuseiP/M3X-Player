package cn.yuusei.m3x;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlayerApp extends Application {
    public static PlayerApp instance;
    public final Handler main = new Handler(Looper.getMainLooper());
    public final ExecutorService jobs = Executors.newSingleThreadExecutor();
    public MusicLibrary library;
    public PlaybackService playback;
    public LyricsManager lyricManager;
    private Future<?> libraryReady;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        library = new MusicLibrary(this, false);
        lyricManager = new LyricsManager(this);
        libraryReady = jobs.submit(() -> library.load());
    }

    public void awaitLibrary() {
        try { if (libraryReady != null) libraryReady.get(); }
        catch (Exception error) { throw new IllegalStateException("Unable to load library", error); }
    }

    public JSONObject settings(String key) {
        try { return new JSONObject(getSharedPreferences("player", 0).getString(key, "{}")); }
        catch (Exception ignored) { return new JSONObject(); }
    }

    public void save(String key, JSONObject value) {
        getSharedPreferences("player", 0).edit().putString(key, value.toString()).apply();
    }

    static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        try { for (int i = 0; i < pairs.length; i += 2) object.put((String) pairs[i], pairs[i + 1]); }
        catch (Exception error) { throw new IllegalArgumentException(error); }
        return object;
    }
}
