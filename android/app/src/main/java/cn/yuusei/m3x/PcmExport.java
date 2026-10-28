package cn.yuusei.m3x;

import android.content.Context;
import android.media.*;
import android.net.Uri;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import androidx.media3.decoder.flac.FlacDecoderJni;
import androidx.media3.datasource.*;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.extractor.DefaultExtractorInput;
import androidx.media3.extractor.FlacStreamMetadata;

final class PcmExport {
    static File decode(Context context, Uri uri, long startMs, long endMs, boolean reverse, String id) throws Exception {
        try { return decodePlatform(context, uri, startMs, endMs, reverse, id); }
        catch (Exception error) {
            DefaultDataSource source = new DefaultDataSource(context, new OkHttpDataSource.Factory(NativeNetwork.HTTP).createDataSource());
            boolean flac = false;
            try (InputStream input = new DataSourceInputStream(source,
                    new DataSpec.Builder().setUri(uri).setHttpRequestHeaders(NativeNetwork.headers(uri.toString())).build())) {
                flac = new DataInputStream(input).readInt() == 0x664c6143;
            } catch (IOException ignored) {}
            if (!flac) throw error;
            return decodeFlac(context, uri, startMs, endMs, reverse, id);
        }
    }

    private static File decodePlatform(Context context, Uri uri, long startMs, long endMs, boolean reverse, String id) throws Exception {
        if (startMs < 0 || (endMs > 0 && endMs <= startMs)) throw new IllegalArgumentException("片段起止时间不正确");
        File directory = new File(context.getCacheDir(), "decoded"); directory.mkdirs();
        File target = new File(directory, id + (reverse ? "-reverse" : "-clip") + ".wav");
        File temporary = new File(directory, id + "-export.tmp");
        MediaExtractor extractor = new MediaExtractor(); MediaCodec decoder = null; boolean complete = false;
        try (RandomAccessFile output = new RandomAccessFile(temporary, "rw")) {
            output.setLength(44);
            output.seek(44);
            extractor.setDataSource(context, uri, null);
            MediaFormat format = null; int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).startsWith("audio/")) { format = extractor.getTrackFormat(i); track = i; break; }
            if (format == null) throw new IOException("没有可解码的音轨");
            extractor.selectTrack(track);
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME));
            decoder.configure(format, null, null, 0); decoder.start();
            boolean inputEnd = false, outputEnd = false;
            int rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE), channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding = 2;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long deadline = android.os.SystemClock.elapsedRealtime() + 120000;
            while (!outputEnd) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("导出已取消");
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("解码超时");
                if (directory.getUsableSpace() < 16 * 1024 * 1024 || output.length() > 0xffffff00L) throw new IOException("缓存空间不足或片段超过 WAV 大小限制");
                if (!inputEnd) {
                    int index = decoder.dequeueInputBuffer(10000);
                    if (index >= 0) {
                        ByteBuffer input = decoder.getInputBuffer(index);
                        int size = extractor.readSampleData(input, 0); long time = extractor.getSampleTime();
                        if (size < 0 || (endMs > 0 && time > endMs * 1000 + 1000000)) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnd = true;
                        } else { decoder.queueInputBuffer(index, 0, size, time, 0); extractor.advance(); }
                    }
                }
                int index = decoder.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat decoded = decoder.getOutputFormat(); rate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING)) encoding = decoded.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    if (encoding != 2 && encoding != 4) throw new IOException("导出仅支持 PCM16 / PCM Float 解码输出");
                } else if (index >= 0) {
                    ByteBuffer decoded = decoder.getOutputBuffer(index).order(ByteOrder.LITTLE_ENDIAN);
                    decoded.position(info.offset); decoded.limit(info.offset + info.size);
                    int frameSize = channels * (encoding == 4 ? 4 : 2);
                    long first = Math.max(0, (long) Math.ceil((startMs * 1000 - info.presentationTimeUs) * rate / 1e6));
                    long last = endMs > 0 ? (long) Math.ceil((endMs * 1000 - info.presentationTimeUs) * rate / 1e6) : info.size / frameSize;
                    int from = (int) Math.min(info.size / frameSize, first), to = (int) Math.max(from, Math.min(info.size / frameSize, last));
                    decoded.position(info.offset + from * frameSize);
                    byte[] bytes = new byte[(to - from) * channels * 2];
                    ByteBuffer pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
                    for (int i = 0; i < (to - from) * channels; i++) pcm.putShort(encoding == 4
                        ? (short) Math.round(Math.max(-1, Math.min(.999969, decoded.getFloat())) * 32768) : decoded.getShort());
                    output.write(bytes);
                    outputEnd = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0 || (endMs > 0 && info.presentationTimeUs > endMs * 1000);
                    decoder.releaseOutputBuffer(index, false);
                }
            }
            long size = output.length() - 44;
            if (size == 0) throw new IOException("片段没有音频数据");
            if (reverse) reverse(output, channels * 2);
            output.seek(0); DsdDecoder.wavHeader(output, rate, channels, 16, size);
            output.getFD().sync();
            complete = true;
        } finally {
            try {
                if (decoder != null) { try { decoder.stop(); } catch (IllegalStateException ignored) {} decoder.release(); }
            } finally { extractor.release(); if (!complete) temporary.delete(); }
        }
        if (target.exists() && !target.delete()) throw new IOException("无法替换旧导出缓存");
        if (!temporary.renameTo(target)) throw new IOException("无法保存导出结果");
        return target;
    }

    private static File decodeFlac(Context context, Uri uri, long startMs, long endMs, boolean reverse, String id) throws Exception {
        if (startMs < 0 || (endMs > 0 && endMs <= startMs)) throw new IllegalArgumentException("片段起止时间不正确");
        File directory = new File(context.getCacheDir(), "decoded"); directory.mkdirs();
        File target = new File(directory, id + (reverse ? "-reverse" : "-clip") + ".wav");
        File temporary = new File(directory, id + "-export.tmp");
        DefaultDataSource input = new DefaultDataSource(context, new OkHttpDataSource.Factory(NativeNetwork.HTTP).createDataSource());
        FlacDecoderJni decoder = null;
        boolean complete = false;
        try (RandomAccessFile output = new RandomAccessFile(temporary, "rw")) {
            output.setLength(44); output.seek(44);
            long length = input.open(new DataSpec.Builder().setUri(uri).setHttpRequestHeaders(NativeNetwork.headers(uri.toString())).build());
            decoder = new FlacDecoderJni();
            decoder.setData(new DefaultExtractorInput(input, 0, length));
            FlacStreamMetadata metadata = decoder.decodeStreamMetadata();
            int channels = metadata.channels, rate = metadata.sampleRate, bytesPerSample = metadata.bitsPerSample / 8;
            ByteBuffer frame = ByteBuffer.allocateDirect(metadata.getMaxDecodedFrameSize());
            long deadline = android.os.SystemClock.elapsedRealtime() + 120000;
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("导出已取消");
                if (android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("解码超时");
                if (directory.getUsableSpace() < 16 * 1024 * 1024 || output.length() > 0xffffff00L) throw new IOException("缓存空间不足或片段超过 WAV 大小限制");
                decoder.decodeSample(frame);
                if (!frame.hasRemaining()) break;
                long timeUs = decoder.getLastFrameTimestamp();
                int frames = frame.remaining() / bytesPerSample / channels;
                int from = (int) Math.min(frames, Math.max(0, (long) Math.ceil((startMs * 1000 - timeUs) * rate / 1e6)));
                int to = endMs > 0 ? (int) Math.max(from, Math.min(frames, (long) Math.ceil((endMs * 1000 - timeUs) * rate / 1e6))) : frames;
                byte[] pcm = new byte[(to - from) * channels * 2];
                for (int i = 0; i < (to - from) * channels; i++) {
                    int offset = (from * channels + i) * bytesPerSample;
                    if (bytesPerSample == 1) { pcm[i * 2] = 0; pcm[i * 2 + 1] = (byte) ((frame.get(offset) & 255) - 128); }
                    else { pcm[i * 2] = frame.get(offset + bytesPerSample - 2); pcm[i * 2 + 1] = frame.get(offset + bytesPerSample - 1); }
                }
                output.write(pcm);
                if (endMs > 0 && timeUs + frames * 1e6 / rate >= endMs * 1000) break;
            }
            long size = output.length() - 44;
            if (size == 0) throw new IOException("片段没有音频数据");
            if (reverse) reverse(output, channels * 2);
            output.seek(0); DsdDecoder.wavHeader(output, rate, channels, 16, size); output.getFD().sync();
            complete = true;
        } finally {
            try { if (decoder != null) decoder.release(); }
            finally { try { input.close(); } finally { if (!complete) temporary.delete(); } }
        }
        if (target.exists() && !target.delete()) throw new IOException("无法替换旧导出缓存");
        if (!temporary.renameTo(target)) throw new IOException("无法保存导出结果");
        return target;
    }

    static void reverse(RandomAccessFile file, int frameSize) throws IOException {
        long left = 44, right = file.length();
        int capacity = 65536 / frameSize * frameSize;
        while (right - left > frameSize) {
            int count = (int) Math.min(capacity, (right - left) / 2 / frameSize * frameSize);
            if (count == 0) break;
            byte[] first = new byte[count], last = new byte[count];
            file.seek(left); file.readFully(first); file.seek(right - count); file.readFully(last);
            reverseFrames(first, frameSize); reverseFrames(last, frameSize);
            file.seek(left); file.write(last); file.seek(right - count); file.write(first);
            left += count; right -= count;
        }
    }
    static void reverseFrames(byte[] data, int frameSize) {
        for (int left = 0, right = data.length - frameSize; left < right; left += frameSize, right -= frameSize)
            for (int byteIndex = 0; byteIndex < frameSize; byteIndex++) { byte swap = data[left + byteIndex]; data[left + byteIndex] = data[right + byteIndex]; data[right + byteIndex] = swap; }
    }
}
