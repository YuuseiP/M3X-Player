package cn.yuusei.m3x;

import java.io.*;

/** Bounded AIFF / uncompressed AIFF-C parser shared by scanning and playback. */
final class AiffHeader {
    int channels, bits, rate, frameSize;
    long frames, dataStart, dataSize;
    boolean littleEndian;

    static AiffHeader read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != 0x464f524d) throw new IOException("Invalid AIFF FORM");
        long end = 8 + Integer.toUnsignedLong(in.readInt());
        int type = in.readInt();
        if (type != 0x41494646 && type != 0x41494643) throw new IOException("Unsupported AIFF form");
        AiffHeader h = new AiffHeader();
        long position = 12;
        boolean common = false;
        while (position + 8 <= end) {
            int id = in.readInt();
            long size = Integer.toUnsignedLong(in.readInt()); position += 8;
            if (size > end - position) throw new IOException("AIFF chunk exceeds FORM");
            if (id == 0x434f4d4d) {
                int minimum = type == 0x41494643 ? 22 : 18;
                if (size < minimum) throw new IOException("Short AIFF COMM");
                h.channels = in.readUnsignedShort(); h.frames = Integer.toUnsignedLong(in.readInt());
                h.bits = in.readUnsignedShort();
                int exponent = in.readUnsignedShort();
                long mantissa = in.readLong();
                double rate = Math.scalb((double) (mantissa >>> 1), exponent - 16383 - 62);
                if ((exponent & 0x8000) != 0 || exponent == 0x7fff || rate < 1 || rate > 768000
                        || Math.abs(rate - Math.rint(rate)) > .0001 || mantissa >= 0)
                    throw new IOException("Invalid AIFF sample rate");
                h.rate = (int) Math.rint(rate);
                if (h.channels < 1 || h.channels > 32 || h.frames == 0
                        || (h.bits != 8 && h.bits != 16 && h.bits != 24 && h.bits != 32))
                    throw new IOException("Unsupported AIFF PCM format");
                if (type == 0x41494643) {
                    int compression = in.readInt();
                    h.littleEndian = compression == 0x736f7774; // sowt
                    if (!h.littleEndian && compression != 0x4e4f4e45 && compression != 0x74776f73)
                        throw new IOException("Unsupported AIFF-C compression");
                }
                h.frameSize = h.channels * (h.bits / 8);
                skip(in, size - minimum); common = true;
            } else if (id == 0x53534e44) {
                if (!common || size < 8) throw new IOException("AIFF SSND requires COMM");
                long offset = Integer.toUnsignedLong(in.readInt()); in.readInt();
                h.dataSize = h.frames * h.frameSize;
                if (offset > size - 8 || h.dataSize > size - 8 - offset)
                    throw new IOException("Truncated AIFF samples");
                skip(in, offset); h.dataStart = position + 8 + offset;
                return h;
            } else skip(in, size);
            if ((size & 1) != 0) skip(in, 1);
            position += size + (size & 1);
        }
        throw new IOException("Missing AIFF audio");
    }

    private static void skip(DataInputStream in, long count) throws IOException {
        while (count > 0) {
            long n = in.skip(count);
            if (n == 0) { in.readByte(); n = 1; }
            count -= n;
        }
    }

    void toLittleEndian(byte[] pcm, int length) {
        int bytes = bits / 8;
        if (bytes == 1) { for (int i = 0; i < length; i++) pcm[i] ^= (byte) 0x80; }
        else if (!littleEndian) for (int i = 0; i < length; i += bytes)
            for (int j = 0; j < bytes / 2; j++) {
                byte b = pcm[i + j]; pcm[i + j] = pcm[i + bytes - 1 - j]; pcm[i + bytes - 1 - j] = b;
            }
    }
}
