package cn.yuusei.m3x;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Opt-in appearance inventory of the installed app and its existing library. */
public final class UiAppearanceCaptureTest extends InstrumentationTestCase {
    private MainActivity activity;
    private File directory;
    private final JSONArray pages = new JSONArray();
    private String theme;
    private String mode = "light";
    private JSONObject baseline;

    private String js(String expression) throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        String[] value = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(expression, result -> {
            value[0] = result; ready.countDown();
        }));
        assertTrue("WebView response", ready.await(15, TimeUnit.SECONDS));
        return value[0];
    }

    private void waitJs(String expression) throws Exception {
        long end = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < end) {
            if ("true".equals(js(expression))) return;
            Thread.sleep(100);
        }
        fail("UI did not become ready: " + expression);
    }

    private void act(String expression) throws Exception {
        String result = js("(function(){try{" + expression + ";return 'ok';}catch(e){return String(e);}})()");
        assertEquals("Action: " + expression, "\"ok\"", result);
    }

    private String click(String selector) {
        return "document.querySelector(" + JSONObject.quote(selector) + ").click()";
    }

    private String button(String text) {
        return "Array.from(document.querySelectorAll('button')).find(function(b){return b.textContent.trim()==="
            + JSONObject.quote(text) + ";}).click()";
    }

    private void close() throws Exception {
        act("Array.from(document.querySelectorAll('dialog[open]')).reverse().forEach(function(d){M3X_UI.closeDialog(d,true);})");
    }

    private void page(String name) throws Exception {
        close();
        act(click("[data-page='" + name + "']"));
        waitJs("!document.querySelector('[data-panel=\"" + name + "\"]').hidden");
    }

    private void setting(String key, String value) throws Exception {
        act("var e=document.querySelector('[data-setting=\"" + key + "\"]');e.value="
            + JSONObject.quote(value) + ";e.dispatchEvent(new Event('change',{bubbles:true}))");
    }

    private void keepDisplayAwake() {
        getInstrumentation().runOnMainSync(() -> activity.getWindow().addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
            android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED));
    }

    private void writeManifest() throws Exception {
        JSONObject doc = new JSONObject().put("schema", 1).put("source", "ADB release instrumentation / UiAutomation.takeScreenshot")
            .put("device", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
            .put("version", BuildConfig.VERSION_NAME).put("baseline", baseline).put("pages", pages);
        try (FileOutputStream output = new FileOutputStream(new File(directory, "manifest.json"))) {
            output.write(doc.toString(2).getBytes("UTF-8"));
        }
    }

    private void capture(String name, String title, String expected) throws Exception {
        waitJs(expected);
        assertEquals("Full navigation inside viewport", "true", js("(function(){var r=document.querySelector('.m3x-nav').getBoundingClientRect();return r.bottom<=innerHeight+1&&r.height>=44})()"));
        waitJs("!document.fonts||document.fonts.status==='loaded'");
        act("window.__appearanceFrame=false;requestAnimationFrame(function(){requestAnimationFrame(function(){window.__appearanceFrame=true;});})");
        waitJs("window.__appearanceFrame");
        Thread.sleep(500);
        assertTrue("Physical display awake", ((android.os.PowerManager) activity.getSystemService(android.content.Context.POWER_SERVICE)).isInteractive());
        Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull("Physical display screenshot", bitmap);
        String filename = theme + "-" + name + ".png";
        try (FileOutputStream output = new FileOutputStream(new File(directory, filename))) {
            java.util.Set<Integer> colors = new java.util.HashSet<>();
            for (int y=0;y<bitmap.getHeight();y+=24) for (int x=0;x<bitmap.getWidth();x+=24) colors.add(bitmap.getPixel(x,y));
            assertTrue("Physical screenshot is not blank", colors.size()>8);
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            pages.put(new JSONObject().put("id", theme + "-" + name).put("title", title).put("theme", theme)
                .put("file", filename).put("status", "captured").put("width", bitmap.getWidth()).put("height", bitmap.getHeight())
                .put("capturedAt", System.currentTimeMillis()).put("assertion", expected));
        } finally { bitmap.recycle(); }
        writeManifest();
        android.util.Log.i("M3X.Appearance", filename);
    }

    private void unavailable(String name, String title, String reason) throws Exception {
        pages.put(new JSONObject().put("id", theme + "-" + name).put("title", title).put("theme", theme)
            .put("status", "unavailable").put("reason", reason));
        writeManifest();
    }

    private void sheet(String name, String title, String action, String selector) throws Exception {
        act(action);
        capture(name, title, "!!document.querySelector(" + JSONObject.quote(selector) + ")");
        close();
    }

    private void inventory() throws Exception {
        setting("theme", mode); setting("visualTheme", "classic"); setting("colorMode", "fixed"); setting("layout", "album");
        act(click("[data-library-view=list]"));
        page("library");
        for (String filter : new String[]{"all", "characters", "groups", "artists", "albums", "folders"}) {
            act(click("[data-filter='" + filter + "']"));
            capture("library-" + filter, "曲库 · " + filter, "document.querySelector('[data-filter=\"" + filter + "\"]').getAttribute('aria-pressed')==='true'");
            if (!filter.equals("all")) {
                if ("true".equals(js("!!document.querySelector('#library-browser [data-group]')"))) {
                    act(click("#library-browser [data-group]"));
                    capture("detail-" + filter, "分类详情 · " + filter, "!document.getElementById('group-back').hidden");
                    act(click("[data-action='group-back']"));
                } else unavailable("detail-" + filter, "分类详情 · " + filter, "当前真实曲库没有此分类内容");
            }
        }
        act(click("[data-filter='all']"));
        act("var e=document.querySelector('[data-panel=library] .m3x-search');e.value='a';e.dispatchEvent(new Event('input',{bubbles:true}))");
        capture("search", "曲库搜索", "document.querySelector('[data-panel=library] .m3x-search').value==='a'");
        act("var e=document.querySelector('[data-panel=library] .m3x-search');e.value='';e.dispatchEvent(new Event('input',{bubbles:true}))");
        page("player"); capture("player-album", "播放 · 专辑沉浸", "!document.querySelector('[data-panel=player]').hidden");
        setting("layout", "catalog");
        capture("player-catalog", "播放 · 曲库优先", "document.querySelector('.m3x-screen').dataset.layout==='catalog'");
        setting("layout", "album");
        page("queue"); capture("queue", "播放队列", "!document.querySelector('[data-panel=queue]').hidden");
        page("lyrics"); capture("lyrics", "歌词", "!document.querySelector('[data-panel=lyrics]').hidden");
        if ("true".equals(js("!!M3X_UI.currentTrack()"))) {
            act(click("#lyrics-more"));
            sheet("lyrics-online", "搜索与保存歌词（未提交）", button("搜索／更换歌词"), "dialog[open][aria-label='搜索与保存歌词']");
            page("player");
            sheet("song-menu", "歌曲操作", click("[data-action='song-menu']"), "#song-menu-dialog[open]");
            act(click("[data-action='song-menu']"));
            sheet("song-info", "歌曲信息", click("[data-action='song-info']"), "#info-dialog[open]");
            act(click("[data-action='song-menu']"));
            sheet("clip", "导出片段（未导出）", button("导出歌曲片段"), "dialog[open][aria-label='导出 PCM WAV 片段']");
            act(click("[data-action='song-menu']"));
            sheet("lyrics-import", "网址导入歌词（未读取）", button("从网址导入歌词"), "dialog[open][aria-label='导入 LRC 歌词']");
            sheet("add-playlist", "添加到歌单（未保存）", click("[data-action='add-playlist']"), "#playlist-dialog[open]");
        } else unavailable("song-actions", "歌曲相关操作", "尚无当前歌曲；不为截图更换播放曲目");
        page("player"); act(click("[data-action='output']")); act(click("[data-sheet-tab='signal']"));
        capture("output", "输出链路", "document.getElementById('output-dialog').open&&!document.getElementById('signal-panel').hidden");
        act(click("[data-sheet-tab='processing']"));
        capture("processing", "声音处理", "!document.getElementById('processing-panel').hidden");
        act("document.getElementById('processing-panel').scrollTop=document.getElementById('processing-panel').scrollHeight");
        capture("processing-bottom", "声音处理 · 下半部", "!document.getElementById('processing-panel').hidden");
        sheet("peq", "参数均衡器（未应用）", button("参数均衡器 · 32 段"), "dialog[open][aria-label='参数均衡器']");
        act(click("[data-action='output']")); act(click("[data-sheet-tab='processing']"));
        sheet("autoeq", "AutoEQ 搜索（未读取）", button("AutoEQ 耳机搜索"), "dialog[open][aria-label='AutoEQ 耳机搜索']");
        page("profile"); capture("profile", "我的", "!document.querySelector('[data-panel=profile]').hidden");
        act(click("[data-action='open-favorites']")); capture("favorites", "我的收藏", "!document.querySelector('[data-panel=favorites]').hidden");
        page("profile"); act(click("[data-action='open-playlists']"));
        capture("playlists", "我的歌单", "!document.querySelector('[data-panel=playlists]').hidden");
        if ("true".equals(js("!!document.querySelector('#profile-playlists [data-playlist]')"))) {
            act(click("#profile-playlists [data-playlist]")); capture("playlist", "歌单详情", "!document.querySelector('[data-panel=playlist]').hidden");
        } else unavailable("playlist", "歌单详情", "当前没有用户歌单；不创建示例歌单");
        page("profile"); act(click("[data-action='open-playlists']"));
        sheet("new-playlist", "新建歌单（未保存）", click("[data-action='new-playlist']"), "#playlist-dialog[open]");
        page("statistics"); capture("statistics", "听歌统计", "!document.querySelector('[data-panel=statistics]').hidden");
        page("settings"); capture("settings", "设置 · 外观与播放", "!document.querySelector('[data-panel=settings]').hidden");
        act("document.querySelector('.m3x-settings-body').scrollTop=450");
        capture("settings-middle", "设置 · 音乐文件", "!document.querySelector('[data-panel=settings]').hidden");
        act("document.querySelector('.m3x-settings-body').scrollTop=99999");
        capture("settings-bottom", "设置 · 关于与原生功能", "!document.querySelector('[data-panel=settings]').hidden");
        sheet("sleep", "睡眠定时（未开启）", click("[data-tool='sleep']"), "#sleep-dialog[open]");
        sheet("scan-options", "扫描筛选（未保存）", click("[data-action='scan-options']"), "#scan-options-dialog[open]");
        sheet("hidden", "隐藏歌曲", click("[data-action='hidden-songs']"), "#hidden-dialog[open]");
        sheet("webdav", "WebDAV（未连接）", button("WebDAV 网络音乐"), "dialog[open][aria-label='WebDAV 音乐']");
        sheet("dlna", "DLNA 发现", button("发现 DLNA 音乐服务器"), "dialog[open][aria-label='局域网音乐']");
        sheet("recoveries", "歌词恢复记录", button("歌词写入与恢复记录"), "dialog[open][aria-label='歌词写入与恢复记录']");
        unavailable("external", "外部依赖与写入结果", "头像需用户图片；NAS 内容需真实服务器；歌词候选需主动联网；写入/恢复结果需实际修改歌曲。本轮只记录现有真实状态，不构造结果。");
    }


    private void touch(String selector) throws Exception {
        act("document.querySelector(" + JSONObject.quote(selector) + ").scrollIntoView({block:'nearest'})");
        Thread.sleep(150);
        String raw = js("(function(){var r=document.querySelector(" + JSONObject.quote(selector) + ").getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,innerWidth]})()");
        JSONArray point = new JSONArray(raw); int[] location = new int[2];
        getInstrumentation().runOnMainSync(() -> activity.web.getLocationOnScreen(location));
        float scale = activity.web.getWidth() / (float)point.getDouble(2);
        float x = location[0] + (float)point.getDouble(0)*scale, y = location[1] + (float)point.getDouble(1)*scale;
        long now = SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now,now,0,x,y,0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now,now+70,1,x,y,0);
        try { getInstrumentation().sendPointerSync(down); getInstrumentation().sendPointerSync(up); }
        finally { down.recycle(); up.recycle(); }
        Thread.sleep(250);
    }

    private void revisionInventory() throws Exception {
        page("library"); act(click("[data-filter=all]"));
        act("window.__appearanceIds=Array.from(document.querySelectorAll('[data-list=library] [data-track]')).map(function(e){return e.dataset.track}).join(',')");
        touch("[data-library-view=grid]");
        waitJs("document.querySelector('.m3x-screen').dataset.libraryView==='grid'");
        assertEquals("List/grid retains song order", "true", js("window.__appearanceIds===Array.from(document.querySelectorAll('[data-list=library] [data-track]')).map(function(e){return e.dataset.track}).join(',')"));
        assertEquals("Two songs occupy the same grid row", "true", js("(function(){var e=document.querySelectorAll('[data-list=library]>.m3x-track-row');if(e.length<2)return true;var a=e[0].getBoundingClientRect(),b=e[1].getBoundingClientRect();return Math.abs(a.top-b.top)<2&&b.left>a.left&&b.right<=innerWidth})()"));
        capture("grid-all", "方形网格 · 真实曲库", "document.querySelector('.m3x-screen').dataset.libraryView==='grid'");
        if ("true".equals(js("!!document.querySelector('[data-more-list=library]')"))) {
            int before = Integer.parseInt(js("document.querySelectorAll('[data-list=library] [data-track]').length"));
            act(click("[data-more-list=library]"));
            int after = Integer.parseInt(js("document.querySelectorAll('[data-list=library] [data-track]').length"));
            assertTrue("Bounded incremental grid", after > before && after <= before+40);
        }
        act("document.getElementById('library-browser').scrollTop=0");
        if ("true".equals(js("!!document.querySelector('[data-list=library] [data-menu-track]')"))) {
            String id = js("nativeCommand('poll').id");
            touch("[data-list=library] [data-menu-track]");
            capture("grid-song-menu", "网格歌曲菜单 · 不播放", "document.getElementById('song-menu-dialog').open");
            assertEquals("Menu keeps current track", id, js("nativeCommand('poll').id")); close();
        }
        for (String filter : new String[]{"characters","groups","artists","albums","folders"}) {
            act(click("[data-filter='"+filter+"']"));
            capture("grid-"+filter,"网格分类 · "+filter,"document.querySelector('[data-filter=\""+filter+"\"]').getAttribute('aria-pressed')==='true'");
            if ("true".equals(js("!!document.querySelector('#library-browser [data-group]')"))) {
                act(click("#library-browser [data-group]"));
                capture("grid-detail-"+filter,"网格分类详情 · "+filter,"!document.getElementById('group-back').hidden");
                act(click("[data-action=group-back]"));
            }
        }
        act(click("[data-filter=all]"));
        page("lyrics");
        assertEquals("Offset hidden on lyrics page", "true", js("document.getElementById('lyric-offset-home').hidden"));
        touch("#lyrics-more");
        capture("lyrics-menu","歌词右下角更多菜单","!!document.querySelector('dialog[open][aria-label=\"歌词更多操作\"]')");
        act(button("调整歌词时间"));
        capture("lyrics-offset","独立歌词偏移面板","!!document.querySelector('dialog[open][aria-label=\"调整歌词时间\"]')");
        getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        waitJs("!document.querySelector('dialog[open]')");
        assertEquals("Back retains lyrics page", "true", js("!document.querySelector('[data-panel=lyrics]').hidden"));
        assertEquals("Offset returned home", "true", js("document.getElementById('lyric-offset-home').contains(document.getElementById('lyric-offset'))"));
        act(click("#lyrics-more")); act(button("歌词保存与写入说明"));
        capture("lyrics-explanation","歌词保存与写入说明","!!document.querySelector('dialog[open][aria-label=\"歌词保存与写入说明\"]')"); close();
        page("statistics");
        assertEquals("24 hourly bins", "24", js("document.querySelectorAll('#stats-hours button').length"));
        assertEquals("7 daily bins", "7", js("document.querySelectorAll('#stats-days button').length"));
        act("document.querySelector('[data-panel=statistics]').scrollTop=99999");
        capture("statistics-ranking","常听排行与真实封面","!document.querySelector('[data-panel=statistics]').hidden");
        page("player"); setting("colorMode","cover");
        waitJs("document.querySelector('.m3x-screen').style.getPropertyValue('--m3x-accent').indexOf('hsl')>=0");
        Thread.sleep(800);
        capture("cover-player","真实当前歌曲封面取色","!document.querySelector('[data-panel=player]').hidden");
        setting("colorMode","fixed");
    }

    private void endfieldInventory() throws Exception {
        setting("visualTheme","endfield");
        act("window.__contourLoaded=false;var image=new Image();image.onload=function(){window.__contourLoaded=image.naturalWidth===384};image.src='endfield-contours.svg'");
        waitJs("window.__contourLoaded===true");
        for (String value : new String[]{"light","dark"}) {
            mode=value; theme="endfield-"+value; setting("theme",value);
            for (String name : new String[]{"library","player","lyrics","profile","statistics","settings"}) {
                page(name);
                if (name.equals("library")) { act(click("[data-filter=all]")); act(click("[data-library-view=grid]")); }
                if (name.equals("settings")) act("document.querySelector('.m3x-settings-body').scrollTop=0");
                if (name.equals("statistics")) act("document.querySelector('[data-panel=statistics]').scrollTop=0");
                capture(name,"终末地 · "+value+" · "+name,"document.querySelector('.m3x-screen').dataset.visualTheme==='endfield'");
            }
            page("lyrics"); act(click("#lyrics-more"));
            capture("lyrics-menu","终末地歌词菜单","!!document.querySelector('dialog[open]')"); close();
            int[] bars=new int[3];
            getInstrumentation().runOnMainSync(() -> { bars[0]=activity.getWindow().getStatusBarColor(); bars[1]=activity.getWindow().getNavigationBarColor(); bars[2]=activity.getWindow().getDecorView().getSystemUiVisibility(); });
            int expected=android.graphics.Color.parseColor(value.equals("dark")?"#161817":"#f3f3e9");
            assertEquals("Status icon contrast", !value.equals("dark"), (bars[2]&android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR)!=0);
            assertEquals("Status bar follows theme",expected,bars[0]); assertEquals("Navigation bar follows theme",expected,bars[1]);
        }
        // Recreate the real activity and check persisted appearance without touching playback.
        setting("theme","light");
        getInstrumentation().runOnMainSync(() -> { activity.web.loadUrl("about:blank"); activity.finish(); });
        getInstrumentation().waitForIdleSync();
        activity=(MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        keepDisplayAwake();
        waitJs("!!window.M3X_UI&&M3X_UI.library()!==null");
        assertEquals("Theme survives activity restart","\"endfield\"",js("document.querySelector('.m3x-screen').dataset.visualTheme"));
        assertEquals("Grid survives activity restart","\"grid\"",js("document.querySelector('.m3x-screen').dataset.libraryView"));
        theme="endfield-light"; page("library"); capture("restart","重开后主题与网格保持","document.querySelector('.m3x-screen').dataset.visualTheme==='endfield'");
    }

    public void testCaptureExistingDeviceAppearance() throws Exception {
        String run = ((android.test.InstrumentationTestRunner) getInstrumentation()).getArguments().getString("captureRun");
        assertNotNull("Use capture-appearance.ps1 with a unique captureRun", run);
        assertTrue("Safe capture directory", run.matches("[A-Za-z0-9_-]+"));
        PlayerApp app = PlayerApp.instance;
        JSONObject savedUi = new JSONObject(app.settings("ui").toString());
        JSONObject savedTools = new JSONObject(app.settings("tools").toString());
        if ("true".equals(((android.test.InstrumentationTestRunner)getInstrumentation()).getArguments().getString("repairPriorCapture"))) {
            // One-off cleanup of the earlier capture's late pagehide write; collection/playback fields stay intact.
            savedUi.put("layout","album").put("theme","light").put("visualTheme","classic").put("libraryView","list").put("colorMode","fixed");
        }
        directory = new File(app.getExternalFilesDir(null), "appearance/" + run);
        assertTrue("Create new capture directory", directory.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(directory,"settings-before.json"))) { out.write(savedUi.toString(2).getBytes("UTF-8")); }
        try {
            activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(
                getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            keepDisplayAwake();
            app.awaitLibrary();
            waitJs("!!window.M3X_UI&&typeof nativeCommand==='function'&&M3X_UI.library()!==null");
            baseline = new JSONObject().put("trackCount", new JSONArray(js("M3X_UI.tracks.map(function(t){return t.id})")).length()).put("scan", app.library.scanStatus()).put("stats", new JSONObject(js("nativeCommand('poll').stats"))).put("currentId", js("nativeCommand('poll').id"));
            if (!"endfield".equals(((android.test.InstrumentationTestRunner)getInstrumentation()).getArguments().getString("captureScope"))) {
                for (String value : new String[]{"light", "dark"}) {
                    theme = value; mode = value;
                    inventory(); revisionInventory();
                }
            }
            endfieldInventory();
            assertEquals("Catalog count preserved",baseline.getInt("trackCount"),Integer.parseInt(js("M3X_UI.tracks.length")));
            assertEquals("No scan started",baseline.getJSONObject("scan").toString(),app.library.scanStatus().toString());
            assertEquals("Current song preserved",baseline.getString("currentId"),js("nativeCommand('poll').id"));
        } finally {
            try {
                if (activity != null) {
                    try {
                        close();
                        act("window.nativeCommand=function(){return {}}");
                    } finally {
                        getInstrumentation().runOnMainSync(() -> {
                            activity.web.loadUrl("about:blank");
                            activity.finish();
                        });
                        getInstrumentation().waitForIdleSync();
                    }
                }
            } finally {
                // pagehide may persist temporary theme/layout; restore after teardown.
                getInstrumentation().runOnMainSync(() -> { app.save("ui", savedUi); app.save("tools", savedTools); });
                Thread.sleep(350);
            }
            writeManifest();
            assertTrue("UI settings restored", savedUi.toString().equals(app.settings("ui").toString()));
            assertEquals("Tool settings restored", savedTools.toString(), app.settings("tools").toString());
        }
    }
}
