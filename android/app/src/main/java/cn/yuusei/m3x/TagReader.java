package cn.yuusei.m3x;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.nio.*;
import java.nio.charset.Charset;
import java.util.Locale;

final class TagReader {
    static void read(Context context, Uri uri, JSONObject track) {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(context.getContentResolver().openInputStream(uri)))) {
            byte[] magic = new byte[4]; input.readFully(magic);
            if (new String(magic, "US-ASCII").equals("fLaC")) {
                boolean last = false; int total = 0;
                while (!last && total < 16 * 1024 * 1024) {
                    int kind = input.readUnsignedByte(); last = (kind & 128) != 0; kind &= 127;
                    int length = input.readUnsignedByte() << 16 | input.readUnsignedByte() << 8 | input.readUnsignedByte();
                    total += length;
                    if (length > 8 * 1024 * 1024 || (kind != 0 && kind != 4)) { skip(input, length); continue; }
                    byte[] bytes = new byte[length]; input.readFully(bytes);
                    if (kind == 0 && length == 34) {
                        int rate = (bytes[10] & 255) << 12 | (bytes[11] & 255) << 4 | (bytes[12] & 255) >> 4;
                        track.put("sampleRate", rate);
                        track.put("bits", (((bytes[12] & 1) << 4) | (bytes[13] & 255) >> 4) + 1);
                    } else if (kind == 4) {
                        ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                        int vendor = data.getInt(); if (vendor < 0 || vendor > data.remaining()) break; data.position(data.position() + vendor);
                        int count = data.getInt();
                        for (int i = 0; i < count && data.remaining() >= 4; i++) {
                            int size = data.getInt(); if (size < 0 || size > data.remaining()) break;
                            byte[] text = new byte[size]; data.get(text);
                            String comment = new String(text, "UTF-8");
                            int at = comment.indexOf('=');
                            if (at > 0) tag(track, comment.substring(0, at), comment.substring(at + 1));
                        }
                    }
                }
            } else if (new String(magic, "US-ASCII").equals("RIFF")) {
                input.readInt(); input.readInt();
                while (true) {
                    byte[] id = new byte[4]; input.readFully(id);
                    int length = Integer.reverseBytes(input.readInt());
                    if (length < 0 || length > 16 * 1024 * 1024) break;
                    if (new String(id, "US-ASCII").equals("fmt ") && length >= 16) {
                        byte[] fmt = new byte[length]; input.readFully(fmt);
                        int encoding = (fmt[0] & 255) | (fmt[1] & 255) << 8;
                        if (encoding == 1 || encoding == 3 || encoding == 65534)
                            track.put("bits", (fmt[14] & 255) | (fmt[15] & 255) << 8);
                        break;
                    }
                    skip(input, length + (length & 1));
                }
            } else if (magic[0] == 'I' && magic[1] == 'D' && magic[2] == '3') {
                int version = magic[3]; input.readUnsignedByte(); int flags = input.readUnsignedByte();
                int size = sync(input.readInt());
                if (size > 4 * 1024 * 1024 || (flags & 128) != 0 || version < 3 || version > 4) return;
                byte[] bytes = new byte[size]; input.readFully(bytes);
                ByteBuffer data = ByteBuffer.wrap(bytes);
                while (data.remaining() >= 10) {
                    byte[] id = new byte[4]; data.get(id); String frame = new String(id, "US-ASCII");
                    int length = data.getInt(); if (version == 4) length = sync(length);
                    int frameFlags = data.getShort() & 65535;
                    if (length <= 0 || length > data.remaining()) break;
                    byte[] content = new byte[length]; data.get(content);
                    if (frameFlags != 0 || length < 2) continue;
                    if (frame.equals("USLT")) {
                        int end = terminator(content, 4, content[0]);
                        track.put("embeddedLyrics", text(content, end, content.length - end, content[0]));
                    } else if (frame.equals("TXXX")) {
                        int end = terminator(content, 1, content[0]);
                        int terminatorSize = content[0] == 1 || content[0] == 2 ? 2 : 1;
                        tag(track, text(content, 1, Math.max(0, end - 1 - terminatorSize), content[0]), text(content, end, content.length - end, content[0]));
                    }
                }
            }
        } catch (Exception ignored) { /* Optional tags must not make an otherwise playable song disappear. */ }
    }
    private static int sync(int n) { return (n & 127) | (n >> 1 & 0x3f80) | (n >> 2 & 0x1fc000) | (n >> 3 & 0xfe00000); }
    private static String text(byte[] data, int start, int count, int encoding) {
        if (start < 0 || count < 0 || start + count > data.length) return "";
        String[] codecs = {"ISO-8859-1", "UTF-16", "UTF-16BE", "UTF-8"};
        return new String(data, start, count, Charset.forName(codecs[Math.max(0, Math.min(3, encoding))])).replace("\0", "").trim();
    }
    private static int terminator(byte[] data, int start, int encoding) {
        int step = encoding == 1 || encoding == 2 ? 2 : 1;
        for (int i = start; i + step <= data.length; i += step)
            if (data[i] == 0 && (step == 1 || data[i + 1] == 0)) return i + step;
        return data.length;
    }
    private static void tag(JSONObject track, String name, String value) throws Exception {
        String key = name.toLowerCase(Locale.ROOT);
        if (key.startsWith("replaygain_")) track.getJSONObject("replayGain").put(key, value);
        else if (key.startsWith("lyrics") || key.equals("unsyncedlyrics")) track.put("embeddedLyrics", value);
        else if (key.equals("discnumber")) track.put("discNumber", value);
        else if (key.equals("tracknumber")) track.put("trackNumber", value);
    }
    static void skip(InputStream input, long count) throws IOException {
        while (count > 0) { long n = input.skip(count); if (n == 0) { if (input.read() == -1) throw new EOFException(); n = 1; } count -= n; }
    }
}
