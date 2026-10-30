package cn.yuusei.m3x;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.SystemClock;
import junit.framework.TestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import static cn.yuusei.m3x.PlayerApp.json;

/** Emulator-only isolated 4205-track acceptance. Never scans or persists fixture catalog. */
public final class UiAppearanceRevisionTest extends TestCase {
    private android.app.Instrumentation getInstrumentation() { return AppearanceTestRunner.current; }
    private MainActivity activity;
    private PlayerApp app;
    private File directory;
    private final JSONArray captures = new JSONArray();
    private String js(String code) throws Exception {
        CountDownLatch latch = new CountDownLatch(1); String[] value = {""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(code, result -> { value[0] = result; latch.countDown(); }));
        assertTrue("Javascript response", latch.await(20, TimeUnit.SECONDS)); return value[0];
    }
    private void act(String code) throws Exception { assertEquals(code, "\"ok\"", js("(function(){try{" + code + ";return 'ok'}catch(e){return String(e)}})()")); }
    private void waitFor(String code) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 30000;
        while (SystemClock.elapsedRealtime() < deadline) { if ("true".equals(js(code))) return; Thread.sleep(100); }
        fail("UI condition: " + code + " / " + js("document.body.innerText.slice(0,700)"));
    }
    private void click(String selector) throws Exception { act("document.querySelector(" + JSONObject.quote(selector) + ").click()"); }
    private void button(String label) throws Exception { act("Array.from(document.querySelectorAll('button')).find(b=>b.textContent.trim()===" + JSONObject.quote(label) + ").click()"); }
    private void close() throws Exception { act("Array.from(document.querySelectorAll('dialog[open]')).reverse().forEach(d=>M3X_UI.closeDialog(d,true))"); Thread.sleep(100); }
    private void page(String name) throws Exception { close(); click("[data-page='" + name + "']"); waitFor("!document.querySelector('[data-panel=" + name + "]').hidden"); }
    private void setting(String name, String value) throws Exception { act("var e=document.querySelector('[data-setting=" + name + "]');e.value=" + JSONObject.quote(value) + ";e.dispatchEvent(new Event('change',{bubbles:true}))"); }
    private void capture(String name) throws Exception {
        waitFor("!document.fonts||document.fonts.status==='loaded'"); Thread.sleep(300);
        Bitmap image = getInstrumentation().getUiAutomation().takeScreenshot(); assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
        captures.put(json("id", name, "file", name + ".png", "width", image.getWidth(), "height", image.getHeight(), "capturedAt", System.currentTimeMillis())); image.recycle();
    }
    private void start() throws Exception {
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitFor("!!window.M3X_UI&&M3X_UI.tracks.length===4205");
    }
    private void finish() {
        if (activity != null) { getInstrumentation().runOnMainSync(() -> { activity.web.loadUrl("about:blank"); activity.finish(); }); getInstrumentation().waitForIdleSync(); activity = null; }
    }
    private File wave() throws Exception {
        File file = new File(app.getCacheDir(), "appearance-060-fixture.wav"); int frames = 44100 * 45, length = frames * 2;
        ByteBuffer bytes = ByteBuffer.allocate(length + 44).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes("US-ASCII")).putInt(length+36).put("WAVEfmt ".getBytes("US-ASCII")).putInt(16).putShort((short)1).putShort((short)1).putInt(44100).putInt(88200).putShort((short)2).putShort((short)16).put("data".getBytes("US-ASCII")).putInt(length);
        for (int i=0;i<frames;i++) bytes.putShort((short)(Math.sin(i*2*Math.PI*440/44100)*1000));
        try (FileOutputStream out=new FileOutputStream(file)) { out.write(bytes.array()); } return file;
    }
    public void testRevision70AndEndfieldOnEmulator() throws Exception {
        assertEquals("Only run with explicit isolated emulator authorization", "true", AppearanceTestRunner.arguments.getString("emulatorFixture"));
        app=PlayerApp.instance; app.awaitLibrary();
        activity=(MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitFor("!!window.M3X_UI&&!!nativeCommand('poll').stats");
        MusicLibrary original=app.library;
        JSONObject savedUi=new JSONObject(app.settings("ui").toString()), savedTools=new JSONObject(app.playback.snapshot().getJSONObject("stats").toString()), savedAudio=new JSONObject(app.settings("audio").toString());
        JSONObject originalScan=new JSONObject(original.scanStatus().toString());
        String originalId=app.playback.snapshot().optString("id"); long originalPosition=app.playback.player.getCurrentPosition();
        boolean wasPlaying=app.playback.player.getPlayWhenReady();
        List<File> files=new ArrayList<>();
        directory=new File(app.getExternalFilesDir(null),"emulator-appearance-060"); directory.mkdirs();
        try {
            getInstrumentation().runOnMainSync(() -> app.playback.player.pause()); finish();
            File audio=wave(); files.add(audio);
            int[] colors={Color.rgb(50,91,106),Color.rgb(175,75,43),Color.rgb(110,87,154)};
            for(int i=0;i<3;i++) {
                File cover=new File(original.artwork,"appearance-060-"+i+".jpg"); files.add(cover);
                Bitmap bitmap=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888); Canvas c=new Canvas(bitmap); c.drawColor(colors[i]);
                Paint p=new Paint(Paint.ANTI_ALIAS_FLAG); p.setColor(Color.rgb(240,240,222)); p.setTextSize(22); c.drawText("M3X / TEST RECORD",22,38,p);
                p.setStrokeWidth(2); c.drawLine(22,50,298,50,p); p.setTextSize(88); c.drawText("0"+(i+1),22,164,p);
                p.setTextSize(17); c.drawText("LOCAL AUDIO ARCHIVE",22,285,p);
                try(FileOutputStream out=new FileOutputStream(cover)){bitmap.compress(Bitmap.CompressFormat.JPEG,95,out);} bitmap.recycle();
            }
            MusicLibrary isolated=new MusicLibrary(app);
            List<JSONObject> tracks=new ArrayList<>();
            for(int i=0;i<4205;i++) tracks.add(json("id","appearance-"+i,"uri",Uri.fromFile(audio).toString(),"src","native-audio/appearance-"+i,"title",(i==0?"原野来信 · 很长的中文与日本語タイトル / Signal from the frontier ":"测试曲目 · ")+String.format(java.util.Locale.US,"%04d",i),"artist","测试演奏者 "+i%3,"originalArtist","测试演奏者 "+i%3,"album","测试专辑 "+i%3,"filename","test-"+i+".wav","folder","测试目录"+i%2,"cover",i%4==3?"cover-placeholder.svg":"native-covers/appearance-060-"+i%3+".jpg","duration",45,"sampleRate",44100,"bits",16,"channels",1,"bytes",audio.length(),"embeddedLyrics","[00:00.00]这是独立测试歌词\n[00:02.00]原野寄来一封信\n[00:05.00]Signal from the frontier"));
            java.lang.reflect.Method setter=MusicLibrary.class.getDeclaredMethod("setTracks",List.class); setter.setAccessible(true); setter.invoke(isolated,tracks);
            for(int i=0;i<4205;i++) isolated.track("appearance-"+i).put("credits",new JSONArray().put(json("id","character-test","kind","character","name","测试角色")).put(json("id","group-test","kind","group","name","测试组合")).put(json("id","artist-test","kind","artist","name","测试演奏者")));
            app.library=isolated;
            JSONObject fixtureUi=json("theme","light","visualTheme","classic","colorMode","fixed","trackId","appearance-0","queue",new JSONArray().put("appearance-0").put("appearance-1").put("appearance-2"),"favorites",new JSONArray().put("appearance-0").put("appearance-1").put("appearance-3"),"history",new JSONArray().put("appearance-1").put("appearance-0"),"playlists",new JSONArray().put(json("id","test-playlist","name","测试歌单 · 原野","trackIds",new JSONArray().put("appearance-1").put("appearance-2"))).put(json("id","empty-playlist","name","空歌单","trackIds",new JSONArray())),"startPage","library");
            JSONObject legacy=json("seconds",7560,"counts",json("appearance-0",3,"appearance-1",2));
            getInstrumentation().runOnMainSync(() -> {try{app.playback.restoreBackup(json("ui",fixtureUi,"tools",legacy,"audio",json("rate",1)));app.playback.load("appearance-0",false);}catch(Exception e){throw new RuntimeException(e);}});
            start();
            capture("classic-light-list");
            assertEquals("40",""+js("document.querySelectorAll('[data-list=library] [data-track]').length"));
            act("window.__ids=Array.from(document.querySelectorAll('[data-list=library] [data-track]')).map(e=>e.dataset.track).join(',')");
            click("[data-library-view=grid]"); assertEquals("true",js("window.__ids===Array.from(document.querySelectorAll('[data-list=library] [data-track]')).map(e=>e.dataset.track).join(',')"));
            waitFor("document.querySelector('[data-list=library] img').complete"); capture("classic-light-grid");
            act("document.getElementById('library-browser').scrollTop=300"); click("[data-library-view=list]"); click("[data-library-view=grid]");
            assertEquals("300",js("document.getElementById('library-browser').scrollTop"));
            click("[data-more-list=library]"); assertEquals("80",js("document.querySelectorAll('[data-list=library] [data-track]').length"));
            click("[data-menu-track='0']"); waitFor("document.getElementById('song-menu-dialog').open"); assertEquals("true",js("audio.paused")); close();
            setting("sort","title"); act("var e=document.querySelector('.m3x-search');e.value='测试曲目';e.dispatchEvent(new Event('input',{bubbles:true}))");
            act("window.__ids=Array.from(document.querySelectorAll('[data-list=library] [data-track]')).map(e=>e.dataset.track).join(',')"); click("[data-library-view=list]");
            assertEquals("true",js("window.__ids===Array.from(document.querySelectorAll('[data-list=library] [data-track]')).map(e=>e.dataset.track).join(',')"));
            act("var e=document.querySelector('.m3x-search');e.value='';e.dispatchEvent(new Event('input',{bubbles:true}))"); setting("sort","original"); click("[data-library-view=grid]");
            for(String filter:new String[]{"characters","groups","artists","albums","folders"}) {
                click("[data-filter='"+filter+"']"); waitFor("!!document.querySelector('#library-groups [data-group]')"); capture("grid-"+filter);
                click("#library-groups [data-group]"); waitFor("document.querySelectorAll('[data-list=library] [data-track]').length>0");
                click("[data-library-view=list]"); click("[data-library-view=grid]"); click("[data-action=group-back]");
            }
            click("[data-filter=all]");
            act("var e=document.querySelector('[data-setting=showCovers]');e.checked=false;e.dispatchEvent(new Event('change'))");
            assertEquals("true",js("Array.from(document.querySelectorAll('[data-list=library] img')).every(e=>e.src.endsWith('cover-placeholder.svg'))")); capture("grid-covers-disabled");
            act("var e=document.querySelector('[data-setting=showCovers]');e.checked=true;e.dispatchEvent(new Event('change'))");
            page("profile"); assertEquals("3",js("document.querySelectorAll('#favorite-preview button').length")); assertEquals("2",js("document.querySelectorAll('#playlist-preview button').length")); capture("classic-profile");
            click("#playlist-preview [data-playlist='empty-playlist']"); waitFor("document.querySelector('[data-list=playlist]').textContent.includes('暂无歌曲')"); page("profile");
            page("lyrics"); assertEquals("true",js("document.getElementById('lyric-offset-home').hidden")); capture("classic-lyrics");
            click("#lyrics-more"); capture("lyrics-menu"); button("调整歌词时间"); capture("lyrics-offset");
            act("var e=document.getElementById('lyric-offset');e.value=1.2;e.dispatchEvent(new Event('change'))");
            assertEquals(1.2,app.playback.snapshot().getJSONObject("settings").getDouble("lyricOffset"),.001);
            getInstrumentation().runOnMainSync(() -> activity.onBackPressed()); waitFor("!document.querySelector('dialog[open]')"); assertEquals("true",js("document.getElementById('lyric-offset-home').contains(document.getElementById('lyric-offset'))"));
            click("#lyrics-more"); button("歌词保存与写入说明"); capture("lyrics-explanation"); close();
            click("#lyrics-more"); button("搜索／更换歌词"); waitFor("!!document.querySelector('dialog[aria-label=\"搜索与保存歌词\"]')"); capture("lyrics-search"); close();
            click("#lyrics-more"); button("导入歌词"); capture("lyrics-import"); close();
            page("statistics"); capture("statistics-legacy-empty"); assertEquals("126",js("document.getElementById('listening-minutes').textContent"));
            assertEquals("2",js("document.querySelectorAll('.m3x-rank img').length")); assertEquals("24",js("document.querySelectorAll('#stats-hours button').length"));
            double paused=app.playback.snapshot().getJSONObject("stats").getDouble("seconds"); Thread.sleep(1600); assertEquals(paused,app.playback.snapshot().getJSONObject("stats").getDouble("seconds"),.001);
            getInstrumentation().runOnMainSync(() -> {app.playback.player.setVolume(0);app.playback.load("appearance-0",true);});
            waitFor("!audio.paused&&audio.currentTime>2"); Thread.sleep(1500);
            getInstrumentation().runOnMainSync(() -> app.playback.player.pause());
            assertTrue("Actual playback increments calendar",app.playback.snapshot().getJSONObject("stats").getDouble("seconds")>paused+2);
            Thread.sleep(800); capture("statistics-real-playback");
            page("player"); setting("colorMode","cover"); waitFor("document.querySelector('.m3x-screen').style.getPropertyValue('--m3x-accent').includes('hsl')"); Thread.sleep(500); capture("cover-player-teal");
            act("window.__color=document.querySelector('.m3x-screen').style.getPropertyValue('--m3x-accent')");
            getInstrumentation().runOnMainSync(() -> app.playback.load("appearance-1",false));
            waitFor("M3X_UI.currentTrack().id==='appearance-1'&&window.__color!==document.querySelector('.m3x-screen').style.getPropertyValue('--m3x-accent')"); capture("cover-player-orange");
            for(String theme:new String[]{"light","dark"}) {
                setting("theme",theme); setting("visualTheme","endfield"); page("library"); click("[data-filter=all]"); capture("endfield-"+theme+"-grid");
                page("player"); capture("endfield-"+theme+"-player"); page("profile"); capture("endfield-"+theme+"-profile");
                page("statistics"); capture("endfield-"+theme+"-statistics"); page("settings"); capture("endfield-"+theme+"-settings");
                button("歌词写入与恢复记录"); capture("endfield-"+theme+"-recoveries"); close();
            }
            setting("visualTheme","classic"); setting("colorMode","fixed"); page("library"); capture("classic-dark-grid");
            page("lyrics"); capture("classic-dark-lyrics");
            setting("visualTheme","endfield"); setting("theme","light"); finish(); start();
            assertEquals("\"endfield\"",js("document.querySelector('.m3x-screen').dataset.visualTheme")); assertEquals("\"grid\"",js("document.querySelector('.m3x-screen').dataset.libraryView"));
            assertEquals("false",js("document.documentElement.dataset.theme==='dark'")); capture("restart-persisted");
            assertEquals("No scan was started",originalScan.toString(),original.scanStatus().toString());
        } finally {
            finish();
            getInstrumentation().runOnMainSync(() -> {
                try {
                    app.playback.player.pause(); app.library=original;
                    app.playback.restoreBackup(json("ui",savedUi,"tools",savedTools,"audio",savedAudio));
                    if(original.track(originalId)!=null){app.playback.load(originalId,false);app.playback.player.seekTo(originalPosition);app.playback.player.setPlayWhenReady(wasPlaying);}
                    app.save("ui",savedUi); app.save("tools",savedTools);
                } catch(Exception error){throw new RuntimeException(error);}
            });
            for(File file:files) file.delete();
            JSONObject manifest=json("source","emulator / release instrumentation / isolated synthetic test library", "fixtureTracks",4205,"device",android.os.Build.MODEL,"android",android.os.Build.VERSION.RELEASE,"version",BuildConfig.VERSION_NAME,"pages",captures);
            try(FileOutputStream out=new FileOutputStream(new File(directory,"manifest.json"))){out.write(manifest.toString(2).getBytes("UTF-8"));}
            assertEquals("Original UI restored",savedUi.toString(),app.settings("ui").toString());
            assertEquals("Original catalog retained",original,app.library);
        }
    }
}
