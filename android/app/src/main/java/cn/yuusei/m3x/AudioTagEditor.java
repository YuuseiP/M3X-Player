package cn.yuusei.m3x;

import org.jaudiotagger.audio.*;
import org.jaudiotagger.tag.*;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;

/** Operates only on a staged copy. Container audio payload must remain byte-for-byte identical. */
final class AudioTagEditor {
    static String extension(String filename) {
        int dot = filename.lastIndexOf('.'); return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    static boolean supported(String filename) {
        return Arrays.asList("flac", "mp3", "wav", "m4a", "mp4", "aif", "aiff", "aifc").contains(extension(filename));
    }
    private static AudioFile read(File file) throws Exception {
        String ext = extension(file.getName());
        if (ext.equals("wav")) {
            TagOptionSingleton.getInstance().setWavOptions(org.jaudiotagger.audio.wav.WavOptions.READ_ID3_ONLY);
            TagOptionSingleton.getInstance().setWavSaveOptions(org.jaudiotagger.audio.wav.WavSaveOptions.SAVE_EXISTING_AND_ACTIVE);
        }
        return AudioFileIO.readAs(file, ext.equals("aif") || ext.equals("aifc") ? "aiff" : ext);
    }
    static String lyrics(File file) throws Exception {
        Tag tag = read(file).getTag(); return tag == null ? "" : tag.getFirst(FieldKey.LYRICS);
    }
    static void embed(File staged, String text) throws Exception {
        if (!supported(staged.getName())) throw new IOException("此格式暂不支持固化歌词标签");
        String audioBefore = payload(staged);
        AudioFile file = read(staged);
        Tag tag = file.getTagOrCreateAndSetDefault();
        List<String> otherTags = snapshot(tag);
        String[] before = {tag.getFirst(FieldKey.TITLE), tag.getFirst(FieldKey.ARTIST), tag.getFirst(FieldKey.ALBUM)};
        tag.setField(FieldKey.LYRICS, text);
        file.commit();
        AudioFile check = read(staged); Tag after = check.getTag();
        if (after == null || !text.equals(after.getFirst(FieldKey.LYRICS))) throw new IOException("歌词标签回读校验失败，原文件未提交");
        if (!before[0].equals(after.getFirst(FieldKey.TITLE)) || !before[1].equals(after.getFirst(FieldKey.ARTIST)) || !before[2].equals(after.getFirst(FieldKey.ALBUM)))
            throw new IOException("歌曲原有署名发生变化，已停止固化");
        if (!otherTags.equals(snapshot(after))) throw new IOException("其他标签或封面发生变化，已停止固化");
        if (!audioBefore.equals(payload(staged))) throw new IOException("音频数据校验不一致，已停止固化");
        try (RandomAccessFile sync = new RandomAccessFile(staged, "rw")) { sync.getFD().sync(); }
    }
    private static List<String> snapshot(Tag tag) throws Exception {
        if (tag instanceof org.jaudiotagger.tag.wav.WavTag) {
            org.jaudiotagger.tag.wav.WavTag wav = (org.jaudiotagger.tag.wav.WavTag)tag;
            List<String> values = new ArrayList<>();
            for (String s : snapshot(wav.getInfoTag())) values.add("INFO:" + s);
            for (String s : snapshot(wav.getID3Tag())) values.add("ID3:" + s);
            return values;
        }
        Set<String> lyrics = new HashSet<>();
        for (TagField f : tag.getFields(FieldKey.LYRICS)) lyrics.add(f.getId());
        List<String> result = new ArrayList<>(); Iterator<TagField> fields = tag.getFields();
        while (fields.hasNext()) {
            TagField f = fields.next(); if (lyrics.contains(f.getId())) continue;
            result.add(f.getId() + ":" + hex(MessageDigest.getInstance("SHA-256").digest(f.getRawContent())));
        }
        Collections.sort(result); return result;
    }
    static String sha(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) { return sha(in); }
    }
    static String sha(InputStream input) throws Exception {
        try (InputStream in = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            return hex(digest.digest());
        }
    }
    static void copy(InputStream input, File destination) throws Exception {
        try (InputStream in = input; FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.getFD().sync();
        }
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString();
    }
    private static String four(RandomAccessFile in) throws IOException { byte[] bytes = new byte[4]; in.readFully(bytes); return new String(bytes, "US-ASCII"); }
    private static void range(RandomAccessFile in, MessageDigest digest, long start, long count) throws Exception {
        if (count < 0 || start < 0 || start > in.length() - count) throw new IOException("音频容器长度无效");
        in.seek(start); byte[] buf = new byte[65536];
        while (count > 0) { int n = in.read(buf, 0, (int) Math.min(count, buf.length)); if (n < 0) throw new EOFException(); digest.update(buf, 0, n); count -= n; }
    }
    static String payload(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); boolean found = false;
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            String ext = extension(file.getName()), magic = four(in);
            if (ext.equals("mp3")) {
                long start = 0, end = in.length();
                if (magic.startsWith("ID3")) {
                    in.seek(5); int flags = in.readUnsignedByte(); long size = 0;
                    for (int i = 0; i < 4; i++) { int b = in.readUnsignedByte(); if ((b & 128) != 0) throw new IOException("ID3 长度无效"); size = (size << 7) | b; }
                    start = 10 + size + (((flags & 16) != 0) ? 10 : 0);
                }
                if (end >= 128) { in.seek(end - 128); if (four(in).startsWith("TAG")) end -= 128; }
                if (end <= start) throw new IOException("MP3 无音频数据");
                range(in, digest, start, end - start); found = true;
            } else if (magic.equals("fLaC")) {
                boolean last = false;
                while (!last) {
                    int type = in.readUnsignedByte(); last = (type & 128) != 0; type &= 127;
                    int count = (in.readUnsignedByte() << 16) | (in.readUnsignedByte() << 8) | in.readUnsignedByte();
                    long start = in.getFilePointer();
                    if (type == 0) range(in, digest, start, count);
                    in.seek(start + count);
                    if (in.getFilePointer() > in.length()) throw new EOFException();
                }
                long start = in.getFilePointer(); if (in.length() <= start) throw new IOException("FLAC 无音频帧");
                range(in, digest, start, in.length() - start); found = true;
            } else if (magic.equals("RIFF") || magic.equals("FORM")) {
                boolean little = magic.equals("RIFF");
                long declared = Integer.toUnsignedLong(little ? Integer.reverseBytes(in.readInt()) : in.readInt()) + 8;
                String kind = four(in);
                if ((little && !kind.equals("WAVE")) || (!little && !kind.equals("AIFF") && !kind.equals("AIFC")) || declared > in.length()) throw new IOException("音频容器无效");
                while (in.getFilePointer() + 8 <= declared) {
                    String chunk = four(in); long size = Integer.toUnsignedLong(little ? Integer.reverseBytes(in.readInt()) : in.readInt()); long start = in.getFilePointer();
                    if (size > declared - start) throw new IOException("音频块越界");
                    if (chunk.equals("data") || chunk.equals("SSND") || chunk.equals("fmt ") || chunk.equals("COMM")) {
                        range(in, digest, start, size);
                        if (chunk.equals("data") || chunk.equals("SSND")) found = true;
                    }
                    in.seek(start + size + (size & 1));
                }
            } else if (ext.equals("m4a") || ext.equals("mp4")) {
                in.seek(0);
                while (in.getFilePointer() + 8 <= in.length()) {
                    long atom = in.getFilePointer(), size = Integer.toUnsignedLong(in.readInt()); String type = four(in); int header = 8;
                    if (size == 1) { size = in.readLong(); header = 16; }
                    if (size == 0) size = in.length() - atom;
                    if (size < header || size > in.length() - atom) throw new IOException("MP4 atom 越界");
                    if (type.equals("mdat")) { range(in, digest, atom + header, size - header); found = true; }
                    in.seek(atom + size);
                }
            }
        }
        if (!found) throw new IOException("无法验证此音频的原始数据，未固化");
        return hex(digest.digest());
    }
}
