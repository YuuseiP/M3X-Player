package cn.yuusei.m3x;
import org.junit.Test;
import org.json.*;
import static org.junit.Assert.*;

public class LyricsLogicTest {
    static JSONObject doc() throws Exception {
        return new JSONObject().put("provider", "NE").put("songId", "123").put("title", "歌").put("artist", "声").put("album", "盤")
            .put("offsetMs", 1200).put("note", "试听").put("original", "[offset:100]\n[00:01.000]日本語\n[00:03.00]次")
            .put("translation", "[00:01.100]中文\n[00:03.200]下一句");
    }
    @Test public void shiftsEachLanguageOnceAndPreservesIndependentTimestamps() throws Exception {
        String lrc = LyricsDocument.lrc(doc(), true);
        assertTrue(lrc.contains("[00:02.300]日本語\n[00:02.300]中文"));
        assertTrue(lrc.contains("[00:04.300]次\n[00:04.400]下一句"));
        assertEquals(2300, LyricsDocument.parse(lrc).get(0).time);
        assertTrue(lrc.contains("[offset:0]"));
    }
    @Test public void annotationCannotInjectHeadersOrChangeProvenance() throws Exception {
        JSONObject input = new JSONObject().put("title", "A\n[ar:bad]").put("provider", "evil").put("offsetMs", -2000);
        JSONObject next = LyricsDocument.annotations(doc(), input);
        assertEquals("NE", next.getString("provider"));
        String lrc = LyricsDocument.lrc(next, true);
        assertFalse(lrc.contains("\n[ar:bad]")); assertTrue(lrc.contains("[00:00.000]日本語"));
    }
    @Test public void rejectsOverflowAndUnsynchronisedLyrics() throws Exception {
        for (long offset : new long[]{Long.MIN_VALUE, -30001, 30001, Long.MAX_VALUE}) {
            try { LyricsDocument.validate(doc().put("offsetMs", offset)); fail("offset accepted"); } catch (java.io.IOException expected) {}
        }
        try { LyricsDocument.validate(doc().put("original", "plain lyrics")); fail(); } catch (java.io.IOException expected) {}
        try { LyricsDocument.parse("[offset:-9223372036854775808]\n[00:01]x"); fail(); } catch (java.io.IOException expected) {}
    }
    @Test public void handlesMissingTranslationWithoutInventingBilingualText() throws Exception {
        JSONObject candidate = new JSONObject().put("provider", "NE").put("songId", "123");
        JSONObject value = LyricsProviders.lyricsResult(candidate, new JSONObject("{\"lrc\":{\"lyric\":\"[00:01]原文\"}}"));
        assertEquals("", value.getString("translation"));
        assertEquals(1, LyricsDocument.parse(LyricsDocument.lrc(value, true)).size());
        try { LyricsProviders.lyricsResult(candidate, new JSONObject("{\"code\":403}")); fail(); } catch (java.io.IOException expected) {}
    }
    @Test public void searchResultsRetainVersionAndDurationAndRejectProviderError() throws Exception {
        JSONArray rows = LyricsProviders.searchResults("NE", new JSONObject("{\"code\":200,\"result\":{\"songs\":[{\"id\":42,\"name\":\"Live\",\"duration\":123456,\"artists\":[{\"name\":\"A\"},{\"name\":\"B\"}],\"album\":{\"name\":\"Concert\"}}]}}"));
        assertEquals("A / B", rows.getJSONObject(0).getString("artist")); assertEquals(123456, rows.getJSONObject(0).getLong("durationMs"));
        assertEquals("Concert", rows.getJSONObject(0).getString("album"));
        try { LyricsProviders.searchResults("QQ", new JSONObject("{\"code\":401}")); fail(); } catch (java.io.IOException expected) {}
    }
}
