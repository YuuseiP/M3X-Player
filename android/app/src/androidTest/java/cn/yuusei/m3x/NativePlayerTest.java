package cn.yuusei.m3x;

import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import org.json.*;
import java.io.*;
import java.util.concurrent.*;
import static cn.yuusei.m3x.PlayerApp.json;

public final class NativePlayerTest extends InstrumentationTestCase {
    MainActivity activity;
    PlayerApp app;
    @Override protected void setUp() throws Exception {
        super.setUp();
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        app = PlayerApp.instance;
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (app.playback == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
        assertNotNull(app.playback);
    }
    @Override protected void tearDown() throws Exception {
        getInstrumentation().runOnMainSync(() -> { if (app.playback != null) app.playback.player.pause(); if (activity != null) activity.finish(); });
        super.tearDown();
    }
    String js(String expression) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); String[] answer = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(expression, result -> { answer[0] = result; ready.countDown(); }));
        assertTrue("WebView response", ready.await(10, TimeUnit.SECONDS)); return answer[0];
    }
    void waitJs(String expression) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < deadline) { if ("true".equals(js(expression))) return; Thread.sleep(100); }
        fail("Condition timed out: " + expression + " / " + js("JSON.stringify(nativeCommand('poll'))"));
    }
    void waitScan() throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (app.library.scanStatus().getJSONObject("scan").optBoolean("running") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
        JSONObject scan = app.library.scanStatus().getJSONObject("scan");
        assertFalse("Scan timed out: " + scan, scan.optBoolean("running"));
        assertFalse(scan.toString(), scan.has("error"));
    }
    public void testManualScanSourcesPerformanceAndCancellation() throws Exception {
        JSONObject originalOptions = app.library.options();
        MusicLibrary originalLibrary = app.library;
        File catalog = new File(app.getFilesDir(), "library.json");
        byte[] originalCatalog = catalog.isFile() ? MusicLibrary.read(new FileInputStream(catalog), 64 * 1024 * 1024) : null;
        try {
            app.save("scan", json("includeMediaStore", false, "roots", new JSONArray(), "excludedFolders", new JSONArray(), "minDuration", 0));
            app.library = new MusicLibrary(app);
            java.lang.reflect.Method empty = MusicLibrary.class.getDeclaredMethod("setTracks", java.util.List.class);
            empty.setAccessible(true); empty.invoke(app.library, java.util.Collections.emptyList());
            getInstrumentation().runOnMainSync(activity::finish);
            activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(app, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            assertFalse("Launching an empty library must not scan", app.library.scanStatus().getJSONObject("scan").optBoolean("running"));
            getInstrumentation().runOnMainSync(() -> {
                activity.onRequestPermissionsResult(20, new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE}, new int[]{android.content.pm.PackageManager.PERMISSION_GRANTED});
                new StorageReceiver().onReceive(app, new Intent(Intent.ACTION_MEDIA_MOUNTED));
            });
            assertFalse("Permission and storage events must not scan", app.library.scanStatus().getJSONObject("scan").optBoolean("running"));
            File folder = new File(app.getFilesDir(), "scan-performance"); folder.mkdirs();
            File second = new File(folder, "second"); second.mkdirs();
            byte[] flac = MusicLibrary.read(getInstrumentation().getContext().getAssets().open("fixture-24-96.flac"), 1024 * 1024);
            for (int i = 0; i < 80; i++) {
                File file = new File(i < 40 ? folder : second, String.format(java.util.Locale.ROOT, "%03d.%s", i, i % 2 == 0 ? "wav" : "flac"));
                if (i % 2 == 0) {
                    try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                        output.setLength(0); DsdDecoder.wavHeader(output, 44100, 1, 16, 88200);
                        output.write(new byte[88200]);
                    }
                } else try (FileOutputStream output = new FileOutputStream(file)) { output.write(flac); }
            }
            android.graphics.Bitmap cover = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.RGB_565);
            try (FileOutputStream output = new FileOutputStream(new File(folder, "cover.jpg"))) { cover.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, output); }
            finally { cover.recycle(); }
            app.library.addRoot(Uri.fromFile(folder));
            app.library.addRoot(Uri.fromFile(second));
            app.library.addRoot(Uri.fromFile(folder));
            assertEquals("Duplicate root is not appended", 2, app.library.options().getJSONArray("roots").length());
            app.library.setOptions(json("minDuration", 0));
            assertFalse("Changing sources must not scan", app.library.scanStatus().getJSONObject("scan").optBoolean("running"));
            getInstrumentation().runOnMainSync(activity::refresh);
            waitJs("Boolean(window.M3X_UI)&&document.querySelectorAll('#scan-directories li').length===2");
            assertEquals("true", js("document.querySelectorAll('#scan-directories li small')[1].textContent.endsWith('/second')"));
            js("document.querySelector('.m3x-nav [data-page=\"profile\"]').click();document.querySelector('[data-panel=\"profile\"] [data-page=\"settings\"]').click()");
            assertEquals("true", js("document.getElementById('scan-directories').getBoundingClientRect().top>=document.querySelector('[data-action=\"choose-folder\"]').getBoundingClientRect().bottom"));
            assertEquals("true", js("document.documentElement.scrollWidth<=innerWidth"));
            js("document.getElementById('scan-directories').scrollIntoView()");
            Thread.sleep(150);
            android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try (FileOutputStream output = new FileOutputStream(new File(app.getFilesDir(), "scan-directories.png"))) { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); }
            finally { screenshot.recycle(); }
            js("document.querySelector('[data-panel=\"settings\"] [data-action=\"scan\"]').click()");
            waitScan();
            JSONObject scan = app.library.scanStatus().getJSONObject("scan");
            assertEquals(scan.toString(), 80, app.library.all().size());
            assertEquals(80, scan.getInt("processed")); assertEquals(80, scan.getInt("total")); assertEquals(0, scan.getInt("failed"));
            assertTrue("Cold scan should exceed 10 songs/s on this fixture: " + scan, scan.getDouble("rate") >= 10);
            android.util.Log.i("M3X.ScanTest", "Cold WAV/FLAC scan: " + scan);
            String firstCover = "", nextCover = "";
            for (JSONObject track : app.library.all()) {
                if (track.optString("filename").equals("000.wav")) firstCover = track.getString("cover");
                if (track.optString("filename").equals("002.wav")) nextCover = track.getString("cover");
            }
            assertTrue(firstCover.startsWith("native-covers/"));
            assertEquals(firstCover, nextCover);
            app.library.start(); waitScan();
            assertEquals(80, app.library.all().size());
            android.util.Log.i("M3X.ScanTest", "Cached WAV/FLAC scan: " + app.library.scanStatus());
            byte[] saved = MusicLibrary.read(new FileInputStream(catalog), 64 * 1024 * 1024);
            app.library.start(); app.library.stop(); waitScan();
            assertTrue(app.library.scanStatus().getJSONObject("scan").getBoolean("cancelled"));
            assertTrue("Cancellation keeps the committed catalog", java.util.Arrays.equals(saved, MusicLibrary.read(new FileInputStream(catalog), 64 * 1024 * 1024)));
            waitJs("!document.querySelector('[data-remove-root=\"1\"]').disabled");
            js("document.querySelector('[data-remove-root=\"1\"]').click()");
            waitJs("document.querySelectorAll('#scan-directories li').length===1");
            assertEquals(1, app.library.options().getJSONArray("roots").length());
            assertFalse("Removing a root must not scan", app.library.scanStatus().getJSONObject("scan").optBoolean("running"));
            js("var toggle=document.getElementById('include-media-store');toggle.checked=true;toggle.dispatchEvent(new Event('change'))");
            waitJs("document.getElementById('include-media-store').checked");
            assertTrue(app.library.options().getBoolean("includeMediaStore"));
            assertFalse("Changing MediaStore must not scan", app.library.scanStatus().getJSONObject("scan").optBoolean("running"));
        } finally {
            app.library.stop();
            long deadline = SystemClock.elapsedRealtime() + 30000;
            while (app.library.scanStatus().getJSONObject("scan").optBoolean("running") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
            app.save("scan", originalOptions); app.library = originalLibrary;
            if (originalCatalog != null) try (FileOutputStream output = new FileOutputStream(catalog)) { output.write(originalCatalog); }
            else catalog.delete();
            getInstrumentation().runOnMainSync(activity::refresh);
        }
    }
    public void testNetworkAdapters() throws Exception {
        java.net.ServerSocket server = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        String base = "http://127.0.0.1:" + server.getLocalPort();
        java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
        worker.execute(() -> {
            try {
                for (int i = 0; i < 3; i++) try (java.net.Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    String first = reader.readLine(), line; int length = 0;
                    while ((line = reader.readLine()) != null && !line.isEmpty()) if (line.toLowerCase(java.util.Locale.ROOT).startsWith("content-length:")) length = Integer.parseInt(line.substring(15).trim());
                    for (int n = 0; n < length; n++) reader.read();
                    String body;
                    if (first.startsWith("PROPFIND")) body = "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/dav/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response><d:response><d:href>/dav/music.wav</d:href><d:propstat><d:prop><d:displayname>Network song</d:displayname><d:getcontenttype>audio/wav</d:getcontenttype></d:prop></d:propstat></d:response></d:multistatus>";
                    else if (first.startsWith("POST")) body = "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><BrowseResponse><Result>&lt;DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\"&gt;&lt;item id=\"1\"&gt;&lt;dc:title&gt;DLNA song&lt;/dc:title&gt;&lt;upnp:class&gt;object.item.audioItem.musicTrack&lt;/upnp:class&gt;&lt;res&gt;" + base + "/music.wav&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</Result><TotalMatches>1</TotalMatches></BrowseResponse></s:Body></s:Envelope>";
                    else body = "[00:00.00]Network lyric";
                    byte[] bytes = body.getBytes("UTF-8");
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 " + (first.startsWith("PROPFIND") ? "207 Multi-Status" : "200 OK") + "\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n").getBytes("US-ASCII"));
                    output.write(bytes); output.flush();
                }
            } catch (Exception ignored) {}
        });
        try {
            JSONObject dav = NativeNetwork.webdav(json("url", base + "/dav/"));
            assertEquals(1, dav.getJSONArray("entries").length());
            assertEquals(base + "/dav/music.wav", dav.getJSONArray("entries").getJSONObject(0).getString("url"));
            JSONObject dlna = NativeNetwork.dlna(json("url", base + "/control", "service", "urn:schemas-upnp-org:service:ContentDirectory:1"));
            assertEquals("DLNA song", dlna.getJSONArray("entries").getJSONObject(0).getString("title"));
            assertTrue(new String(NativeNetwork.request(base + "/lyrics", "GET", null, java.util.Collections.emptyMap(), 2048), "UTF-8").contains("Network lyric"));
        } finally { server.close(); worker.shutdownNow(); }
    }
    public void testNativeLibraryPlaybackProcessingAndBackground() throws Exception {
        File folder = new File(app.getFilesDir(), "test-music"); folder.mkdirs();
        File song = new File(folder, "fixture.wav");
        try (RandomAccessFile file = new RandomAccessFile(song, "rw")) {
            file.setLength(0); DsdDecoder.wavHeader(file, 44100, 1, 16, 44100 * 2 * 6);
            for (int i = 0; i < 44100 * 6; i++) file.writeShort(Short.reverseBytes((short) (600 * Math.sin(2 * Math.PI * 440 * i / 44100))));
        }
        try (FileOutputStream file = new FileOutputStream(new File(folder, "fixture.lrc"))) { file.write("[00:00.00]Line one\n[00:02.00]Line two".getBytes("UTF-8")); }
        app.library.setOptions(json("includeMediaStore", false, "minDuration", 0, "excludedFolders", new JSONArray()));
        app.library.addRoot(Uri.fromFile(folder)); app.library.start();
        while (app.library.snapshot().getJSONObject("scan").optBoolean("running")) Thread.sleep(50);
        assertEquals(app.library.snapshot().toString(), 1, app.library.all().size());
        JSONObject songInfo = app.library.all().get(0);
        assertEquals(16, songInfo.getInt("bits")); assertEquals(44100, songInfo.getInt("sampleRate"));
        assertTrue(app.library.details(songInfo.getString("id")).getString("lyrics").contains("Line two"));
        getInstrumentation().runOnMainSync(() -> activity.refresh());
        waitJs("Boolean(window.M3X_UI)&&document.querySelectorAll('[data-list=\"library\"] [data-track]').length===1");
        assertEquals("true", js("document.querySelector('.m3x-header')===null"));
        js("document.querySelector('[data-list=\"library\"] [data-track]').click()");
        waitJs("!audio.paused&&audio.currentTime>0.2&&audio.readyState>=2");
        js("nativeCommand('configure',{eq:true,bands:[0,0,0,0,0,0,0,0,0,0],sampleRate:48000})");
        waitJs("nativeCommand('poll').outputRate===48000&&nativeCommand('poll').playing");
        getInstrumentation().runOnMainSync(() -> activity.moveTaskToBack(true));
        Thread.sleep(900);
        final JSONObject[] snapshot = {null};
        getInstrumentation().runOnMainSync(() -> snapshot[0] = app.playback.snapshot());
        assertTrue("Background service plays without visible WebView", snapshot[0].getBoolean("playing"));
        assertTrue(snapshot[0].getDouble("position") > .5);
        assertTrue(snapshot[0].getJSONObject("stats").optDouble("seconds", 0) > 0);
        getInstrumentation().runOnMainSync(() -> { app.playback.pause(); try { app.playback.configure(json("eq", false, "sampleRate", 0)); } catch (Exception error) { throw new RuntimeException(error); } });
        File clip = PcmExport.decode(app, Uri.fromFile(song), 1000, 2500, false, "fixture");
        assertTrue(Math.abs(clip.length() - (44 + 44100 * 2 * 1.5)) < 1024);
        assertNotNull(NativeNetwork.xml("<root xmlns=\"urn:test\"><title>ok</title></root>".getBytes("UTF-8")));
        getInstrumentation().runOnMainSync(() -> { try { app.playback.configure(json("reverse", true, "fades", true)); app.playback.play(); } catch (Exception error) { throw new RuntimeException(error); } });
        waitJs("nativeCommand('poll').playing&&!nativeCommand('poll').conversion");
        assertTrue(new File(app.getCacheDir(), "decoded/" + songInfo.getString("id") + "-reverse.wav").length() > 44);
        js("nativeCommand('pause')");
        waitJs("nativeCommand('poll').paused");
        assertEquals("true", js("nativeCommand('configure',{rate:0}).error!==undefined"));
        assertEquals("true", js("nativeCommand('poll').settings.rate!==0"));
        js("nativeCommand('configure',{reverse:false,fades:false})");

        File flac = new File(app.getFilesDir(), "external-fixture.flac");
        try (InputStream input = getInstrumentation().getContext().getAssets().open("fixture-24-96.flac"); OutputStream output = new FileOutputStream(flac)) {
            byte[] bytes = new byte[65536]; int count; while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
        }
        JSONObject highRes = app.library.importUri(Uri.fromFile(flac), flac.getName());
        assertEquals(24, highRes.getInt("bits")); assertEquals(96000, highRes.getInt("sampleRate"));
        js("nativeCommand('load',{id:'" + highRes.getString("id") + "',play:true})");
        waitJs("nativeCommand('poll').playing&&nativeCommand('poll').position>0.3&&nativeCommand('poll').id==='" + highRes.getString("id") + "'");
        android.util.Log.i("M3X.Test", "FLAC24/96 snapshot " + js("JSON.stringify(nativeCommand('poll'))"));
        assertEquals("true", js("nativeCommand('poll').outputRate>0"));
        js("nativeCommand('pause');nativeCommand('next')");
        waitJs("nativeCommand('poll').playing");
        getInstrumentation().runOnMainSync(() -> {
            try {
                app.playback.sleep(1, false);
                java.lang.reflect.Field deadline = PlaybackService.class.getDeclaredField("sleepDeadline");
                deadline.setAccessible(true); deadline.setLong(app.playback, SystemClock.elapsedRealtime() + 300);
            } catch (Exception error) { throw new RuntimeException(error); }
        });
        waitJs("nativeCommand('poll').paused&&nativeCommand('poll').sleepRemaining===0");
        js("nativeCommand('sleep',{minutes:1,finish:true});nativeCommand('next')");
        assertEquals("true", js("nativeCommand('poll').sleepRemaining===0"));
        js("nativeCommand('pause')");

        File cue = new File(folder, "fixture.cue");
        try (OutputStream output = new FileOutputStream(cue)) {
            output.write("TITLE \"Test Album\"\nFILE \"fixture.wav\" WAVE\nTRACK 01 AUDIO\nTITLE \"Part one\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nTITLE \"Part two\"\nINDEX 01 00:03:00\n".getBytes("UTF-8"));
        }
        app.library.start(); while (app.library.snapshot().getJSONObject("scan").optBoolean("running")) Thread.sleep(50);
        assertEquals(app.library.snapshot().toString(), 2, app.library.all().size());
        assertEquals(3d, app.library.all().get(0).getDouble("duration"));
        assertEquals(3000, app.library.all().get(1).getInt("cueStart"));
        cue.delete();
        app.library.start(); while (app.library.snapshot().getJSONObject("scan").optBoolean("running")) Thread.sleep(50);

        File dsf = new File(app.getFilesDir(), "fixture.dsf");
        java.nio.ByteBuffer header = java.nio.ByteBuffer.allocate(92).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.put("DSD ".getBytes("US-ASCII")).putLong(28).putLong(92 + 4096).putLong(0);
        header.put("fmt ".getBytes("US-ASCII")).putLong(52).putInt(1).putInt(0).putInt(1).putInt(1).putInt(2822400).putInt(1).putLong(32768).putInt(4096).putInt(0);
        header.put("data".getBytes("US-ASCII")).putLong(4108);
        try (OutputStream output = new FileOutputStream(dsf)) { output.write(header.array()); byte[] silence = new byte[4096]; java.util.Arrays.fill(silence, (byte) 0x69); output.write(silence); }
        File dsdPcm = DsdDecoder.convert(app, Uri.fromFile(dsf), "test-dsf");
        assertEquals(44 + 1024 * 3, dsdPcm.length());
        app.library.addRoot(Uri.fromFile(new File(folder, ".")));
        app.library.start(); while (app.library.snapshot().getJSONObject("scan").optBoolean("running")) Thread.sleep(50);
        assertEquals("Overlapping roots must not duplicate songs", 1, app.library.all().size());
        assertEquals(app.library.identity(Uri.fromFile(new File(android.os.Environment.getExternalStorageDirectory(), "Music/test.flac"))),
            app.library.identity(android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music/test.flac")));
        JSONObject source = app.library.all().get(0);
        long sourceBytes = song.length();
        try { NativeDocuments.perform(activity, "clip-export", Uri.fromFile(song), json("id", source.getString("id"), "start", 0, "end", 1)); fail("Do not overwrite source music"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("源歌曲")); }
        assertEquals(sourceBytes, song.length());
        File backup = new File(app.getFilesDir(), "test-backup.json");
        js("nativeCommand('configure',{rate:1.25})");
        NativeDocuments.perform(activity, "backup-export", Uri.fromFile(backup), new JSONObject());
        js("nativeCommand('configure',{rate:0.75})");
        NativeDocuments.perform(activity, "backup-import", Uri.fromFile(backup), new JSONObject());
        assertEquals("true", js("nativeCommand('poll').settings.rate===1.25"));
        js("nativeCommand('configure',{rate:1})");
        final java.util.concurrent.Future<?>[] connection = {null};
        getInstrumentation().runOnMainSync(() -> connection[0] = new androidx.media3.session.MediaController.Builder(app,
            new androidx.media3.session.SessionToken(app, new ComponentName(app, PlaybackService.class))).buildAsync());
        androidx.media3.session.MediaController controller = (androidx.media3.session.MediaController) connection[0].get(10, TimeUnit.SECONDS);
        try {
            getInstrumentation().runOnMainSync(controller::play);
            waitJs("nativeCommand('poll').playing");
            android.app.NotificationManager notifications = (android.app.NotificationManager) app.getSystemService(android.content.Context.NOTIFICATION_SERVICE);
            long deadline = SystemClock.elapsedRealtime() + 3000;
            while (notifications.getActiveNotifications().length == 0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            assertTrue("Native media notification is posted", notifications.getActiveNotifications().length > 0);
            getInstrumentation().runOnMainSync(controller::pause);
            waitJs("nativeCommand('poll').paused");
        } finally { getInstrumentation().runOnMainSync(controller::release); }
    }
}
