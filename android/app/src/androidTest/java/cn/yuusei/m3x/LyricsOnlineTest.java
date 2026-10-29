package cn.yuusei.m3x;
import android.test.InstrumentationTestCase;
import org.json.*;
public final class LyricsOnlineTest extends InstrumentationTestCase {
    private void probe(String provider) throws Exception {
        LyricsProviders p = new LyricsProviders(); JSONArray rows = p.search(provider, "夢咲きAfter school");
        assertTrue("Search " + provider, rows.length() > 0);
        JSONObject document = p.fetch(rows.getJSONObject(0));
        assertFalse(LyricsDocument.parse(document.getString("original")).isEmpty());
        android.util.Log.i("M3X.LyricsTest", provider + " candidates=" + rows.length() + " originalLines=" + LyricsDocument.parse(document.getString("original")).size() + " translationLines=" + LyricsDocument.parse(document.optString("translation")).size());
    }
    public void testQqLive() throws Exception { probe("QQ"); }
    public void testNeteaseLive() throws Exception { probe("NE"); }
}
