package cn.yuusei.m3x;

import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Performance probe that never scans or replaces the device's music library. */
public final class UiPerformanceTest extends InstrumentationTestCase {
    private MainActivity activity;

    private String js(String expression) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        String[] result = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(expression, value -> {
            result[0] = value; ready.countDown();
        }));
        assertTrue("WebView response", ready.await(15, TimeUnit.SECONDS));
        return result[0];
    }

    private void waitJs(String expression) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if ("true".equals(js(expression))) return;
            Thread.sleep(100);
        }
        fail("Timed out: " + expression);
    }

    public void testDeviceNavigationAndSheets() throws Exception {
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(
            getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        org.json.JSONObject savedUi = PlayerApp.instance.settings("ui");
        try {
            waitJs("!!window.M3X_UI");
            MusicLibrary library = PlayerApp.instance.library;
            assertEquals("Startup does not rewrite listening history", savedUi.optString("history"), PlayerApp.instance.settings("ui").optString("history"));
            String savedId = savedUi.optString("trackId");
            if (library.track(savedId) != null && !savedUi.optBoolean("queueCleared")) {
                assertEquals(org.json.JSONObject.quote(savedId), js("nativeCommand('poll').id"));
                if (savedUi.optBoolean("rememberPosition", true)) assertEquals(
                    savedUi.optDouble("position", 0), Double.parseDouble(js("nativeCommand('poll').position")), .01);
            }
            for (String locator : new String[]{
                "content://com.android.externalstorage.documents/document/primary%3AMusic%2F%E6%AD%8C%2Ba.flac",
                "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2F%E6%AD%8C%2Ba.flac"
            }) {
                android.net.Uri uri = android.net.Uri.parse(locator);
                assertTrue(library.isDocumentUri(uri));
                assertEquals(android.provider.DocumentsContract.getDocumentId(uri), library.classificationPath(uri));
            }
            assertFalse(library.isDocumentUri(android.net.Uri.parse("content://media/external/audio/media/1")));
            assertFalse(library.isDocumentUri(android.net.Uri.parse("file:///storage/Music/song.flac")));
            android.util.Log.i("M3X.Perf", "device tracks=" + PlayerApp.instance.library.all().size());
            android.util.Log.i("M3X.Perf", "navigation milliseconds=" + js(
                "(function(){var times=[];['profile','player','library','profile','player','library'].forEach(function(page){"
                + "var t=performance.now();document.querySelector('.m3x-nav [data-page=\"'+page+'\"]').click();"
                + "times.push(Math.round(performance.now()-t));});return times;})()"));
            js("window.tapLatency=null;window.tapStart=0;document.addEventListener('touchend',function(){tapStart=performance.now();},true);"
                + "document.addEventListener('click',function(){if(tapStart){window.clickDelay=performance.now()-tapStart;"
                + "requestAnimationFrame(function(){tapLatency=performance.now()-tapStart;tapStart=0;});}},true)");
            for (String page : new String[]{"profile", "player", "library"}) {
                Thread.sleep(300);
                org.json.JSONArray point = new org.json.JSONArray(js(
                    "(function(){var r=document.querySelector('.m3x-nav [data-page=\"" + page + "\"]').getBoundingClientRect();"
                    + "window.tapLatency=null;return [(r.left+r.width/2)*devicePixelRatio,(r.top+r.height/2)*devicePixelRatio];})()"));
                int[] origin = new int[2];
                getInstrumentation().runOnMainSync(() -> activity.web.getLocationOnScreen(origin));
                float x = (float) point.getDouble(0) + origin[0], y = (float) point.getDouble(1) + origin[1];
                long down = SystemClock.uptimeMillis();
                for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) {
                    android.view.MotionEvent event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
                    event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                    try { assertTrue(getInstrumentation().getUiAutomation().injectInputEvent(event, true)); }
                    finally { event.recycle(); }
                }
                waitJs("tapLatency!==null");
                double latency = Double.parseDouble(js("tapLatency"));
                android.util.Log.i("M3X.Perf", page + " touch-release to frame=" + latency + " ms click-delay=" + js("clickDelay"));
                assertTrue(page + " response " + latency + " ms", latency < 350);
            }
            android.util.Log.i("M3X.Perf", "category milliseconds=" + js(
                "(function(){var times=[];['characters','groups','artists','albums','folders','all'].forEach(function(filter){"
                + "var t=performance.now();document.querySelector('[data-filter=\"'+filter+'\"]').click();"
                + "times.push(Math.round(performance.now()-t));});return times;})()"));
            js("document.querySelector('.m3x-nav [data-page=\"player\"]').click();document.querySelector('.m3x-output').click()");
            waitJs("document.getElementById('output-dialog').open");
            Thread.sleep(300);
            capture("performance-sheet.png");
            js("document.querySelector('[data-action=\"close-output\"]').click()");
            waitJs("!document.getElementById('output-dialog').open");
            js("document.querySelector('.m3x-nav [data-page=\"library\"]').click()");
            Thread.sleep(500);
            capture("performance-library.png");
        } finally {
            getInstrumentation().runOnMainSync(() -> activity.finish());
        }
    }

    private void capture(String name) throws Exception {
        android.graphics.Bitmap image = getInstrumentation().getUiAutomation().takeScreenshot();
        try (FileOutputStream output = new FileOutputStream(new File(PlayerApp.instance.getExternalFilesDir(null), name))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } finally { image.recycle(); }
    }
}
