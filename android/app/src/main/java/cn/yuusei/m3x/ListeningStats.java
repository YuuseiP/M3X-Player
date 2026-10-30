package cn.yuusei.m3x;

import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Locale;
import java.util.TimeZone;

/** Bounded local-time aggregates; old seconds/counts are never backfilled. */
final class ListeningStats {
    private ListeningStats() {}
    private static String date(long time, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(zone); return format.format(new java.util.Date(time));
    }
    static void initialize(JSONObject stats, long now, TimeZone zone) {
        try {
            if (stats.optJSONObject("calendar") == null) stats.put("calendar", new JSONObject()
                .put("version", 1).put("started", date(now, zone)).put("days", new JSONObject()));
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }
    static void record(JSONObject stats, long endTime, long elapsed, TimeZone zone) {
        if (elapsed <= 0) return;
        initialize(stats, endTime - elapsed, zone);
        try {
            stats.put("seconds", stats.optDouble("seconds", 0) + elapsed / 1000d);
            JSONObject calendar = stats.getJSONObject("calendar"), days = calendar.optJSONObject("days");
            if (days == null) { days = new JSONObject(); calendar.put("days", days); }
            long cursor = endTime - elapsed;
            while (cursor < endTime) {
                Calendar time = Calendar.getInstance(zone); time.setTimeInMillis(cursor);
                int hour = time.get(Calendar.HOUR_OF_DAY);
                // Advance to the next real hour boundary, including repeated DST hours.
                time.set(Calendar.MINUTE, 0); time.set(Calendar.SECOND, 0); time.set(Calendar.MILLISECOND, 0);
                long boundary = time.getTimeInMillis() + 3600000L;
                if (boundary <= cursor) boundary = cursor + 3600000L;
                long end = Math.min(endTime, boundary);
                String dayKey = date(cursor, zone);
                JSONObject day = days.optJSONObject(dayKey);
                if (day == null) { day = new JSONObject().put("seconds", 0).put("hours", new JSONArray()); days.put(dayKey, day); }
                JSONArray hours = day.optJSONArray("hours"); if (hours == null) hours = new JSONArray();
                while (hours.length() < 24) hours.put(0);
                double seconds = (end - cursor) / 1000d;
                day.put("seconds", day.optDouble("seconds", 0) + seconds);
                hours.put(hour, hours.optDouble(hour, 0) + seconds); day.put("hours", hours);
                cursor = end;
            }
            ArrayList<String> keys = new ArrayList<>(); java.util.Iterator<String> iterator = days.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            Calendar cutoff = Calendar.getInstance(zone); cutoff.setTimeInMillis(endTime); cutoff.add(Calendar.DATE, -89);
            String oldest = date(cutoff.getTimeInMillis(), zone);
            for (String key : keys) if (key.compareTo(oldest) < 0) days.remove(key);
            // Also bound anomalous/future dates after a wall-clock adjustment.
            keys.removeIf(key -> !daysHas(calendar, key));
            while (keys.size() > 90) days.remove(keys.remove(0));
        } catch (org.json.JSONException error) { throw new IllegalArgumentException(error); }
    }
    private static boolean daysHas(JSONObject calendar, String key) { return calendar.optJSONObject("days").has(key); }
}
