package cn.yuusei.m3x;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import java.io.*;
import java.nio.*;
import java.util.Arrays;

/** Uncompressed DSF/DFF to 24-bit PCM; this is explicitly not native DSD or DoP. */
final class DsdDecoder {
    static final class Header {
        int channels, rate, block; long samples, offset, bytes; boolean dsf, lsb;
    }
    static Header header(InputStream raw) throws Exception {
        DataInputStream input = new DataInputStream(raw);
        byte[] magic = new byte[4]; input.readFully(magic); Header h = new Header();
        if (new String(magic, "US-ASCII").equals("DSD ")) {
            byte[] data = new byte[88]; input.readFully(data);
            ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (b.getLong(0) != 28 || !new String(data, 24, 4, "US-ASCII").equals("fmt ")) throw new IOException("DSF 文件头无效");
            h.dsf = true; h.channels = b.getInt(48); h.rate = b.getInt(52); h.lsb = b.getInt(56) == 1;
            h.samples = b.getLong(60); h.block = b.getInt(68); h.offset = 92;
            h.bytes = ((h.samples + 7) / 8) * h.channels;
            if (h.block < 1 || h.block > 65536) throw new IOException("DSF 块大小无效");
        } else if (new String(magic, "US-ASCII").equals("FRM8")) {
            input.readLong(); byte[] form = new byte[4]; input.readFully(form);
            if (!new String(form, "US-ASCII").equals("DSD ")) throw new IOException("不支持此 DFF");
            long position = 16;
            while (true) {
                byte[] type = new byte[4]; input.readFully(type); long size = input.readLong(); position += 12;
                if (size < 0) throw new IOException("DFF 数据大小无效");
                String name = new String(type, "US-ASCII");
                if (name.equals("DSD ")) { h.offset = position; h.bytes = size; break; }
                if (name.equals("DST ")) throw new IOException("暂不支持 DST 压缩，请转换为无压缩 DSF/DFF");
                if (name.equals("PROP") && size < 65536) {
                    byte[] data = new byte[(int) size]; input.readFully(data);
                    ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
                    b.position(4);
                    while (b.remaining() >= 12) {
                        byte[] id = new byte[4]; b.get(id); long length = b.getLong(); int at = b.position();
                        if (length < 0 || length > b.remaining()) break;
                        String prop = new String(id, "US-ASCII");
                        if (prop.equals("FS  ") && length >= 4) h.rate = b.getInt();
                        if (prop.equals("CHNL") && length >= 2) h.channels = b.getShort() & 65535;
                        b.position(at + (int) length + (int) (length & 1));
                    }
                } else TagReader.skip(input, size);
                if ((size & 1) != 0) input.readByte();
                position += size + (size & 1);
            }
            h.samples = h.channels > 0 ? h.bytes * 8 / h.channels : 0;
        } else throw new IOException("不是 DSF/DFF 文件");
        if (h.channels < 1 || h.channels > 2 || h.rate < 2822400 || h.rate > 22579200 || h.rate % 88200 != 0 || h.samples <= 0)
            throw new IOException("仅支持单/双声道 DSD64～DSD512");
        return h;
    }

    static void inspect(Context context, Uri uri, JSONObject track) throws Exception {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            Header h = header(input);
            track.put("codec", "DSD"); track.put("bits", 1); track.put("channels", h.channels);
            track.put("sampleRate", h.rate); track.put("duration", h.samples / (double) h.rate);
        }
    }

    static File convert(Context context, Uri uri, String id) throws Exception {
        File directory = new File(context.getCacheDir(), "decoded"); directory.mkdirs();
        File target = new File(directory, id + "-dsd.wav");
        if (target.isFile() && target.length() > 44) return target;
        File partial = new File(directory, id + "-dsd.tmp");
        try (InputStream raw = new BufferedInputStream(context.getContentResolver().openInputStream(uri), 65536)) {
            Header h = header(raw);
            int decimation = h.rate / 88200, stride = decimation / 8, taps = decimation * 8, groups = taps / 8;
            long frames = h.samples / decimation, size = frames * h.channels * 3;
            if (size > 0xfffffff0L || directory.getUsableSpace() < size + 32 * 1024 * 1024) throw new IOException("DSD 转 PCM 缓存空间不足");
            float[][] table = coefficients(taps, h.rate, h.lsb);
            byte[][] history = new byte[h.channels][groups];
            for (byte[] channel : history) Arrays.fill(channel, (byte) 0x69);
            byte[][] block = new byte[h.channels][h.dsf ? h.block : 1];
            try (RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
                output.setLength(0); wavHeader(output, 88200, h.channels, 24, size);
                long remaining = (h.samples + 7) / 8, written = 0, count = 0; int cursor = 0;
                DataInputStream input = new DataInputStream(raw);
                byte[] pcm = new byte[3 * h.channels];
                while (remaining > 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("转换已取消");
                    int bytes = (int) Math.min(remaining, h.dsf ? h.block : 1);
                    if (h.dsf) for (int channel = 0; channel < h.channels; channel++) input.readFully(block[channel]);
                    else for (int channel = 0; channel < h.channels; channel++) block[channel][0] = input.readByte();
                    for (int i = 0; i < bytes; i++) {
                        for (int channel = 0; channel < h.channels; channel++) history[channel][cursor] = block[channel][i];
                        cursor = (cursor + 1) % groups;
                        if (++count % stride != 0 || written >= frames) continue;
                        for (int channel = 0; channel < h.channels; channel++) {
                            double value = 0;
                            for (int age = 0; age < groups; age++) value += table[age][history[channel][(cursor + groups - 1 - age) % groups] & 255];
                            int sample = (int) Math.round(Math.max(-1, Math.min(.99999988, value * .5)) * 8388608);
                            pcm[channel * 3] = (byte) sample; pcm[channel * 3 + 1] = (byte) (sample >> 8); pcm[channel * 3 + 2] = (byte) (sample >> 16);
                        }
                        output.write(pcm); written++;
                    }
                    remaining -= bytes;
                }
            }
            if (!partial.renameTo(target)) throw new IOException("无法保存 DSD 缓存");
            return target;
        } finally { if (partial.exists()) partial.delete(); }
    }

    static float[][] coefficients(int taps, int rate, boolean lsb) {
        double[] weights = new double[taps]; double sum = 0, cutoff = 39000d / rate;
        for (int i = 0; i < taps; i++) {
            double t = i - (taps - 1) / 2d;
            weights[i] = 2 * cutoff * (t == 0 ? 1 : Math.sin(2 * Math.PI * cutoff * t) / (2 * Math.PI * cutoff * t))
                * (.42 - .5 * Math.cos(2 * Math.PI * i / (taps - 1)) + .08 * Math.cos(4 * Math.PI * i / (taps - 1)));
            sum += weights[i];
        }
        float[][] table = new float[taps / 8][256];
        for (int group = 0; group < table.length; group++) for (int b = 0; b < 256; b++)
            for (int k = 0; k < 8; k++) table[group][b] += (((b >> (lsb ? 7 - k : k)) & 1) == 0 ? -1 : 1) * weights[group * 8 + k] / sum;
        return table;
    }

    static void wavHeader(RandomAccessFile file, int rate, int channels, int bits, long size) throws IOException {
        file.writeBytes("RIFF"); file.writeInt(Integer.reverseBytes((int) (size + 36))); file.writeBytes("WAVEfmt ");
        file.writeInt(Integer.reverseBytes(16)); file.writeShort(Short.reverseBytes((short) 1));
        file.writeShort(Short.reverseBytes((short) channels)); file.writeInt(Integer.reverseBytes(rate));
        file.writeInt(Integer.reverseBytes(rate * channels * bits / 8)); file.writeShort(Short.reverseBytes((short) (channels * bits / 8)));
        file.writeShort(Short.reverseBytes((short) bits)); file.writeBytes("data"); file.writeInt(Integer.reverseBytes((int) size));
    }
}
