package cn.yuusei.m3x;

import org.junit.Test;
import org.json.JSONObject;
import java.time.Instant;
import java.util.TimeZone;
import static org.junit.Assert.*;

public class ListeningStatsTest {
    private final TimeZone zone = TimeZone.getTimeZone("Asia/Shanghai");
    private long at(String time) { return Instant.parse(time).toEpochMilli(); }
    @Test public void legacyTotalsSurviveAndNeverInventHistory() throws Exception {
        JSONObject old = new JSONObject("{\"seconds\":7560,\"counts\":{\"song\":48}}");
        ListeningStats.initialize(old, at("2026-10-30T00:00:00Z"), zone);
        assertEquals(7560, old.getDouble("seconds"), 0);
        assertEquals(48, old.getJSONObject("counts").getInt("song"));
        assertEquals(0, old.getJSONObject("calendar").getJSONObject("days").length());
        ListeningStats.record(old, at("2026-10-30T00:00:01Z"), 1000, zone);
        JSONObject restored = new JSONObject(old.toString());
        ListeningStats.initialize(restored, at("2026-10-31T00:00:00Z"), zone);
        assertEquals(old.toString(), restored.toString());
        assertEquals(7561, restored.getDouble("seconds"), 0);
    }
    @Test public void splitsMidnightAndHourExactly() throws Exception {
        JSONObject stats = new JSONObject();
        ListeningStats.record(stats, at("2026-10-30T16:00:00.500Z"), 1500, zone);
        JSONObject days = stats.getJSONObject("calendar").getJSONObject("days");
        assertEquals(1, days.getJSONObject("2026-10-30").getJSONArray("hours").getDouble(23), .0001);
        assertEquals(.5, days.getJSONObject("2026-10-31").getJSONArray("hours").getDouble(0), .0001);
        assertEquals(1.5, stats.getDouble("seconds"), 0);
    }
    @Test public void noElapsedTimeAddsNothing() {
        JSONObject stats = new JSONObject();
        ListeningStats.record(stats, 0, 0, zone); ListeningStats.record(stats, 0, -2, zone);
        assertEquals(0, stats.length());
    }
    @Test public void retainsOnlyNinetyDaysButKeepsLifetimeTotal() throws Exception {
        JSONObject stats = new JSONObject(); long start = at("2026-01-01T00:00:00Z");
        for (int i = 0; i < 120; i++) ListeningStats.record(stats, start + i * 86400000L, 1000, zone);
        assertEquals(90, stats.getJSONObject("calendar").getJSONObject("days").length());
        assertEquals(120, stats.getDouble("seconds"), 0);
    }
    @Test public void repeatedDstHourAndTimezoneChangePreserveSeconds() throws Exception {
        JSONObject stats = new JSONObject(); TimeZone ny = TimeZone.getTimeZone("America/New_York");
        ListeningStats.record(stats, at("2026-11-01T06:00:00.500Z"), 1500, ny);
        assertEquals(1.5, stats.getJSONObject("calendar").getJSONObject("days").getJSONObject("2026-11-01").getJSONArray("hours").getDouble(1), .0001);
        ListeningStats.record(stats, at("2026-11-01T06:00:02Z"), 1000, zone);
        assertEquals(2.5, stats.getDouble("seconds"), .0001);
    }
}
