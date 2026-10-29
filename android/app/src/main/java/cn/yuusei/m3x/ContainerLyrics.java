package cn.yuusei.m3x;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded metadata-only read for AIFF/WAV ID3 and MP4 copyright-lyr atoms. */
final class ContainerLyrics {
    static String read(InputStream source) throws Exception {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(source))) {
            int magic = in.readInt();
            if (magic == 0x464f524d || magic == 0x52494646) {
                boolean little = magic == 0x52494646;
                long length = Integer.toUnsignedLong(little ? Integer.reverseBytes(in.readInt()) : in.readInt());
                in.readInt(); long left = length - 4;
                while (left >= 8) {
                    int id = in.readInt(); long size = Integer.toUnsignedLong(little ? Integer.reverseBytes(in.readInt()) : in.readInt());
                    left -= 8; if (size > left) throw new IOException("标签块越界");
                    if ((id == 0x49443320 || id == 0x69643320) && size <= 2 * 1024 * 1024) {
                        byte[] bytes = new byte[(int)size]; in.readFully(bytes);
                        JSONObject tags = new JSONObject(); TagReader.read(new ByteArrayInputStream(bytes), tags);
                        return tags.optString("embeddedLyrics");
                    }
                    TagReader.skip(in, size + (size & 1)); left -= size + (size & 1);
                }
                return "";
            }
            // MP4 size was already consumed; let the recursive scanner read from byte zero.
            byte[] first = {(byte)(magic >>> 24), (byte)(magic >>> 16), (byte)(magic >>> 8), (byte)magic};
            return atoms(new DataInputStream(new SequenceInputStream(new ByteArrayInputStream(first), in)), Long.MAX_VALUE, 0, false);
        }
    }
    private static String atoms(DataInputStream in, long remaining, int depth, boolean lyric) throws Exception {
        if (depth > 8) throw new IOException("MP4 元数据嵌套过深");
        int count = 0;
        while (remaining >= 8 && ++count < 10000) {
            long size;
            try { size = Integer.toUnsignedLong(in.readInt()); } catch (EOFException end) { return ""; }
            int type = in.readInt(); int header = 8;
            if (size == 1) { size = in.readLong(); header = 16; }
            if (size == 0) return "";
            if (size < header || size > remaining) throw new IOException("MP4 标签长度无效");
            long payload = size - header;
            if (lyric && type == 0x64617461 && payload >= 8 && payload <= 2 * 1024 * 1024) {
                int encoding = in.readInt() & 0xffffff; in.readInt();
                byte[] text = new byte[(int)payload - 8]; in.readFully(text);
                if (encoding != 1 && encoding != 2) return "";
                return new String(text, encoding == 2 ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_8);
            }
            if (type == 0x6d6f6f76 || type == 0x75647461 || type == 0x696c7374 || type == 0xa96c7972 || type == 0x6d657461) {
                if (type == 0x6d657461) { if (payload < 4) throw new IOException("MP4 meta 过短"); in.readInt(); payload -= 4; }
                String found = atoms(in, payload, depth + 1, type == 0xa96c7972);
                if (!found.isEmpty()) return found;
            } else TagReader.skip(in, payload);
            remaining -= size;
        }
        return "";
    }
}
