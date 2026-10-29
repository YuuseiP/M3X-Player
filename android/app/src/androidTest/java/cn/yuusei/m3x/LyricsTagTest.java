package cn.yuusei.m3x;
import android.test.InstrumentationTestCase;
import java.io.*;
import org.json.*;

public final class LyricsTagTest extends InstrumentationTestCase {
    private static final String LRC = "[ti:测试]\n[ar:歌手]\n[al:專輯]\n[offset:0]\n[00:01.200]日本語の歌詞\n[00:01.200]中文翻译\n[00:03.400]第二行🙂\n";
    private File fixture(String name) throws Exception {
        File target = new File(getInstrumentation().getTargetContext().getCacheDir(), "tag-test-" + name);
        AudioTagEditor.copy(getInstrumentation().getContext().getAssets().open(name), target); return target;
    }
    private void roundtrip(String name) throws Exception {
        File f = fixture(name);
        try {
            String audio = AudioTagEditor.payload(f);
            AudioTagEditor.embed(f, LRC);
            assertEquals(name, LRC, AudioTagEditor.lyrics(f));
            assertEquals(name, audio, AudioTagEditor.payload(f));
            JSONObject tags = new JSONObject().put("filename", name).put("replayGain", new JSONObject());
            TagReader.read(getInstrumentation().getTargetContext(), android.net.Uri.fromFile(f), tags);
            assertEquals("Scanner must read embedded bilingual lyrics: " + name, LRC.trim(), tags.optString("embeddedLyrics").trim());
            AudioTagEditor.embed(f, LRC.replace("1.200", "1.500"));
            assertEquals(audio, AudioTagEditor.payload(f));
        } finally { f.delete(); }
    }
    public void testMp3CoverAndReplaygain() throws Exception { roundtrip("fixture-tags.mp3"); }
    public void testWav() throws Exception { roundtrip("fixture-tags.wav"); }
    public void testFlac() throws Exception { roundtrip("fixture-24-96.flac"); }
    public void testAiff16() throws Exception { roundtrip("fixture-aiff-16.aiff"); }
    public void testAiff24() throws Exception { roundtrip("fixture-aiff-24.aiff"); }
    public void testAiff32() throws Exception { roundtrip("fixture-aiff-32.aiff"); }
    public void testAiff8() throws Exception { roundtrip("fixture-aiff-8.aiff"); }
    public void testAifc() throws Exception { roundtrip("fixture-sowt.aifc"); }
    public void testAac() throws Exception { roundtrip("fixture-aac.m4a"); }
    public void testAlac16() throws Exception { roundtrip("fixture-alac-16.m4a"); }
    public void testAlac24() throws Exception { roundtrip("fixture-alac-24.m4a"); }
}
