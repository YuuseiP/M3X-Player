package cn.yuusei.m3x;

/** Streaming 32-tap windowed sinc with a 1024-phase table and an explicit end drain. */
public final class SincResampler {
    private static final int TAPS = 32, PHASES = 1024;
    private final int channels;
    private final double step;
    private final double[][] history, coefficients = new double[PHASES][TAPS];
    private long received, realFrames;
    private double next;
    public SincResampler(int inputRate, int outputRate, int channels) {
        if (inputRate < 8000 || outputRate < 8000 || channels < 1 || channels > 8) throw new IllegalArgumentException("重采样参数无效");
        this.channels = channels; step = inputRate / (double) outputRate; history = new double[channels][64];
        double cutoff = .45 * Math.min(1, outputRate / (double) inputRate);
        for (int phase = 0; phase < PHASES; phase++) {
            double sum = 0;
            for (int tap = 0; tap < TAPS; tap++) {
                double distance = tap - 15 - phase / (double) PHASES;
                double value = Math.abs(distance) < 1e-12 ? 2 * cutoff : Math.sin(2 * Math.PI * cutoff * distance) / (Math.PI * distance);
                value *= .42 - .5 * Math.cos(2 * Math.PI * tap / (TAPS - 1)) + .08 * Math.cos(4 * Math.PI * tap / (TAPS - 1));
                coefficients[phase][tap] = value; sum += value;
            }
            for (int tap = 0; tap < TAPS; tap++) coefficients[phase][tap] /= sum;
        }
    }
    public interface Output { void frame(double[] samples); }
    public void input(double[] samples, Output output) { feed(samples, output, false); realFrames++; }
    private void feed(double[] samples, Output output, boolean draining) {
        long at = received++;
        for (int channel = 0; channel < channels; channel++) history[channel][(int) (at % 64)] = samples[channel];
        double[] result = new double[channels];
        while (Math.floor(next) + 16 <= at && (!draining || next < realFrames)) {
            long base = (long) Math.floor(next); int phase = Math.min(PHASES - 1, (int) ((next - base) * PHASES));
            for (int channel = 0; channel < channels; channel++) {
                double value = 0;
                for (int tap = 0; tap < TAPS; tap++) {
                    long source = base - 15 + tap;
                    if (source >= 0 && at - source < 64) value += history[channel][(int) (source % 64)] * coefficients[phase][tap];
                }
                result[channel] = value;
            }
            output.frame(result); next += step;
        }
    }
    public void finish(Output output) { double[] zero = new double[channels]; for (int i = 0; i < 32; i++) feed(zero, output, true); }
}
