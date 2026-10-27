package cn.yuusei.m3x;

import org.junit.Test;
import org.json.JSONArray;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class AudioLogicTest {
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
