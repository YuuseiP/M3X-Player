package cn.yuusei.m3x;

import androidx.media3.common.*;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.*;
import java.io.*;
import java.util.Arrays;

/** Streams frame-aligned PCM without converting or caching the complete song. */
@UnstableApi
final class AiffExtractor implements Extractor {
    static ExtractorsFactory factory() {
        return () -> {
            Extractor[] defaults = new DefaultExtractorsFactory().createExtractors();
            Extractor[] all = Arrays.copyOf(defaults, defaults.length + 1);
            System.arraycopy(all, 0, all, 1, defaults.length);
            all[0] = new AiffExtractor(); return all;
        };
    }

    private ExtractorOutput output;
    private TrackOutput track;
    private AiffHeader header;
    private final byte[] pcm = new byte[32768];

    @Override public boolean sniff(ExtractorInput input) throws IOException {
        byte[] bytes = new byte[12];
        if (!input.peekFully(bytes, 0, 12, true)) return false;
        return bytes[0] == 'F' && bytes[1] == 'O' && bytes[2] == 'R' && bytes[3] == 'M'
            && bytes[8] == 'A' && bytes[9] == 'I' && bytes[10] == 'F' && (bytes[11] == 'F' || bytes[11] == 'C');
    }
    @Override public void init(ExtractorOutput output) { this.output = output; }
    @Override public int read(ExtractorInput input, PositionHolder seek) throws IOException {
        if (header == null) {
            header = AiffHeader.read(new InputStream() {
                @Override public int read() throws IOException {
                    byte[] b = new byte[1]; return read(b, 0, 1) == -1 ? -1 : b[0] & 255;
                }
                @Override public int read(byte[] b, int offset, int length) throws IOException { return input.read(b, offset, length); }
                @Override public long skip(long n) throws IOException { return input.skip((int) Math.min(n, 65536)); }
            });
            if (input.getLength() != C.LENGTH_UNSET && header.dataStart + header.dataSize > input.getLength())
                throw new EOFException("Truncated AIFF file");
            track = output.track(0, C.TRACK_TYPE_AUDIO);
            int encoding = header.bits == 8 ? C.ENCODING_PCM_8BIT : header.bits == 16 ? C.ENCODING_PCM_16BIT
                : header.bits == 24 ? C.ENCODING_PCM_24BIT : C.ENCODING_PCM_32BIT;
            track.format(new Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(header.channels)
                .setSampleRate(header.rate).setPcmEncoding(encoding).setMaxInputSize(pcm.length).build());
            output.endTracks();
            output.seekMap(new SeekMap() {
                @Override public boolean isSeekable() { return true; }
                @Override public long getDurationUs() { return header.frames * 1000000L / header.rate; }
                @Override public SeekPoints getSeekPoints(long timeUs) {
                    long frame = Math.max(0, Math.min(header.frames - 1, timeUs * header.rate / 1000000));
                    return new SeekPoints(new SeekPoint(frame * 1000000L / header.rate, header.dataStart + frame * header.frameSize));
                }
            });
        }
        if (input.getPosition() < header.dataStart) {
            seek.position = header.dataStart; return RESULT_SEEK;
        }
        long offset = input.getPosition() - header.dataStart;
        long remaining = header.dataSize - offset;
        if (remaining <= 0) return RESULT_END_OF_INPUT;
        int size = (int) Math.min(remaining, pcm.length / header.frameSize * header.frameSize);
        input.readFully(pcm, 0, size);
        header.toLittleEndian(pcm, size);
        track.sampleData(new ParsableByteArray(pcm, size), size);
        track.sampleMetadata(offset / header.frameSize * 1000000L / header.rate, C.BUFFER_FLAG_KEY_FRAME, size, 0, null);
        return RESULT_CONTINUE;
    }
    @Override public void seek(long position, long timeUs) { }
    @Override public void release() { }
}
