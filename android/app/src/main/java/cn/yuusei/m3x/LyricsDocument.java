package cn.yuusei.m3x;

import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.regex.*;

/** Canonical milliseconds; positive offset delays every original/translated line equally. */
final class LyricsDocument {
    static final int MAX_TEXT = 512 * 1024;
    private static final Pattern STAMP = Pattern.compile("\\[(\\d{1,4}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE);
    static final class Line {
        final long time; final String text;
        Line(long time, String text) { this.time = time; this.text = text; }
    }
    static List<Line> parse(String source) throws IOException {
        if (source.length() > MAX_TEXT) throw new IOException("歌词过大");
        Matcher offset = OFFSET.matcher(source);
        long shift = 0;
        try { if (offset.find()) shift = Long.parseLong(offset.group(1)); }
        catch (NumberFormatException error) { throw new IOException("歌词偏移无效"); }
        if (shift < -3600000 || shift > 3600000) throw new IOException("歌词偏移超出范围");
        List<Line> result = new ArrayList<>();
        for (String raw : source.split("\\r?\\n")) {
            Matcher stamp = STAMP.matcher(raw);
            String text = raw.replaceAll("\\[[^\\]]*\\]", "").trim();
            while (stamp.find()) {
                String fraction = stamp.group(3);
                long millis = fraction == null ? 0 : Long.parseLong((fraction + "000").substring(0, 3));
                result.add(new Line(Long.parseLong(stamp.group(1)) * 60000 + Long.parseLong(stamp.group(2)) * 1000 + millis + shift, text));
                if (result.size() > 10000) throw new IOException("歌词行数过多");
            }
        }
        result.sort(Comparator.comparingLong(line -> line.time));
        return result;
    }
    static String clean(String value) { return value.replaceAll("[\\r\\n\\[\\]\\p{Cntrl}]", " ").trim(); }
    static void validate(JSONObject doc) throws Exception {
        for (String name : new String[]{"title", "artist", "album"})
            if (doc.optString(name).length() > 500) throw new IOException("歌词标注过长");
        if (doc.getLong("offsetMs") < -30000 || doc.getLong("offsetMs") > 30000) throw new IOException("整曲偏移须在 ±30 秒内");
        if (doc.optString("note").length() > 1000) throw new IOException("备注过长");
        List<Line> original = parse(doc.getString("original"));
        if (original.stream().noneMatch(line -> !line.text.isEmpty())) throw new IOException("候选没有同步原文");
        parse(doc.optString("translation"));
    }
    static String lrc(JSONObject doc, boolean bilingual) throws Exception {
        validate(doc);
        List<Line> lines = new ArrayList<>(parse(doc.getString("original")));
        if (bilingual) lines.addAll(parse(doc.optString("translation")));
        lines.sort(Comparator.comparingLong(line -> line.time)); // stable: original before translation at the same time
        long shift = doc.getLong("offsetMs");
        StringBuilder out = new StringBuilder();
        out.append("[ti:").append(clean(doc.optString("title"))).append("]\n[ar:").append(clean(doc.optString("artist")))
            .append("]\n[al:").append(clean(doc.optString("album"))).append("]\n[by:")
            .append(clean(doc.optString("provider") + ":" + doc.optString("songId"))).append("]\n[re:澄 M3X]\n[offset:0]\n");
        out.append("[m3x_offset_ms:").append(shift).append("]\n");
        if (!doc.optString("note").isEmpty()) out.append("[m3x_note:").append(clean(doc.optString("note"))).append("]\n");
        Set<String> seen = new HashSet<>();
        for (Line line : lines) {
            long time = Math.max(0, line.time + shift);
            if (time > 24 * 3600000L) throw new IOException("歌词时间超出范围");
            String value = String.format(Locale.ROOT, "[%02d:%02d.%03d]%s\n", time / 60000, time / 1000 % 60, time % 1000, line.text);
            if (seen.add(value)) out.append(value);
        }
        if (out.length() > MAX_TEXT * 2) throw new IOException("双语歌词过大");
        return out.toString();
    }
    static JSONObject annotations(JSONObject selected, JSONObject input) throws Exception {
        JSONObject next = new JSONObject(selected.toString());
        for (String key : new String[]{"title", "album", "artist", "note"}) next.put(key, input.optString(key, next.optString(key)));
        next.put("offsetMs", input.optLong("offsetMs", next.optLong("offsetMs")));
        validate(next); return next;
    }
}
