package cn.yuusei.m3x;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public final class AiffPlaybackTest extends InstrumentationTestCase {
    private static final class SignalSink extends ForwardingAudioSink {
        volatile int encoding;
        final AtomicInteger peak = new AtomicInteger();

        SignalSink(AudioSink sink) { super(sink); }

        @Override public void configure(Format format, int bufferSize, int[] channels)
                throws AudioSink.ConfigurationException {
            encoding = format.pcmEncoding;
            super.configure(format, bufferSize, channels);
        }

        @Override public boolean handleBuffer(ByteBuffer buffer, long timeUs, int count)
                throws AudioSink.InitializationException, AudioSink.WriteException {
            ByteBuffer samples = buffer.duplicate().order(ByteOrder.nativeOrder());
            int max = peak.get();
            if (encoding == C.ENCODING_PCM_16BIT) {
                while (samples.remaining() >= 2) max = Math.max(max, Math.abs((int) samples.getShort()));
            } else if (encoding == C.ENCODING_PCM_FLOAT) {
                while (samples.remaining() >= 4) max = Math.max(max, (int) (Math.abs(samples.getFloat()) * 32768));
            } else if (encoding == C.ENCODING_PCM_8BIT) {
                while (samples.hasRemaining()) max = Math.max(max, Math.abs((samples.get() & 255) - 128) * 256);
            } else if (encoding == C.ENCODING_PCM_24BIT) {
                while (samples.remaining() >= 3) {
                    int value = (samples.get() & 255) | ((samples.get() & 255) << 8) | (samples.get() << 16);
                    max = Math.max(max, Math.abs(value >> 8));
                }
            } else if (encoding == C.ENCODING_PCM_32BIT) {
                while (samples.remaining() >= 4) max = Math.max(max, Math.abs(samples.getInt() >> 16));
            }
            peak.set(max);
            return super.handleBuffer(buffer, timeUs, count);
        }
    }

    public void testAiffPlaybackAndSeek() throws Exception {
        for (String name : new String[]{"fixture-aiff-8.aiff", "fixture-aiff-16.aiff", "fixture-aiff-24.aiff", "fixture-aiff-32.aiff", "fixture-sowt.aifc"}) {
            fixture(name, false, false);
        }
        fixture("fixture-aiff-24.aiff", true, false);
        String uri = ((android.test.InstrumentationTestRunner) getInstrumentation()).getArguments().getString("probeUri");
        if (uri != null) {
            probe(Uri.parse(uri), "device.aiff");
            play(Uri.parse(uri), false, false, "device AIFF", 0);
        }
    }

    public void testExistingDeviceAiff() throws Exception {
        PlayerApp.instance.awaitLibrary();
        int tested = 0;
        for (JSONObject item : PlayerApp.instance.library.all()) {
            String filename = item.optString("filename").toLowerCase(java.util.Locale.ROOT);
            if (!filename.endsWith(".aiff") && !filename.endsWith(".aif")) continue;
            Uri uri = Uri.parse(item.getString("uri"));
            probe(uri, filename);
            play(uri, false, false, "existing AIFF " + item.optString("id"), 0);
            if (++tested == 3) break;
        }
        assertTrue("At least one existing AIFF song in the device library", tested > 0);
    }

    private void fixture(String name, boolean dsp, boolean alac) throws Exception {
        File file = File.createTempFile("m3x-decoder-test-", ".aiff", getInstrumentation().getTargetContext().getExternalFilesDir(null));
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open(name);
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] bytes = new byte[8192]; int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            }
            probe(Uri.fromFile(file), name);
            play(Uri.fromFile(file), dsp, alac, name, 44100);
            if (name.equals("fixture-aiff-16.aiff")) {
                Uri content = androidx.core.content.FileProvider.getUriForFile(getInstrumentation().getTargetContext(), "cn.yuusei.m3x.files", file);
                probe(content, name);
                play(content, false, false, "content URI AIFF", 44100);
            }
        } finally {
            file.delete();
        }
    }

    private void probe(Uri uri, String name) throws Exception {
        Class<?> source = Class.forName("cn.yuusei.m3x.MusicLibrary$Source");
        java.lang.reflect.Constructor<?> constructor = source.getDeclaredConstructor(Uri.class, String.class, String.class, long.class, long.class);
        constructor.setAccessible(true);
        java.lang.reflect.Method method = MusicLibrary.class.getDeclaredMethod("probe", source);
        method.setAccessible(true);
        JSONObject track = (JSONObject) method.invoke(PlayerApp.instance.library, constructor.newInstance(uri, name, "test", 0L, 0L));
        android.util.Log.i("M3X.AiffTest", name + " rate=" + track.optInt("sampleRate") + " bits=" + track.optInt("bits") + " duration=" + track.optDouble("duration"));
        assertTrue(name + " duration", track.optDouble("duration") > 0);
        assertTrue(name + " sample rate", track.optInt("sampleRate") > 0);
        assertTrue(name + " channels", track.optInt("channels") > 0);
        if (name.startsWith("fixture-")) {
            assertEquals(name + " duration", 3.0, track.optDouble("duration"), .001);
            assertEquals(name + " sample rate", 44100, track.optInt("sampleRate"));
            int bits = name.contains("-8.") ? 8 : name.contains("-24.") ? 24 : name.contains("-32.") ? 32 : 16;
            assertEquals(name + " bits", bits, track.optInt("bits"));
        }
    }

    private void play(Uri uri, boolean dsp, boolean alac, String label, int expectedRate) throws Exception {
        Context context = getInstrumentation().getTargetContext();
        ExoPlayer[] player = new ExoPlayer[1];
        SignalSink[] signal = new SignalSink[1];
        AtomicReference<String> decoder = new AtomicReference<>("");
        AtomicReference<PlaybackException> error = new AtomicReference<>();
        AtomicInteger outputRate = new AtomicInteger();
        getInstrumentation().runOnMainSync(() -> {
            DefaultRenderersFactory factory = new DefaultRenderersFactory(context) {
                @Override protected AudioSink buildAudioSink(Context c, boolean floatOutput, boolean playbackParams) {
                    DefaultAudioSink.Builder sink = new DefaultAudioSink.Builder(c).setEnableFloatOutput(!dsp);
                    if (dsp) {
                        PcmProcessor processor = new PcmProcessor(48000);
                        processor.engine.set(new DspEngine.Config(new DspEngine.Band[]{new DspEngine.Band("PK", 1000, 2, 1)}, 1, false, false, false));
                        sink.setAudioProcessors(new AudioProcessor[]{processor});
                    }
                    signal[0] = new SignalSink(sink.build());
                    return signal[0];
                }
            }.setEnableDecoderFallback(true).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
            player[0] = new ExoPlayer.Builder(context, factory).setMediaSourceFactory(new androidx.media3.exoplayer.source.DefaultMediaSourceFactory(new androidx.media3.datasource.DefaultDataSource.Factory(context), AiffExtractor.factory())).build();
            player[0].addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException exception) { error.set(exception); }
            });
            player[0].addAnalyticsListener(new AnalyticsListener() {
                @Override public void onAudioDecoderInitialized(EventTime time, String name, long timestampMs, long durationMs) { decoder.set(name); }
                @Override public void onAudioTrackInitialized(EventTime time, AudioSink.AudioTrackConfig config) { outputRate.set(config.sampleRate); }
            });
            player[0].setVolume(0);
            player[0].setMediaItem(MediaItem.fromUri(uri));
            player[0].prepare();
            player[0].play();
        });
        try {
            waitForPosition(player[0], error, 400);
            waitForSignal(signal[0], error);
            assertTrue(label + " has decoded audio", signal[0].peak.get() > 100);
            if (dsp || expectedRate > 0) assertEquals(label + " output rate", dsp ? 48000 : expectedRate, outputRate.get());
            else assertTrue(label + " output rate", outputRate.get() > 0);
            if (alac) assertTrue(label + " software decoder: " + decoder.get(), decoder.get().startsWith("ffmpeg") && decoder.get().endsWith("-alac"));
            else if (android.os.Build.MODEL.contains("M3X")) assertFalse(label + " should retain platform decoder", decoder.get().startsWith("ffmpeg"));
            long[] seekMs = {1500};
            getInstrumentation().runOnMainSync(() -> {
                if (label.startsWith("existing AIFF")) seekMs[0] = Math.min(30000, player[0].getDuration() / 2);
                signal[0].peak.set(0); player[0].seekTo(seekMs[0]);
            });
            waitForPosition(player[0], error, seekMs[0] + 300);
            waitForSignal(signal[0], error);
            assertTrue(label + " has decoded audio after seek", signal[0].peak.get() > 100);
            getInstrumentation().runOnMainSync(player[0]::pause);
            getInstrumentation().runOnMainSync(() -> assertFalse(player[0].getPlayWhenReady()));
            getInstrumentation().runOnMainSync(player[0]::play);
            if (label.startsWith("fixture-") || label.startsWith("content URI")) {
                long deadline = SystemClock.elapsedRealtime() + 10000;
                boolean[] ended = {false};
                while (SystemClock.elapsedRealtime() < deadline && !ended[0]) {
                    getInstrumentation().runOnMainSync(() -> ended[0] = player[0].getPlaybackState() == Player.STATE_ENDED);
                    if (error.get() != null) fail("Playback error at end: " + error.get());
                    Thread.sleep(50);
                }
                assertTrue(label + " reaches end", ended[0]);
            }
            android.util.Log.i("M3X.AiffTest", label + " dsp=" + dsp + " decoder=" + decoder.get()
                + " pcm=" + signal[0].encoding + " peak=" + signal[0].peak.get() + " outputRate=" + outputRate.get());
        } finally {
            getInstrumentation().runOnMainSync(player[0]::release);
        }
    }

    private void waitForPosition(ExoPlayer player, AtomicReference<PlaybackException> error, long position) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 15000;
        boolean[] reached = {false};
        while (SystemClock.elapsedRealtime() < deadline) {
            getInstrumentation().runOnMainSync(() -> reached[0] = player.isPlaying() && player.getCurrentPosition() > position);
            if (error.get() != null) fail("Playback error: " + error.get());
            if (reached[0]) return;
            Thread.sleep(50);
        }
        fail("Playback did not reach " + position + " ms");
    }

    private void waitForSignal(SignalSink sink, AtomicReference<PlaybackException> error) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (sink.peak.get() <= 100 && SystemClock.elapsedRealtime() < deadline) {
            if (error.get() != null) fail("Playback error: " + error.get());
            Thread.sleep(50);
        }
    }
}
