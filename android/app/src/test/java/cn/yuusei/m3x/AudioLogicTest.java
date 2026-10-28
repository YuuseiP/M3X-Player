package cn.yuusei.m3x;

import org.junit.Test;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class AudioLogicTest {
    @Test public void contentHashKeepsExistingSongIds() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de", MusicLibrary.hash("abc"));
        assertEquals(MusicLibrary.hash("abc"), MusicLibrary.hash(new byte[]{97, 98, 99}));
        assertNotEquals(MusicLibrary.hash(new byte[]{1, 2}), MusicLibrary.hash(new byte[]{1, 3}));
    }

    @Test public void flacHeaderScanKeepsTagsAndCoverWithoutReadingAudio() throws Exception {
        byte[] data = flacHeader(false);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write(data); file.write(new byte[1024 * 1024]);
        int[] bytesRead = {0};
        InputStream source = new FilterInputStream(new ByteArrayInputStream(file.toByteArray())) {
            @Override public int read(byte[] buffer, int offset, int count) throws IOException {
                int n = super.read(buffer, offset, count); if (n > 0) bytesRead[0] += n; return n;
            }
        };
        JSONObject track = new JSONObject().put("replayGain", new JSONObject());
        assertArrayEquals(new byte[]{1, 2, 3}, TagReader.read(source, track));
        assertEquals(96000, track.getInt("sampleRate")); assertEquals(24, track.getInt("bits"));
        assertEquals(2, track.getInt("channels")); assertEquals(180, track.getDouble("duration"), 1e-9);
        assertEquals("Title", track.getString("title")); assertEquals("Artist", track.getString("artist"));
        assertEquals("Album", track.getString("album")); assertEquals("2026", track.getString("year"));
        assertEquals("2/10", track.getString("trackNumber")); assertEquals("1", track.getString("discNumber"));
        assertEquals("[00:01]Lyrics", track.getString("embeddedLyrics"));
        assertEquals("-6 dB", track.getJSONObject("replayGain").getString("replaygain_track_gain"));
        assertTrue("Only headers and buffered read-ahead, not the audio payload", bytesRead[0] <= data.length + 8192);
    }

    @Test public void incompleteFlacHeadersDoNotEnableFastScan() throws Exception {
        byte[] valid = flacHeader(false);
        byte[] oversized = Arrays.copyOf(valid, 46);
        oversized[42] = (byte) 0x84; oversized[43] = (byte) 0x80; oversized[44] = 0; oversized[45] = 1;
        byte[] zeroSamples = valid.clone();
        zeroSamples[21] &= (byte) 0xf0; Arrays.fill(zeroSamples, 22, 26, (byte) 0);
        byte[] invalidFrame = valid.clone(); invalidFrame[invalidFrame.length - 2] = 0;
        for (byte[] bytes : new byte[][]{Arrays.copyOf(valid, 20), Arrays.copyOf(valid, valid.length - 2),
                flacHeader(true), oversized, zeroSamples, invalidFrame}) {
            JSONObject track = new JSONObject().put("replayGain", new JSONObject());
            try { TagReader.read(new ByteArrayInputStream(bytes), track); fail("Malformed FLAC must fall back to the platform reader"); }
            catch (IOException expected) {
                assertFalse(track.has("duration"));
                if (bytes == zeroSamples) {
                    assertEquals(24, track.getInt("bits"));
                    assertEquals(96000, track.getInt("sampleRate"));
                }
            }
        }
    }

    private byte[] flacHeader(boolean invalidPicture) throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(file);
        output.writeBytes("fLaC");
        byte[] info = new byte[34];
        java.nio.ByteBuffer.wrap(info).putLong(10, (96000L << 44) | (1L << 41) | (23L << 36) | (96000L * 180));
        flacBlock(output, 0, info);
        String[] tags = {"TITLE=Title", "ARTIST=Artist", "ALBUM=Album", "DATE=2026", "TRACKNUMBER=2/10",
            "DISCNUMBER=1", "LYRICS=[00:01]Lyrics", "REPLAYGAIN_TRACK_GAIN=-6 dB"};
        ByteArrayOutputStream comments = new ByteArrayOutputStream();
        DataOutputStream text = new DataOutputStream(comments);
        text.writeInt(0); text.writeInt(Integer.reverseBytes(tags.length));
        for (String tag : tags) { byte[] bytes = tag.getBytes("UTF-8"); text.writeInt(Integer.reverseBytes(bytes.length)); text.write(bytes); }
        flacBlock(output, 4, comments.toByteArray());
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        DataOutputStream picture = new DataOutputStream(image);
        picture.writeInt(3); picture.writeInt(10); picture.writeBytes("image/jpeg"); picture.writeInt(0);
        picture.writeInt(2); picture.writeInt(2); picture.writeInt(24); picture.writeInt(0);
        picture.writeInt(invalidPicture ? 100 : 3); picture.write(new byte[]{1, 2, 3});
        flacBlock(output, 0x86, image.toByteArray());
        output.writeShort(0xfff8);
        return file.toByteArray();
    }
    private void flacBlock(DataOutputStream output, int type, byte[] data) throws IOException {
        output.writeByte(type); output.writeByte(data.length >>> 16); output.writeShort(data.length); output.write(data);
    }

    @Test public void largeFlacPicturesAndPaddingDoNotPreventImport() throws Exception {
        byte[] valid = flacHeader(false);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(file);
        output.write(valid, 0, 42);
        flacBlock(output, 6, new byte[12 * 1024 * 1024 + 1]);
        flacBlock(output, 1, new byte[5 * 1024 * 1024]);
        output.write(valid, 42, valid.length - 42);
        JSONObject track = new JSONObject().put("replayGain", new JSONObject());
        assertArrayEquals(new byte[]{1, 2, 3}, TagReader.read(new ByteArrayInputStream(file.toByteArray()), track));
        assertEquals(180, track.getDouble("duration"), 1e-9);
        assertEquals("Title", track.getString("title"));
    }

    @Test public void wav32HeaderProvidesDurationRateAndChannelsWithoutPlatformCodecs() throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(file);
        out.writeBytes("RIFF"); out.writeInt(Integer.reverseBytes(44 + 10 + 768000 - 8)); out.writeBytes("WAVE");
        out.writeBytes("JUNK"); out.writeInt(Integer.reverseBytes(1)); out.writeShort(0);
        out.writeBytes("fmt "); out.writeInt(Integer.reverseBytes(16));
        out.writeShort(Short.reverseBytes((short) 1)); out.writeShort(Short.reverseBytes((short) 2));
        out.writeInt(Integer.reverseBytes(96000)); out.writeInt(Integer.reverseBytes(768000));
        out.writeShort(Short.reverseBytes((short) 8)); out.writeShort(Short.reverseBytes((short) 32));
        out.writeBytes("data"); out.writeInt(Integer.reverseBytes(768000)); out.write(new byte[8]);
        JSONObject track = new JSONObject();
        TagReader.read(new ByteArrayInputStream(file.toByteArray()), track);
        assertEquals(32, track.getInt("bits")); assertEquals(96000, track.getInt("sampleRate"));
        assertEquals(2, track.getInt("channels")); assertEquals(1, track.getDouble("duration"), 1e-9);
        assertEquals(6144000, track.getLong("bitrate"));
        byte[] corrupt = file.toByteArray(); corrupt[12 + 10 + 20] = 0;
        try { TagReader.read(new ByteArrayInputStream(corrupt), new JSONObject()); fail("Invalid block alignment"); }
        catch (IOException expected) {}
    }

    @Test public void cueResolvesRelativePathsAndWindowsSeparators() {
        assertEquals("file:/storage/music/Disc/one.flac", CueParser.resolvePath("file:/storage/music", "Disc\\one.flac"));
        assertEquals("file:/storage/music/one.flac", CueParser.resolvePath("file:/storage/music/Disc", "../one.flac"));
        assertEquals("document:provider:card:Music/one.flac", CueParser.resolvePath("document:provider:card:Music", "./one.flac"));
        assertEquals("", CueParser.resolvePath("music", "/other/one.flac"));
        assertEquals("", CueParser.resolvePath("music", "C:\\one.flac"));
    }

    @Test public void cueUsesFramesAndMultipleFiles() {
        List<CueParser.Entry> entries = CueParser.parse("PERFORMER \"Artist\"\nTITLE \"Album\"\nFILE \"disc one.flac\" WAVE\nTRACK 01 AUDIO\nTITLE \"First\"\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nTITLE \"Second\"\nINDEX 01 01:02:37\nFILE \"disc two.flac\" WAVE\nTRACK 03 AUDIO\nINDEX 01 00:00:00");
        assertEquals(3, entries.size()); assertEquals("disc one.flac", entries.get(1).file);
        assertEquals(62493, entries.get(1).startMs); assertEquals("Album", entries.get(2).album); assertEquals("Artist", entries.get(0).artist);
        assertEquals(0, CueParser.parse("TRACK 01 AUDIO\nINDEX 01 00:00:99").size());
    }

    @Test public void flatDspPreservesSignalAndReplayGainAttenuates() {
        DspEngine engine = new DspEngine(); engine.configure(48000, 2);
        double[] frame = {.125, -.25}; engine.frame(frame);
        assertArrayEquals(new double[]{.125, -.25}, frame, 1e-12);
        engine.set(new DspEngine.Config(new DspEngine.Band[0], .5, false, false, false));
        frame = new double[]{.125, -.25}; engine.frame(frame);
        assertArrayEquals(new double[]{.0625, -.125}, frame, 1e-12);
    }

    @Test public void lowPassRejectsHighToneAndAllFiltersStayFinite() {
        double low = tone("LP", 1000, 100), high = tone("LP", 1000, 12000);
        assertTrue(high < low / 30);
        for (String type : new String[]{"PK", "LS", "HS", "LP", "HP"})
            for (double q : new double[]{.1, 1.4, 18}) assertTrue(Double.isFinite(tone(type, 20000, 440, q)));
    }
    private double tone(String type, double frequency, double input) { return tone(type, frequency, input, .707); }
    private double tone(String type, double frequency, double input, double q) {
        DspEngine engine = new DspEngine(); engine.configure(48000, 1);
        engine.set(new DspEngine.Config(new DspEngine.Band[]{new DspEngine.Band(type, frequency, 6, q)}, 1, false, false, false));
        double sum = 0;
        for (int i = 0; i < 4800; i++) { double[] sample = {.1 * Math.sin(2 * Math.PI * input * i / 48000)}; engine.frame(sample); if (i > 2400) sum += sample[0] * sample[0]; }
        return Math.sqrt(sum / 2399);
    }

    @Test public void sincPreservesLengthAndRejectsAliasing() {
        List<Double> low = resample(1000), high = resample(30000);
        assertTrue(Math.abs(low.size() - 44100) <= 1);
        assertTrue(rms(low) > .06); assertTrue(rms(high) < rms(low) / 20);
    }
    private List<Double> resample(int frequency) {
        SincResampler resampler = new SincResampler(96000, 44100, 1);
        List<Double> result = new ArrayList<>();
        for (int i = 0; i < 96000; i++) resampler.input(new double[]{.1 * Math.sin(2 * Math.PI * frequency * i / 96000)}, values -> result.add(values[0]));
        resampler.finish(values -> result.add(values[0])); return result;
    }
    private double rms(List<Double> values) { return Math.sqrt(values.subList(100, values.size() - 100).stream().mapToDouble(v -> v * v).average().getAsDouble()); }

    @Test public void autoeqRoundTripsFiltersAndRejectsBadValues() throws Exception {
        JSONArray bands = PeqPresets.parse("Preamp: -5 dB\nFilter 1: ON PK Fc 105 Hz Gain -3.5 dB Q 1.41\nFilter 2: OFF HSC Fc 5000 Hz Gain 2.0 dB Q 0.71");
        assertEquals(2, bands.length()); assertEquals("HS", bands.getJSONObject(1).getString("type"));
        assertEquals(bands.toString(), PeqPresets.parse(PeqPresets.export(bands)).toString());
        try { PeqPresets.parse("Filter 1: ON PK Fc 100 Hz Gain 100 dB Q 1.0"); fail(); } catch (IllegalArgumentException expected) {}
    }

    @Test public void pcmReverseKeepsStereoFramesAndHeader() throws Exception {
        File file = File.createTempFile("m3x-audio", ".wav");
        try (RandomAccessFile data = new RandomAccessFile(file, "rw")) {
            DsdDecoder.wavHeader(data, 44100, 2, 16, 20);
            for (int i = 1; i <= 5; i++) { data.writeShort(Short.reverseBytes((short) i)); data.writeShort(Short.reverseBytes((short) -i)); }
            PcmExport.reverse(data, 4); data.seek(0); assertEquals(0x52494646, data.readInt()); data.seek(44);
            for (int i = 5; i >= 1; i--) { assertEquals(i, Short.reverseBytes(data.readShort())); assertEquals(-i, Short.reverseBytes(data.readShort())); }
        } finally { file.delete(); }
    }

    @Test public void dsdFirHasUnityDcAndCorrectBitOrder() {
        float[][] lsb = DsdDecoder.coefficients(256, 2822400, true), msb = DsdDecoder.coefficients(256, 2822400, false);
        double dc = 0;
        for (int i = 0; i < lsb.length; i++) {
            dc += lsb[i][255];
            for (int b = 0; b < 256; b++) assertEquals(lsb[i][b], msb[i][Integer.reverse(b) >>> 24], 1e-6);
        }
        assertEquals(1, dc, 1e-5);
    }
}
