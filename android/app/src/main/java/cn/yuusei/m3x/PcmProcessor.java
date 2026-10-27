package cn.yuusei.m3x;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import java.nio.ByteBuffer;

final class PcmProcessor extends BaseAudioProcessor {
    final DspEngine engine = new DspEngine();
    private double[] frame;
    private final int requestedRate;
    private SincResampler resampler;
    PcmProcessor(int requestedRate) { this.requestedRate = requestedRate; }
    @Override protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat format) throws UnhandledAudioFormatException {
        if (format.encoding != C.ENCODING_PCM_16BIT) throw new UnhandledAudioFormatException(format);
        engine.configure(format.sampleRate, format.channelCount); frame = new double[format.channelCount];
        resampler = requestedRate == 0 || requestedRate == format.sampleRate ? null : new SincResampler(format.sampleRate, requestedRate, format.channelCount);
        return resampler == null ? format : new AudioProcessor.AudioFormat(requestedRate, format.channelCount, format.encoding);
    }
    @Override public void queueInput(ByteBuffer input) {
        int frames = input.remaining() / (frame.length * 2);
        ByteBuffer output = replaceOutputBuffer((int) Math.ceil((frames + 34) * Math.max(1d, outputAudioFormat.sampleRate / (double) inputAudioFormat.sampleRate)) * frame.length * 2);
        while (input.remaining() >= frame.length * 2) {
            for (int c = 0; c < frame.length; c++) frame[c] = input.getShort() / 32768d;
            engine.frame(frame);
            if (resampler == null) write(output, frame); else resampler.input(frame, values -> write(output, values));
        }
        output.flip();
    }
    private void write(ByteBuffer output, double[] values) { for (double sample : values) output.putShort((short) Math.round(Math.max(-1, Math.min(.999969, sample)) * 32768)); }
    @Override protected void onQueueEndOfStream() {
        if (resampler != null) {
            ByteBuffer output = replaceOutputBuffer((int) Math.ceil(34 * Math.max(1d, outputAudioFormat.sampleRate / (double) inputAudioFormat.sampleRate)) * frame.length * 2);
            resampler.finish(values -> write(output, values)); output.flip();
        }
    }
    @Override protected void onFlush() {
        if (inputAudioFormat.sampleRate > 0) {
            engine.configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount);
            resampler = requestedRate == 0 || requestedRate == inputAudioFormat.sampleRate ? null : new SincResampler(inputAudioFormat.sampleRate, requestedRate, inputAudioFormat.channelCount);
        }
    }
}
