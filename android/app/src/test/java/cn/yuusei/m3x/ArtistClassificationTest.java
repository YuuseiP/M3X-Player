package cn.yuusei.m3x;

import org.junit.Test;
import org.json.*;
import java.nio.file.*;
import static org.junit.Assert.*;

public class ArtistClassificationTest {
    private JSONObject config() throws Exception {
        return new JSONObject(new String(Files.readAllBytes(Paths.get("src/main/assets/player/artist-classification.json")), "UTF-8"));
    }

    private JSONObject track(JSONObject known) throws Exception {
        return new JSONObject().put("id", "existing-device-id").put("artist", known.getString("artist"))
            .put("bytes", known.getLong("bytes")).put("title", "Real title").put("album", "Real album")
            .put("duration", 180.5).put("sampleRate", 96000).put("bits", 24).put("cover", "native-covers/real.jpg");
    }

    @Test public void allConfirmedMembershipsSurviveRelocationWithoutChangingAudioMetadata() throws Exception {
        JSONObject config = config();
        ArtistClassification classifier = new ArtistClassification(config);
        JSONArray entries = config.getJSONArray("tracks");
        assertEquals(4235, entries.length());
        for (int i = 0; i < entries.length(); i++) {
            JSONObject known = entries.getJSONObject(i), source = track(known);
            String before = source.toString();
            String prefix = i % 2 == 0 ? "/storage/1234-ABCD/Music/RenamedLibrary/" : "primary:Music/THE_IDOLMASTER/";
            JSONObject classified = classifier.apply(source, prefix + known.getString("path"));
            JSONArray expected = known.getJSONArray("memberships"), credits = classified.getJSONArray("credits");
            assertTrue(classified.getBoolean("classificationMatched"));
            if (expected.length() > 0) {
                assertEquals(expected.length(), credits.length());
                for (int n = 0; n < expected.length(); n++) assertEquals(expected.getString(n), credits.getJSONObject(n).getString("id"));
            } else assertEquals("artist", credits.getJSONObject(0).getString("kind"));
            assertEquals(before, source.toString());
            assertEquals("existing-device-id", classified.getString("id"));
            assertEquals("Real title", classified.getString("title"));
            assertEquals("Real album", classified.getString("album"));
            assertEquals(180.5, classified.getDouble("duration"), 0);
            assertEquals(24, classified.getInt("bits"));
            assertEquals("native-covers/real.jpg", classified.getString("cover"));
            assertEquals(known.getString("artist"), classified.getString("originalArtist"));
            assertEquals(classified.getString("artist"), classifier.apply(classified, prefix + known.getString("path")).getString("artist"));
        }
    }

    @Test public void exactTagFallbackIsScopedAndNeverSplitsUnknownCredits() throws Exception {
        JSONObject config = config();
        ArtistClassification classifier = new ArtistClassification(config);
        JSONObject known = config.getJSONArray("tracks").getJSONObject(0);
        JSONObject song = track(known);
        JSONObject fallback = classifier.apply(song, "primary:THE_IDOLMASTER/New/song.flac");
        assertTrue(fallback.getBoolean("classificationMatched"));
        assertEquals("group", fallback.getJSONArray("credits").getJSONObject(0).getString("kind"));
        JSONObject outside = classifier.apply(song, "/storage/1234/Other/song.flac");
        assertFalse(outside.getBoolean("classificationMatched"));
        assertEquals("artist", outside.getJSONArray("credits").getJSONObject(0).getString("kind"));
        song.put("artist", "Unknown A & Unknown B (CV: Somebody)");
        JSONObject unknown = classifier.apply(song, "/storage/1234/THE_IDOLMASTER/New/song.flac");
        assertFalse(unknown.getBoolean("classificationMatched"));
        assertEquals(1, unknown.getJSONArray("credits").length());
        assertEquals(song.getString("artist"), unknown.getString("artist"));
        song.put("artist", "Unknown").put("bytes", 1);
        assertFalse(classifier.apply(song, "/storage/1234/" + known.getString("path")).getBoolean("classificationMatched"));
        song = track(known).put("cueStart", 0).put("originalArtist", "CUE performer");
        JSONObject cue = classifier.apply(song, "/storage/1234/THE_IDOLMASTER/" + known.getString("path"));
        assertEquals("CUE performer", cue.getString("artist"));
        assertFalse(cue.getBoolean("classificationMatched"));
    }
}
