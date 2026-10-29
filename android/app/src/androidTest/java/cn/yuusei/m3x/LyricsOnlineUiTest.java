package cn.yuusei.m3x;
import android.test.InstrumentationTestCase;
import android.net.Uri;
import java.io.*;
import org.json.*;
import static cn.yuusei.m3x.PlayerApp.json;
/** Opt-in online acceptance test. Never run against user music. */
public final class LyricsOnlineUiTest extends InstrumentationTestCase {
    public void testSearchAnnotateAdoptAndEmbed() throws Exception {
        NativePlayerTest ui = new NativePlayerTest(); ui.injectInstrumentation(getInstrumentation()); ui.setUp();
        PlayerApp app = ui.app; MusicLibrary original = app.library;
        String id = "lyrics-online-fixture";
        File song = new File(app.getCacheDir(), "lyrics-online.flac");
        AudioTagEditor.copy(getInstrumentation().getContext().getAssets().open("fixture-24-96.flac"), song);
        String audio = AudioTagEditor.payload(song), before = AudioTagEditor.sha(song);
        File state = new File(app.getFilesDir(), "lyric-selections/" + MusicLibrary.hash(id) + ".json");
        File journal = new File(app.getFilesDir(), "lyric-selections/" + MusicLibrary.hash(id) + ".journal");
        try {
            MusicLibrary isolated = new MusicLibrary(app);
            java.lang.reflect.Method setter = MusicLibrary.class.getDeclaredMethod("setTracks", java.util.List.class); setter.setAccessible(true);
            setter.invoke(isolated, java.util.Collections.singletonList(json("id", id, "uri", Uri.fromFile(song).toString(), "filename", song.getName(), "title", "夢咲きAfter school", "artist", "", "album", "Synthetic", "duration", 2, "src", "native/" + id)));
            app.library = isolated;
            ui.waitJs("!!window.M3X_UI&&typeof nativeCommand==='function'");
            ui.js("window.dispatchEvent(new Event('m3x-native-library'));true"); Thread.sleep(600);
            ui.js("document.getElementById('song-menu-dialog').dataset.trackId='" + id + "';Array.from(document.querySelectorAll('button')).find(b=>b.textContent==='联网搜索／标注／固化歌词').click();true");
            ui.waitJs("Array.from(document.querySelectorAll('dialog[open] p')).some(n=>n.textContent.indexOf('可联网搜索')>=0)");
            ui.js("Array.from(document.querySelectorAll('dialog[open] button')).find(b=>b.textContent==='联网搜索').click();true");
            ui.waitJs("Array.from(document.querySelectorAll('dialog[open] p')).some(n=>n.textContent.indexOf('个候选')>=0)");
            ui.js("Array.from(document.querySelectorAll('dialog[open] button')).find(b=>b.textContent.indexOf('与本地差')>=0).click();true");
            ui.waitJs("Array.from(document.querySelectorAll('dialog[open] p')).some(n=>n.textContent.indexOf('候选预览')>=0)");
            ui.js("var d=document.querySelector('dialog[open]');var n=d.querySelector('input[type=number]');n.value=1200;n.dispatchEvent(new Event('input'));var t=d.querySelector('input[aria-label=\"歌词曲名\"]');t.value='Test annotated title';t.dispatchEvent(new Event('input'));Array.from(d.querySelectorAll('button')).find(b=>b.textContent==='采用歌词并保存逐曲标注').click();true");
            ui.waitJs("Array.from(document.querySelectorAll('dialog[open] p')).some(n=>n.textContent.indexOf('已离线保存')>=0)");
            JSONObject doc = app.lyricManager.selected(id); assertNotNull(doc); assertEquals(1200, doc.getInt("offsetMs")); assertEquals("Test annotated title", doc.getString("title"));
            String expected = LyricsDocument.lrc(doc, true);
            assertEquals(expected, isolated.details(id).getString("lyrics"));
            ui.js("Array.from(document.querySelectorAll('dialog[open] button')).find(b=>b.textContent==='一键固化双语歌词到歌曲标签').click();true");
            ui.waitJs("Array.from(document.querySelectorAll('dialog[open] p')).some(n=>n.textContent.indexOf('已写入歌曲标签')>=0)");
            assertEquals(expected, AudioTagEditor.lyrics(song)); assertEquals(audio, AudioTagEditor.payload(song));
            ui.capture("lyrics-adopted-050.png");
            ui.js("Array.from(document.querySelectorAll('dialog[open] button')).find(b=>b.textContent==='恢复本次固化前的完整歌曲文件').click();true");
            ui.waitJs("Array.from(document.querySelectorAll('dialog[open] p')).some(n=>n.textContent.indexOf('已恢复固化前')>=0)");
            assertEquals(before, AudioTagEditor.sha(song)); assertNull(app.lyricManager.selected(id));
        } finally {
            if (journal.isFile()) { JSONObject j = new JSONObject(new String(MusicLibrary.read(new FileInputStream(journal), 10000), "UTF-8")); new File(journal.getParentFile(), j.optString("backup")).delete(); journal.delete(); }
            state.delete(); song.delete(); app.library = original;
            java.lang.reflect.Method persist = MusicLibrary.class.getDeclaredMethod("persistCatalog"); persist.setAccessible(true); persist.invoke(original);
            ui.tearDown();
        }
    }
}
