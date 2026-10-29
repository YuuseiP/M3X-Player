package cn.yuusei.m3x;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.nio.*;
import java.nio.charset.Charset;
import java.util.Locale;

final class TagReader {
    static byte[] read(Context context, Uri uri, JSONObject track) {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            return read(input, track);
        } catch (Exception ignored) { /* Optional tags must not make an otherwise playable song disappear. */ }
        return null;
    }
    static byte[] read(InputStream stream, JSONObject track) throws Exception {
        DataInputStream input = new DataInputStream(new BufferedInputStream(stream));
        byte[] picture = null;
        byte[] magic = new byte[4]; input.readFully(magic);
        if (new String(magic, "US-ASCII").equals("fLaC")) {
            boolean last = false, first = true;
            int rate = 0, channels = 0, bits = 0;
            long samples = 0;
            while (!last) {
                int kind = input.readUnsignedByte(); last = (kind & 128) != 0; kind &= 127;
                int length = input.readUnsignedByte() << 16 | input.readUnsignedByte() << 8 | input.readUnsignedByte();
                if (first && (kind != 0 || length != 34)) throw new IOException("Invalid FLAC STREAMINFO");
                first = false;
                // Large artwork/padding is optional; skip it without allocating or losing STREAMINFO.
                if ((kind == 4 && length > 8 * 1024 * 1024) || (kind == 6 && length > 12 * 1024 * 1024)) {
                    skip(input, length); continue;
                }
                if (kind != 0 && kind != 4 && kind != 6) { skip(input, length); continue; }
                byte[] bytes = new byte[length]; input.readFully(bytes);
                if (kind == 0 && length == 34) {
                    long info = ByteBuffer.wrap(bytes).getLong(10);
                    rate = (int) (info >>> 44);
                    channels = (int) (info >>> 41 & 7) + 1;
                    bits = (int) (info >>> 36 & 31) + 1;
                    samples = info & 0xfffffffffL;
                    track.put("sampleRate", rate); track.put("channels", channels); track.put("bits", bits);
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
                } else if (kind == 6) {
                    ByteBuffer data = ByteBuffer.wrap(bytes);
                    int type = data.getInt();
                    int mime = data.getInt();
                    if (mime < 0 || mime > data.remaining()) throw new IOException("Invalid FLAC picture MIME");
                    data.position(data.position() + mime);
                    int description = data.getInt();
                    if (description < 0 || description > data.remaining() - 20) throw new IOException("Invalid FLAC picture description");
                    data.position(data.position() + description + 16);
                    int size = data.getInt();
                    if (size < 0 || size != data.remaining()) throw new IOException("Invalid FLAC picture length");
                    if (picture == null || type == 3) {
                        picture = new byte[size]; data.get(picture);
                    }
                }
            }
            if (!last || rate <= 0 || bits < 4 || samples == 0 || (input.readUnsignedShort() & 0xfffe) != 0xfff8)
                throw new IOException("Incomplete FLAC metadata or audio frame");
            track.put("duration", samples / (double) rate);
        } else if (new String(magic, "US-ASCII").equals("RIFF")) {
            input.readInt();
            if (input.readInt() != 0x57415645) throw new IOException("Invalid WAVE header");
            int rate = 0, channels = 0, align = 0, bits = 0;
            while (true) {
                byte[] id = new byte[4]; input.readFully(id);
                long length = Integer.toUnsignedLong(Integer.reverseBytes(input.readInt()));
                String chunk = new String(id, "US-ASCII");
                if (chunk.equals("fmt ")) {
                    if (length < 16 || length > 65536) throw new IOException("Invalid WAVE format");
                    byte[] fmt = new byte[(int) length]; input.readFully(fmt);
                    ByteBuffer data = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN);
                    int encoding = data.getShort() & 65535;
                    channels = data.getShort() & 65535; rate = data.getInt();
                    data.getInt(); align = data.getShort() & 65535; bits = data.getShort() & 65535;
                    if (encoding == 65534) {
                        if (length < 40 || (data.getShort(16) & 65535) < 22) throw new IOException("Invalid WAVE extensible format");
                        encoding = data.getInt(24);
                        byte[] guidTail = {0, 0, 16, 0, (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113};
                        for (int i = 0; i < guidTail.length; i++)
                            if (fmt[28 + i] != guidTail[i]) throw new IOException("Unsupported WAVE subformat");
                    }
                    if ((encoding != 1 && encoding != 3) || channels <= 0 || rate <= 0 ||
                        (bits != 8 && bits != 16 && bits != 24 && bits != 32 && bits != 64) ||
                        (encoding == 3 && bits != 32 && bits != 64) || align != channels * ((bits + 7) / 8))
                        throw new IOException("Unsupported WAVE PCM format");
                    if ((length & 1) != 0) skip(input, 1);
                    continue;
                } else if (chunk.equals("data") && rate > 0) {
                    if (length == 0 || length % align != 0) throw new IOException("Invalid WAVE data length");
                    input.readUnsignedByte();
                    track.put("sampleRate", rate); track.put("channels", channels); track.put("bits", bits);
                    track.put("duration", length / (double) align / rate);
                    track.put("bitrate", (long) rate * align * 8);
                    break;
                }
                skip(input, length + (length & 1));
            }
        } else if (new String(magic, "US-ASCII").equals("FORM")) {
            AiffHeader h = AiffHeader.read(new SequenceInputStream(new ByteArrayInputStream(magic), input));
            track.put("sampleRate", h.rate); track.put("channels", h.channels); track.put("bits", h.bits);
            track.put("duration", h.frames / (double) h.rate);
            track.put("bitrate", (long) h.rate * h.frameSize * 8);
        } else if (magic[0] == 'I' && magic[1] == 'D' && magic[2] == '3') {
            int version = magic[3]; input.readUnsignedByte(); int flags = input.readUnsignedByte();
            int size = sync(input.readInt());
            if (size > 4 * 1024 * 1024 || (flags & 128) != 0 || version < 3 || version > 4) return null;
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
        return picture;
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
        else if (!value.trim().isEmpty()) {
            if (key.equals("title") || key.equals("artist") || key.equals("album")) track.put(key, value);
            else if ((key.equals("albumartist") || key.equals("album_artist")) && track.optString("artist").equals("未知歌手")) track.put("artist", value);
            else if (key.equals("date") || key.equals("year")) track.put("year", value);
        }
    }
    static void skip(InputStream input, long count) throws IOException {
        while (count > 0) { long n = input.skip(count); if (n == 0) { if (input.read() == -1) throw new EOFException(); n = 1; } count -= n; }
    }
}
