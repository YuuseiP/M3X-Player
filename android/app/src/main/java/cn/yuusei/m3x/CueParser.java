package cn.yuusei.m3x;

import java.util.*;
import java.util.regex.*;

public final class CueParser {
    static String resolvePath(String folder, String reference) {
        String path = reference.replace('\\', '/');
        if (path.startsWith("/") || path.contains(":")) return "";
        Deque<String> parts = new ArrayDeque<>();
        for (String part : (folder + "/" + path).split("/")) {
            if (part.equals("..")) { if (parts.isEmpty()) return ""; parts.removeLast(); }
            else if (!part.isEmpty() && !part.equals(".")) parts.addLast(part);
        }
        return String.join("/", parts);
    }
    public static final class Entry {
        public String file = "", title = "", artist = "", album = "";
        public int number;
        public long startMs = -1;
    }
    public static List<Entry> parse(String text) {
        List<Entry> entries = new ArrayList<>();
        Entry track = null; String file = "", album = "", artist = "";
        for (String raw : text.split("\\r?\\n")) {
            String line = raw.trim();
            Matcher value = Pattern.compile("^(FILE|TITLE|PERFORMER)\\s+(?:\"([^\"]+)\"|([^\\s]+)).*$", Pattern.CASE_INSENSITIVE).matcher(line);
            if (value.matches()) {
                String key = value.group(1).toUpperCase(Locale.ROOT), name = value.group(2) == null ? value.group(3) : value.group(2);
                if (key.equals("FILE")) file = name;
                else if (key.equals("TITLE")) { if (track == null) album = name; else track.title = name; }
                else { if (track == null) artist = name; else track.artist = name; }
                continue;
            }
            Matcher number = Pattern.compile("^TRACK\\s+(\\d+)\\s+AUDIO$", Pattern.CASE_INSENSITIVE).matcher(line);
            if (number.matches()) {
                track = new Entry(); track.file = file; track.album = album; track.artist = artist;
                track.number = Integer.parseInt(number.group(1)); track.title = String.format(Locale.ROOT, "音轨 %02d", track.number);
                entries.add(track); continue;
            }
            Matcher index = Pattern.compile("^INDEX\\s+01\\s+(\\d+):([0-5]\\d):([0-7]\\d)$", Pattern.CASE_INSENSITIVE).matcher(line);
            if (index.matches() && track != null && Integer.parseInt(index.group(3)) < 75)
                track.startMs = Integer.parseInt(index.group(1)) * 60000L + Integer.parseInt(index.group(2)) * 1000L + Integer.parseInt(index.group(3)) * 1000L / 75;
        }
        entries.removeIf(entry -> entry.file.isEmpty() || entry.startMs < 0);
        return entries;
    }
}
