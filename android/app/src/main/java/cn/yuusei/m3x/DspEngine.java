package cn.yuusei.m3x;

import java.util.*;

/** RBJ biquads. State belongs to the audio thread; configuration snapshots are immutable. */
public final class DspEngine {
    public static final class Band {
        public final String type; public final double frequency, gain, q;
        public Band(String type, double frequency, double gain, double q) {
            if (!Arrays.asList("PK", "LS", "HS", "LP", "HP").contains(type) || !Double.isFinite(frequency) || frequency < 10 || frequency > 48000
                || !Double.isFinite(gain) || Math.abs(gain) > 18 || !Double.isFinite(q) || q < .1 || q > 18) throw new IllegalArgumentException("均衡器参数无效");
            this.type = type; this.frequency = frequency; this.gain = gain; this.q = q;
        }
    }
    public static final class Config {
        public final Band[] bands; public final double gain; public final boolean reverb, compressor, hall;
        public Config(Band[] bands, double gain, boolean reverb, boolean compressor, boolean hall) {
            if (bands.length > 32 || !Double.isFinite(gain) || gain < 0 || gain > 32) throw new IllegalArgumentException("音效参数无效");
            this.bands = bands.clone(); this.gain = gain; this.reverb = reverb; this.compressor = compressor; this.hall = hall;
        }
    }
    private volatile Config wanted = new Config(new Band[0], 1, false, false, false);
    private Config active;
    private Biquad[][] filters;
    private double[][][] delays; private int[][] positions;
    private double[] envelopes;
    private int rate = 44100, channels = 2;
    private double headroom = 1;

    public void set(Config config) { wanted = config; }
    public void configure(int sampleRate, int channelCount) {
        rate = sampleRate; channels = channelCount; active = null;
    }
    public void frame(double[] samples) {
        if (active != wanted) reset(wanted);
        Config config = active;
        for (int channel = 0; channel < channels; channel++) {
            double sample = samples[channel] * config.gain * headroom;
            for (Biquad filter : filters[channel]) sample = filter.sample(sample);
            if (config.reverb || config.hall) {
                double wet = 0;
                for (int d = 0; d < 4; d++) {
                    int index = positions[channel][d]; double delayed = delays[channel][d][index];
                    delays[channel][d][index] = sample + delayed * (config.hall ? .82 : .64);
                    positions[channel][d] = (index + 1) % delays[channel][d].length; wet += delayed;
                }
                sample = sample * .83 + wet * (config.hall ? .065 : .045);
            }
            if (config.compressor) {
                double absolute = Math.abs(sample), coefficient = Math.exp(-1d / (rate * (absolute > envelopes[channel] ? .003 : .15)));
                envelopes[channel] = coefficient * envelopes[channel] + (1 - coefficient) * absolute;
                if (envelopes[channel] > .5) sample *= Math.pow(envelopes[channel] / .5, -.75);
            }
            samples[channel] = Double.isFinite(sample) ? Math.max(-1, Math.min(1 - 1d / 32768, sample)) : 0;
        }
    }
    private void reset(Config config) {
        active = config;
        filters = new Biquad[channels][config.bands.length]; delays = new double[channels][4][]; positions = new int[channels][4]; envelopes = new double[channels];
        double positive = 0; for (Band band : config.bands) positive = Math.max(positive, band.gain);
        headroom = Math.pow(10, -positive / 20);
        for (int channel = 0; channel < channels; channel++) {
            for (int i = 0; i < config.bands.length; i++) filters[channel][i] = new Biquad(config.bands[i], rate);
            for (int d = 0; d < 4; d++) delays[channel][d] = new double[Math.max(1, (int) (rate * new double[]{.0297, .0371, .0411, .0437}[d]) + channel * 23)];
        }
    }
    static final class Biquad {
        double b0, b1, b2, a1, a2, x1, x2, y1, y2;
        Biquad(Band p, int rate) {
            double w = 2 * Math.PI * Math.min(p.frequency, rate * .49) / rate, c = Math.cos(w), s = Math.sin(w);
            double a = Math.pow(10, p.gain / 40), alpha = s / (2 * p.q), a0 = 1;
            switch (p.type) {
                case "PK": b0 = 1 + alpha * a; b1 = -2 * c; b2 = 1 - alpha * a; a0 = 1 + alpha / a; a1 = -2 * c; a2 = 1 - alpha / a; break;
                case "LP": b0 = (1 - c) / 2; b1 = 1 - c; b2 = b0; a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha; break;
                case "HP": b0 = (1 + c) / 2; b1 = -(1 + c); b2 = b0; a0 = 1 + alpha; a1 = -2 * c; a2 = 1 - alpha; break;
                case "LS": {
                    double t = 2 * Math.sqrt(a) * alpha;
                    b0 = a * ((a + 1) - (a - 1) * c + t); b1 = 2 * a * ((a - 1) - (a + 1) * c); b2 = a * ((a + 1) - (a - 1) * c - t);
                    a0 = (a + 1) + (a - 1) * c + t; a1 = -2 * ((a - 1) + (a + 1) * c); a2 = (a + 1) + (a - 1) * c - t; break;
                }
                case "HS": {
                    double t = 2 * Math.sqrt(a) * alpha;
                    b0 = a * ((a + 1) + (a - 1) * c + t); b1 = -2 * a * ((a - 1) + (a + 1) * c); b2 = a * ((a + 1) + (a - 1) * c - t);
                    a0 = (a + 1) - (a - 1) * c + t; a1 = 2 * ((a - 1) - (a + 1) * c); a2 = (a + 1) - (a - 1) * c - t; break;
                }
            }
            b0 /= a0; b1 /= a0; b2 /= a0; a1 /= a0; a2 /= a0;
        }
        double sample(double x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x; y2 = y1; y1 = y; return y;
        }
    }
}
