package cn.yuusei.m3x;

import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.util.*;
import junit.framework.*;
import org.json.*;
import static cn.yuusei.m3x.PlayerApp.json;

/** Opt-in physical-device suite. Saves user state before opening UI, uses only synthetic songs. */
public final class LyricsDeviceTest extends InstrumentationTestCase {
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private PlayerApp app;
    private JSONObject savedPrefs;
    private void saveState() throws Exception {
        app = (PlayerApp)getInstrumentation().getTargetContext().getApplicationContext(); app.awaitLibrary();
        savedPrefs = new JSONObject(app.getSharedPreferences("player", 0).getAll());
        for (String name : new String[]{"library.json", "scan-report.json"}) {
            File f = new File(app.getFilesDir(), name);
            files.put(name, f.isFile() ? MusicLibrary.read(new FileInputStream(f), 64 * 1024 * 1024) : null);
        }
        JSONObject snapshot = json("prefs", savedPrefs, "files", new JSONObject());
        for (Map.Entry<String, byte[]> e : files.entrySet()) snapshot.getJSONObject("files").put(e.getKey(), e.getValue() == null ? JSONObject.NULL : android.util.Base64.encodeToString(e.getValue(), android.util.Base64.NO_WRAP));
        try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir(null), ".lyrics-device-state-050.json"))) { out.write(snapshot.toString().getBytes("UTF-8")); out.getFD().sync(); }
    }
    private void restoreState(MusicLibrary library) throws Exception {
        app.library = library;
        getInstrumentation().runOnMainSync(() -> { if (app.playback != null) { app.playback.player.pause(); app.stopService(new Intent(app, PlaybackService.class)); } });
        long until = SystemClock.elapsedRealtime() + 5000;
        while (app.playback != null && SystemClock.elapsedRealtime() < until) Thread.sleep(50);
        assertNull("Test playback service stopped before restoring user preferences", app.playback);
        SharedPreferences.Editor edit = app.getSharedPreferences("player", 0).edit().clear();
        Iterator<String> keys = savedPrefs.keys();
        while (keys.hasNext()) { String key = keys.next(); Object value = savedPrefs.get(key);
            if (value instanceof String) edit.putString(key, (String)value);
            else if (value instanceof Boolean) edit.putBoolean(key, (Boolean)value);
            else if (value instanceof Integer) edit.putInt(key, (Integer)value);
            else if (value instanceof Long) edit.putLong(key, (Long)value);
            else if (value instanceof Number) edit.putFloat(key, ((Number)value).floatValue());
            else throw new IOException("Unsupported preference type " + key);
        }
        assertTrue(edit.commit());
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            File f = new File(app.getFilesDir(), e.getKey()); new File(f + ".bak").delete();
            if (e.getValue() == null) f.delete();
            else try (FileOutputStream out = new FileOutputStream(f)) { out.write(e.getValue()); out.getFD().sync(); }
        }
        assertEquals(savedPrefs.toString(), new JSONObject(app.getSharedPreferences("player", 0).getAll()).toString());
        for (Map.Entry<String, byte[]> e : files.entrySet()) if (e.getValue() != null) assertTrue(Arrays.equals(e.getValue(), MusicLibrary.read(new FileInputStream(new File(app.getFilesDir(), e.getKey())), 64 * 1024 * 1024)));
    }
    public void testPhysicalDeviceAcceptance() throws Exception {
        saveState(); MusicLibrary original = app.library;
        JSONObject report = json("model", android.os.Build.MODEL, "android", android.os.Build.VERSION.RELEASE, "api", android.os.Build.VERSION.SDK_INT, "abi", android.os.Build.SUPPORTED_ABIS[0], "originalTracks", original.all().size());
        TestSuite suite = new TestSuite(LyricsTagTest.class);
        for (String name : new String[]{"test050InterruptedRecoveryWithoutCatalogTrack", "test050LyricsWriteRecoveryAndUi", "test050SafWriteAndFailureRecovery"}) { NativePlayerTest test = new NativePlayerTest(); test.setName(name); suite.addTest(test); }
        if (!"true".equals(((android.test.InstrumentationTestRunner)getInstrumentation()).getArguments().getString("offlineOnly"))) { suite.addTestSuite(LyricsOnlineTest.class); suite.addTestSuite(LyricsOnlineUiTest.class); }
        TestResult result = new TestResult(); JSONArray failures = new JSONArray();
        try {
            for (Enumeration<Test> tests = suite.tests(); tests.hasMoreElements();) run(tests.nextElement(), result);
            for (Enumeration<TestFailure> errors = result.errors(); errors.hasMoreElements();) failures.put(errors.nextElement().trace());
            for (Enumeration<TestFailure> errors = result.failures(); errors.hasMoreElements();) failures.put(errors.nextElement().trace());
            report.put("tests", result.runCount()).put("failures", failures);
            report.put("externalStorage", externalStorageRoundtrips());
        } finally {
            try { restoreState(original); report.put("userStateRestored", true); }
            finally { try (FileOutputStream out = new FileOutputStream(new File(app.getExternalFilesDir(null), "lyrics-device-report-050.json"))) { out.write(report.toString(2).getBytes("UTF-8")); } }
        }
        assertEquals(failures.toString(), 0, result.errorCount() + result.failureCount());
    }
    private JSONArray externalStorageRoundtrips() throws Exception {
        JSONArray entries = new JSONArray(); MusicLibrary original = app.library;
        java.lang.reflect.Method setter = MusicLibrary.class.getDeclaredMethod("setTracks", java.util.List.class); setter.setAccessible(true);
        int index = 0;
        try {
            for (File directory : app.getExternalFilesDirs(null)) {
                if (directory == null) continue;
                String id = "lyrics-device-volume-" + index++;
                File song = File.createTempFile("m3x-lyrics-validation-", ".aiff", directory);
                File journal = new File(app.getFilesDir(), "lyric-selections/" + MusicLibrary.hash(id) + ".journal");
                try {
                    AudioTagEditor.copy(getInstrumentation().getContext().getAssets().open("fixture-aiff-24.aiff"), song);
                    String before = AudioTagEditor.sha(song), audio = AudioTagEditor.payload(song), text = "[00:00.100]原文\n[00:00.100]译文\n";
                    MusicLibrary isolated = new MusicLibrary(app);
                    setter.invoke(isolated, Collections.singletonList(json("id", id, "uri", Uri.fromFile(song).toString(), "filename", song.getName(), "title", "Synthetic storage fixture", "artist", "Test", "album", "Test", "duration", 2))); app.library = isolated;
                    app.lyricManager.mutate(id, text, false, false);
                    assertEquals(text, AudioTagEditor.lyrics(song)); assertEquals(audio, AudioTagEditor.payload(song));
                    app.lyricManager.mutate(id, "", true, false); assertEquals(before, AudioTagEditor.sha(song));
                    entries.put(json("removable", android.os.Environment.isExternalStorageRemovable(directory), "format", "AIFF24", "writeAndFullRestore", true));
                } finally {
                    if (journal.isFile()) { JSONObject j = new JSONObject(new String(MusicLibrary.read(new FileInputStream(journal), 10000), "UTF-8")); new File(journal.getParentFile(), j.optString("backup")).delete(); journal.delete(); }
                    song.delete();
                }
            }
        } finally { app.library = original; }
        return entries;
    }
    private void run(Test test, TestResult result) {
        if (test instanceof TestSuite) { for (Enumeration<Test> items = ((TestSuite)test).tests(); items.hasMoreElements();) run(items.nextElement(), result); }
        else { ((InstrumentationTestCase)test).injectInstrumentation(getInstrumentation()); test.run(result); }
    }
}
