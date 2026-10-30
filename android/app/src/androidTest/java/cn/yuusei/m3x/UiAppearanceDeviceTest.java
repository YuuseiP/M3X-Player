package cn.yuusei.m3x;

import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit real-library interaction probe. Restores playback, statistics and UI after testing. */
public final class UiAppearanceDeviceTest extends InstrumentationTestCase {
    private MainActivity activity;
    private String js(String expression) throws Exception {
        CountDownLatch latch=new CountDownLatch(1); String[] value={""};
        getInstrumentation().runOnMainSync(() -> activity.web.evaluateJavascript(expression,result->{value[0]=result;latch.countDown();}));
        assertTrue(latch.await(20,TimeUnit.SECONDS)); return value[0];
    }
    private void act(String code) throws Exception { assertEquals("\"ok\"",js("(function(){try{"+code+";return 'ok'}catch(e){return String(e)}})()")); }
    private void waitJs(String code) throws Exception {
        long end=SystemClock.elapsedRealtime()+30000;
        while(SystemClock.elapsedRealtime()<end){if("true".equals(js(code)))return;Thread.sleep(100);}
        fail("Timed out: "+code);
    }
    private void setting(String key,String value) throws Exception {
        act("var e=document.querySelector('[data-setting="+key+"]');e.value="+JSONObject.quote(value)+";e.dispatchEvent(new Event('change',{bubbles:true}))");
    }
    private Field field(String name) throws Exception { Field f=PlaybackService.class.getDeclaredField(name); f.setAccessible(true);return f; }
    private void capture(File directory,String name) throws Exception {
        Thread.sleep(350); android.graphics.Bitmap image=getInstrumentation().getUiAutomation().takeScreenshot();assertNotNull(image);
        try(FileOutputStream output=new FileOutputStream(new File(directory,name+".png"))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output);}finally{image.recycle();}
    }
    public void testRealLibraryCoverPlaybackAndStateRestoration() throws Exception {
        assertEquals("Physical target", "Shanling M3X", android.os.Build.MODEL.replace('_',' '));
        PlayerApp app=PlayerApp.instance; app.awaitLibrary();
        activity=(MainActivity)getInstrumentation().startActivitySync(new Intent(getInstrumentation().getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        waitJs("!!window.M3X_UI&&!!nativeCommand('poll').stats&&M3X_UI.tracks.length>0");
        JSONObject savedUi=new JSONObject(app.settings("ui").toString());
        JSONObject savedDiskStats=new JSONObject(app.settings("tools").toString());
        JSONObject savedStats=new JSONObject(js("nativeCommand('poll').stats"));
        JSONObject savedAudio=new JSONObject(app.settings("audio").toString());
        JSONObject scan=new JSONObject(app.library.scanStatus().toString());
        String originalId=new JSONArray("["+js("nativeCommand('poll').id")+"]").getString(0);
        long[] originalPosition={0}; boolean[] playing={false}; float[] gain={1},volume={1};
        Field manual=field("manualGain"),ramp=field("rampStart"),stats=field("stats"),lastStats=field("lastStats");
        JSONObject evidence=new JSONObject(); File directory=new File(app.getExternalFilesDir(null),"appearance-device-060"); directory.mkdirs();
        getInstrumentation().runOnMainSync(()->{originalPosition[0]=app.playback.player.getCurrentPosition();playing[0]=app.playback.player.getPlayWhenReady();volume[0]=app.playback.player.getVolume();});
        assertEquals("No active sleep timer",0,new JSONObject(js("nativeCommand('poll')")).optLong("sleepRemaining"));
        try {
            getInstrumentation().runOnMainSync(()->{try {gain[0]=manual.getFloat(app.playback);app.playback.player.pause();manual.setFloat(app.playback,0);ramp.setLong(app.playback,0);app.playback.player.setVolume(0);}catch(Exception e){throw new RuntimeException(e);}});
            setting("visualTheme","classic");setting("theme","light");setting("colorMode","cover");
            act("document.querySelector('[data-page=library]').click();document.querySelector('[data-filter=all]').click()");
            setting("sort","title");
            act("var e=document.querySelector('.m3x-search');e.value=M3X_UI.tracks[0].title.slice(0,2);e.dispatchEvent(new Event('input',{bubbles:true}));window.__ids=Array.from(document.querySelectorAll('[data-list=library] [data-track]')).map(function(e){return e.dataset.track}).join(',');document.querySelector('[data-library-view=grid]').click()");
            assertEquals("Search/sort retained across views","true",js("__ids===Array.from(document.querySelectorAll('[data-list=library] [data-track]')).map(function(e){return e.dataset.track}).join(',')"));
            act("var e=document.querySelector('.m3x-search');e.value='';e.dispatchEvent(new Event('input',{bubbles:true}));document.getElementById('library-browser').scrollTop=200");
            String scroll=js("document.getElementById('library-browser').scrollTop");
            act("document.querySelector('[data-library-view=list]').click();document.querySelector('[data-library-view=grid]').click()");
            assertEquals("Grid scroll restored",scroll,js("document.getElementById('library-browser').scrollTop"));
            act("var e=document.querySelector('[data-setting=showCovers]');e.checked=false;e.dispatchEvent(new Event('change',{bubbles:true}))");
            assertEquals("Cover switch uses placeholders","true",js("Array.from(document.querySelectorAll('[data-list=library] img')).every(function(e){return e.src.indexOf('cover-placeholder.svg')>=0})"));
            act("var e=document.querySelector('[data-setting=showCovers]');e.checked=true;e.dispatchEvent(new Event('change',{bubbles:true}));document.querySelector('[data-page=player]').click()");
            // Only choose existing queue entries with local cached artwork. No catalog or source-file writes.
            JSONArray candidates=new JSONArray(js("(function(){var q=nativeCommand('queue').ids,seen={},out=[];M3X_UI.tracks.some(function(t){if(q.indexOf(t.id)>=0&&t.cover.indexOf('native-covers/')===0&&!seen[t.cover]){seen[t.cover]=true;out.push(t.id)}return out.length>=8});return out})()"));
            assertTrue("Two real covers available",candidates.length()>=2);
            String first=candidates.getString(0); getInstrumentation().runOnMainSync(()->app.playback.load(first,false));
            waitJs("M3X_UI.currentTrack().id==="+JSONObject.quote(first)); Thread.sleep(1200);
            String firstColor=js("document.querySelector('.m3x-screen').style.getPropertyValue('--m3x-accent')");
            capture(directory,"cover-first");
            String second=null,secondColor=firstColor;
            for(int i=1;i<candidates.length();i++) {
                String id=candidates.getString(i);getInstrumentation().runOnMainSync(()->app.playback.load(id,false));
                waitJs("M3X_UI.currentTrack().id==="+JSONObject.quote(id));Thread.sleep(1000);
                secondColor=js("document.querySelector('.m3x-screen').style.getPropertyValue('--m3x-accent')");
                if(!firstColor.equals(secondColor)){second=id;break;}
            }
            assertNotNull("Different real covers change palette",second); capture(directory,"cover-second");
            String selected=second,expected=secondColor;
            getInstrumentation().runOnMainSync(()->{app.playback.load(first,false);app.playback.load(selected,false);});
            waitJs("M3X_UI.currentTrack().id==="+JSONObject.quote(second));Thread.sleep(1000);
            assertEquals("Rapid switches settle on final cover",expected,js("document.querySelector('.m3x-screen').style.getPropertyValue('--m3x-accent')"));
            double paused=new JSONObject(js("nativeCommand('poll').stats")).getDouble("seconds");Thread.sleep(1500);
            assertEquals("Paused time is not counted",paused,new JSONObject(js("nativeCommand('poll').stats")).getDouble("seconds"),.001);
            getInstrumentation().runOnMainSync(()->{app.playback.player.seekTo(30000);app.playback.player.play();});
            waitJs("nativeCommand('poll').playing&&nativeCommand('poll').position>31");Thread.sleep(2500);
            JSONObject active=new JSONObject(js("nativeCommand('poll')"));
            getInstrumentation().runOnMainSync(()->app.playback.player.pause());
            JSONObject recorded=new JSONObject(js("nativeCommand('poll').stats"));
            assertTrue("Real playback adds duration",recorded.getDouble("seconds")>paused+2);
            assertTrue("Real playback creates hourly record",recorded.getJSONObject("calendar").getJSONObject("days").length()>0);
            double stopped=recorded.getDouble("seconds");Thread.sleep(1500);
            assertEquals("Paused recording stays stable",stopped,new JSONObject(js("nativeCommand('poll').stats")).getDouble("seconds"),.001);
            assertTrue("Decoder initialized",!active.optString("decoder").isEmpty()||active.optInt("outputRate")>0);
            act("document.querySelector('[data-page=statistics]').click()");capture(directory,"statistics-playback");
            evidence.put("device",android.os.Build.MODEL).put("tracks",app.library.all().size()).put("firstColor",firstColor).put("secondColor",secondColor).put("secondsRecorded",stopped-paused).put("outputRate",active.optInt("outputRate")).put("decoder",active.optString("decoder")).put("muted",true).put("scanUnchanged",scan.toString().equals(app.library.scanStatus().toString()));
        } finally {
            try {
                act("window.nativeCommand=function(){return {}}");
                getInstrumentation().runOnMainSync(()->{activity.web.loadUrl("about:blank");activity.finish();});getInstrumentation().waitForIdleSync();
            } finally {
                getInstrumentation().runOnMainSync(()->{try{
                    app.playback.player.pause();app.playback.load(originalId,false);app.playback.player.seekTo(originalPosition[0]);
                    stats.set(app.playback,new JSONObject(savedStats.toString()));lastStats.setLong(app.playback,SystemClock.elapsedRealtime());
                    manual.setFloat(app.playback,gain[0]);ramp.setLong(app.playback,0);app.playback.player.setVolume(volume[0]);
                    app.save("ui",savedUi);app.save("tools",savedDiskStats);app.playback.player.setPlayWhenReady(playing[0]);
                }catch(Exception e){throw new RuntimeException(e);}});
                assertEquals("UI restored",savedUi.toString(),app.settings("ui").toString());
                assertEquals("Audio settings preserved",savedAudio.toString(),app.settings("audio").toString());
                assertEquals("No scan",scan.toString(),app.library.scanStatus().toString());
                evidence.put("settingsRestored",true).put("originalTrack",originalId).put("originalPositionMs",originalPosition[0]);
                try(FileOutputStream out=new FileOutputStream(new File(directory,"result.json"))){out.write(evidence.toString(2).getBytes("UTF-8"));}
            }
        }
    }
}
