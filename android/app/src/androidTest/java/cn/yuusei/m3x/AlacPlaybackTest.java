package cn.yuusei.m3x;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.decoder.ffmpeg.FfmpegLibrary;
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

public final class AlacPlaybackTest extends InstrumentationTestCase {
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
            }
            peak.set(max);
            return super.handleBuffer(buffer, timeUs, count);
        }
    }

    public void testAlacAndAacPlayback() throws Exception {
        getInstrumentation().runOnMainSync(() -> {});
        assertNotNull("Music library", PlayerApp.instance.library);
        assertTrue("FFmpeg native library", FfmpegLibrary.isAvailable());
        assertTrue("ALAC decoder", FfmpegLibrary.supportsFormat(MimeTypes.AUDIO_ALAC));
        fixture("fixture-alac-16.m4a", false, true);
        fixture("fixture-alac-24.m4a", false, true);
        fixture("fixture-alac-24.m4a", true, true);
        fixture("fixture-aac.m4a", false, false);
        String uri = ((android.test.InstrumentationTestRunner) getInstrumentation()).getArguments().getString("probeUri");
        if (uri != null) {
            probe(Uri.parse(uri), "device-alac.m4a");
            play(Uri.parse(uri), false, true, "device ALAC", 0);
        }
    }

    private void fixture(String name, boolean dsp, boolean alac) throws Exception {
        File file = File.createTempFile("m3x-decoder-test-", ".m4a", getInstrumentation().getTargetContext().getCacheDir());
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets().open(name);
                 FileOutputStream output = new FileOutputStream(file)) {
                byte[] bytes = new byte[8192]; int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            }
            probe(Uri.fromFile(file), name);
            play(Uri.fromFile(file), dsp, alac, name, 44100);
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
        assertTrue(name + " duration", track.optDouble("duration") > 0);
        assertTrue(name + " sample rate", track.optInt("sampleRate") > 0);
        assertTrue(name + " channels", track.optInt("channels") > 0);
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
            player[0] = new ExoPlayer.Builder(context, factory).build();
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
            assertTrue(label + " has decoded audio", signal[0].peak.get() > 100);
            if (dsp || expectedRate > 0) assertEquals(label + " output rate", dsp ? 48000 : expectedRate, outputRate.get());
            else assertTrue(label + " output rate", outputRate.get() > 0);
            if (alac) assertTrue(label + " software decoder: " + decoder.get(), decoder.get().startsWith("ffmpeg") && decoder.get().endsWith("-alac"));
            else if (android.os.Build.MODEL.contains("M3X")) assertFalse(label + " should retain platform decoder", decoder.get().startsWith("ffmpeg"));
            getInstrumentation().runOnMainSync(() -> { player[0].seekTo(1500); signal[0].peak.set(0); });
            waitForPosition(player[0], error, 1800);
            assertTrue(label + " has decoded audio after seek", signal[0].peak.get() > 100);
            android.util.Log.i("M3X.AlacTest", label + " dsp=" + dsp + " decoder=" + decoder.get()
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
}
