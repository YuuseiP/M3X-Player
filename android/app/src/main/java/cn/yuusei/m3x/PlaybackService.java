package cn.yuusei.m3x;

import android.content.*;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import androidx.media3.common.*;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.audio.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.ResolvingDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.session.*;
import org.json.*;
import java.io.File;
import java.util.*;
import static cn.yuusei.m3x.PlayerApp.json;

@UnstableApi
public final class PlaybackService extends MediaSessionService {
    private PlayerApp app;
    public ExoPlayer player;
    private MediaSession session;
    private PcmProcessor processor;
    private JSONObject settings;
    private long sleepDeadline;
    private boolean sleepFinish, sleepExpired;
    private boolean preparingFile;
    private String preparingId = "";
    private boolean conversionPlay;
    private long fileRequest;
    private String error = "", conversion = "";
    private int outputRate, outputEncoding, audioTracks;
    private long lastStats, listenedMs, lastSave;
    private boolean counted;
    private boolean destroyed;
    private JSONObject stats;
    private float manualGain = 1, rampFrom = 1, rampTarget = 1;
    private long rampStart;
    private boolean pauseAfterRamp, fadeTrackStart;
    private String previousAlbum = "";
    private final Runnable fadeTick = new Runnable() {
        @Override public void run() {
            if (player == null || destroyed) return;
            if (rampStart > 0) {
                float fraction = Math.min(1, (SystemClock.elapsedRealtime() - rampStart) / 300f);
                manualGain = rampFrom + (rampTarget - rampFrom) * fraction;
                if (fraction == 1) {
                    rampStart = 0;
                    if (pauseAfterRamp) { player.pause(); pauseAfterRamp = false; manualGain = 1; }
                }
            }
            float transitionGain = 1;
            if (settings.optBoolean("albumTransitions") && player.isPlaying() && !settings.optBoolean("reverse")) {
                if (fadeTrackStart) transitionGain = Math.min(1, player.getCurrentPosition() / 350f);
                int next = player.getNextMediaItemIndex();
                JSONObject current = currentTrack(), following = next >= 0 ? app.library.track(player.getMediaItemAt(next).mediaId) : null;
                if (current != null && following != null && !sameAlbum(current.optString("album"), following.optString("album")) && player.getDuration() > 700)
                    transitionGain = Math.min(transitionGain, Math.max(0, (player.getDuration() - player.getCurrentPosition()) / 350f));
            }
            float level = manualGain * transitionGain;
            if (Math.abs(player.getVolume() - level) > .001) player.setVolume(level);
            app.main.postDelayed(this, rampStart > 0 || (settings.optBoolean("albumTransitions") && player.isPlaying()) ? 33 : 1000);
        }
    };
    private static boolean sameAlbum(String a, String b) { return !a.isEmpty() && !a.startsWith("未知") && a.equals(b); }
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            long now = SystemClock.elapsedRealtime();
            if (player != null) {
                if (player.isPlaying()) {
                    long elapsed = Math.min(1500, now - lastStats);
                    statsTime(elapsed); listenedMs += elapsed;
                    JSONObject track = currentTrack();
                    if (track != null && !counted && listenedMs >= (track.optDouble("duration", 0) > 0 ? Math.min(30000, track.optDouble("duration") * 500) : 30000)) {
                        JSONObject counts = stats.optJSONObject("counts"); if (counts == null) counts = new JSONObject();
                        try { counts.put(track.optString("id"), counts.optInt(track.optString("id")) + 1); stats.put("counts", counts); } catch (Exception ignored) {}
                        counted = true;
                    }
                    if (sleepDeadline > 0 && now >= sleepDeadline) {
                        if (sleepFinish) { sleepExpired = true; player.setPauseAtEndOfMediaItems(true); } else finishSleep();
                    }
                } else if (sleepDeadline > 0 && now >= sleepDeadline && !sleepFinish) finishSleep();
                if (now - lastSave > 5000) { savePosition(); lastSave = now; app.save("tools", stats); }
            }
            lastStats = now; app.main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        app = PlayerApp.instance; app.playback = this; settings = app.settings("audio"); stats = app.settings("tools");
        buildPlayer();
        session = new MediaSession.Builder(this, sessionPlayer())
            .setSessionActivity(android.app.PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            .build();
        restore();
        lastStats = SystemClock.elapsedRealtime(); app.main.post(tick); app.main.post(fadeTick);
    }

    private Player sessionPlayer() {
        return new ForwardingPlayer(player) {
            @Override public void play() { PlaybackService.this.play(); }
            @Override public void pause() { PlaybackService.this.pause(); }
            @Override public void setPlayWhenReady(boolean ready) { if (ready) play(); else pause(); }
        };
    }

    private void buildPlayer() {
        final boolean dsp = settings.optBoolean("eq") || !settings.optString("replaygain", "off").equals("off") || settings.optBoolean("reverb") || settings.optBoolean("compressor") || settings.optBoolean("hall") || settings.optInt("sampleRate") > 0;
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override protected AudioSink buildAudioSink(Context context, boolean floatOutput, boolean playbackParams) {
                DefaultAudioSink.Builder sink = new DefaultAudioSink.Builder(context).setEnableFloatOutput(!dsp);
                if (dsp) { processor = new PcmProcessor(settings.optInt("sampleRate")); sink.setAudioProcessors(new androidx.media3.common.audio.AudioProcessor[]{processor}); }
                else processor = null;
                return sink.build();
            }
        }.setEnableDecoderFallback(true);
        ResolvingDataSource.Factory sources = new ResolvingDataSource.Factory(new DefaultDataSource.Factory(this, new OkHttpDataSource.Factory(NativeNetwork.HTTP)),
            spec -> spec.withAdditionalHeaders(NativeNetwork.headers(spec.uri.toString())));
        player = new ExoPlayer.Builder(this, renderers).setMediaSourceFactory(new DefaultMediaSourceFactory(sources)).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true);
        player.setHandleAudioBecomingNoisy(true);
        player.setWakeMode(C.WAKE_MODE_LOCAL);
        player.addListener(new Player.Listener() {
            @Override public void onIsPlayingChanged(boolean playing) {
                lastStats = SystemClock.elapsedRealtime();
                if (!playing) savePosition();
                app.main.removeCallbacks(fadeTick); app.main.post(fadeTick);
            }
            @Override public void onPlayWhenReadyChanged(boolean ready, int reason) {
                if (!ready && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepExpired) finishSleep();
                else if (!ready && !preparingFile && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) cancelWholeSongTimer();
            }
            @Override public void onMediaItemTransition(MediaItem item, int reason) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) cancelWholeSongTimer();
                listenedMs = 0; counted = false;
                if (sleepExpired) { finishSleep(); return; }
                error = ""; applyDsp(); savePosition();
                JSONObject track = currentTrack();
                String album = track == null ? "" : track.optString("album");
                fadeTrackStart = !sameAlbum(previousAlbum, album); previousAlbum = album;
                if (track != null) {
                    try {
                        JSONObject ui = app.settings("ui"); JSONArray history = ui.optJSONArray("history"); JSONArray updated = new JSONArray().put(track.getString("id"));
                        if (history != null) for (int i = 0; i < history.length() && updated.length() < 20; i++) if (!history.getString(i).equals(track.getString("id"))) updated.put(history.getString(i));
                        ui.put("history", updated); app.save("ui", ui);
                    } catch (Exception ignored) {}
                }
                prepareDsd();
            }
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && sleepDeadline > 0 && SystemClock.elapsedRealtime() >= sleepDeadline) finishSleep();
            }
            @Override public void onPlayerError(PlaybackException exception) {
                if (preparingFile) return;
                error = "无法播放：" + exception.getErrorCodeName(); player.pause(); savePosition();
            }
            @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
                lastStats = SystemClock.elapsedRealtime();
            }
        });
        final ExoPlayer engine = player;
        outputRate = 0; outputEncoding = 0; audioTracks = 0;
        player.addAnalyticsListener(new AnalyticsListener() {
            @Override public void onAudioTrackInitialized(EventTime time, AudioSink.AudioTrackConfig config) {
                if (player != engine) return;
                audioTracks++; outputRate = config.sampleRate; outputEncoding = config.encoding;
            }
            @Override public void onAudioTrackReleased(EventTime time, AudioSink.AudioTrackConfig config) {
                if (player != engine) return;
                audioTracks = Math.max(0, audioTracks - 1);
                if (audioTracks == 0) { outputRate = 0; outputEncoding = 0; }
            }
        });
        applyRate();
    }

    private void restore() {
        JSONObject ui = app.settings("ui");
        syncQueue(ui);
        String id = ui.optString("trackId");
        int index = indexOf(id);
        if (index >= 0) player.seekTo(index, ui.optBoolean("rememberPosition", true) ? (long) (ui.optDouble("position") * 1000) : 0);
        player.setPlayWhenReady(false);
    }

    private int indexOf(String id) {
        for (int i = 0; i < player.getMediaItemCount(); i++) if (player.getMediaItemAt(i).mediaId.equals(id)) return i;
        return -1;
    }

    private MediaItem media(JSONObject track) {
        MediaItem.Builder builder = new MediaItem.Builder().setMediaId(track.optString("id")).setUri(track.optString("uri"))
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(track.optString("title")).setArtist(track.optString("artist")).setAlbumTitle(track.optString("album"))
                .setArtworkUri(track.optString("cover").startsWith("native-covers/") ? Uri.fromFile(new File(app.library.artwork, track.optString("cover").substring("native-covers/".length()))) : null).build());
        File reversed = new File(getCacheDir(), "decoded/" + track.optString("id") + "-reverse.wav");
        boolean reverse = settings.optBoolean("reverse") && reversed.isFile();
        if (track.has("cueStart") && !reverse) builder.setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionMs(track.optLong("cueStart")).setEndPositionMs(track.optLong("cueEnd")).build());
        if (track.optString("codec").equals("DSD")) {
            File cached = new File(getCacheDir(), "decoded/" + track.optString("id") + "-dsd.wav");
            if (cached.isFile()) builder.setUri(Uri.fromFile(cached));
        }
        if (reverse) builder.setUri(Uri.fromFile(reversed));
        return builder.build();
    }

    public void syncQueue(JSONObject ui) {
        if (player == null) return;
        JSONArray queue = ui.optJSONArray("queue"), hidden = ui.optJSONArray("hidden");
        Set<String> invisible = new HashSet<>(); if (hidden != null) for (int i = 0; i < hidden.length(); i++) invisible.add(hidden.optString(i));
        List<MediaItem> items = new ArrayList<>();
        boolean matched = false;
        if (queue != null) for (int i = 0; i < queue.length(); i++) {
            JSONObject track = app.library.track(queue.optString(i));
            if (track != null) { matched = true; if (!invisible.contains(track.optString("id"))) items.add(media(track)); }
        }
        if (!matched && !ui.optBoolean("queueCleared")) for (JSONObject track : app.library.all()) if (!invisible.contains(track.optString("id"))) items.add(media(track));
        boolean same = items.size() == player.getMediaItemCount();
        for (int i = 0; same && i < items.size(); i++) same = items.get(i).mediaId.equals(player.getMediaItemAt(i).mediaId);
        if (!same) {
            MediaItem current = player.getCurrentMediaItem(); long position = player.getCurrentPosition(); boolean playing = player.getPlayWhenReady();
            int index = 0; if (current != null) for (int i = 0; i < items.size(); i++) if (items.get(i).mediaId.equals(current.mediaId)) index = i;
            player.setMediaItems(items, index, current == null || items.isEmpty() || !items.get(index).mediaId.equals(current.mediaId) ? 0 : position);
            player.setPlayWhenReady(playing && !items.isEmpty());
        }
        int mode = ui.optInt("mode");
        player.setShuffleModeEnabled(mode == 1);
        player.setRepeatMode(mode == 2 ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_ALL);
    }

    public JSONObject currentTrack() {
        return player == null || player.getCurrentMediaItem() == null ? null : app.library.track(player.getCurrentMediaItem().mediaId);
    }

    public void load(String id, boolean play) {
        cancelWholeSongTimer();
        conversionPlay = play;
        int index = indexOf(id);
        if (index < 0) {
            JSONObject track = app.library.track(id);
            if (track == null) return;
            player.addMediaItem(media(track)); index = player.getMediaItemCount() - 1;
        }
        player.seekTo(index, 0); error = "";
        if (!prepareDsd()) { player.prepare(); player.setPlayWhenReady(play); }
    }

    private boolean prepareDsd() {
        JSONObject track = currentTrack();
        final boolean reverse = settings.optBoolean("reverse");
        if (track == null || (!track.optString("codec").equals("DSD") && !reverse)) {
            if (preparingFile) { fileRequest++; preparingFile = false; preparingId = ""; conversion = ""; }
            return false;
        }
        File target = new File(getCacheDir(), "decoded/" + track.optString("id") + (reverse ? "-reverse.wav" : "-dsd.wav"));
        if (target.isFile()) return false;
        if (preparingFile && preparingId.equals(track.optString("id"))) return true;
        final String id = track.optString("id"); final long token = ++fileRequest;
        preparingFile = true; preparingId = id; conversion = reverse ? "正在生成 PCM16 倒放缓存" : "正在将 DSD 转为 24bit / 88.2kHz PCM";
        conversionPlay = conversionPlay || player.getPlayWhenReady(); player.pause();
        app.jobs.execute(() -> {
            try {
                Uri input = Uri.parse(track.optString("uri"));
                if (track.optString("codec").equals("DSD")) input = Uri.fromFile(DsdDecoder.convert(app, input, id));
                if (reverse) PcmExport.decode(app, input, track.optLong("cueStart"), track.has("cueEnd") ? track.optLong("cueEnd") : 0, true, id);
                app.main.post(() -> {
                    if (token != fileRequest) return;
                    preparingFile = false; conversion = "";
                    if (currentTrack() != null && currentTrack().optString("id").equals(id)) {
                        int index = player.getCurrentMediaItemIndex(); player.replaceMediaItem(index, media(track)); player.prepare(); player.setPlayWhenReady(conversionPlay);
                    }
                });
            } catch (Exception exception) { app.main.post(() -> { if (token == fileRequest) { preparingFile = false; conversion = ""; error = exception.getMessage(); } }); }
        });
        return true;
    }

    public void play() {
        conversionPlay = true;
        if (!player.getPlayWhenReady() && settings.optBoolean("fades")) { manualGain = 0; ramp(1, false); }
        else if (pauseAfterRamp) ramp(1, false);
        if (!prepareDsd()) { if (player.getPlaybackState() == Player.STATE_IDLE) player.prepare(); player.play(); }
    }
    public void pause() {
        conversionPlay = false; cancelWholeSongTimer();
        if (player.isPlaying() && settings.optBoolean("fades")) ramp(0, true);
        else { rampStart = 0; manualGain = 1; pauseAfterRamp = false; player.pause(); }
    }
    private void ramp(float target, boolean pause) {
        rampFrom = manualGain; rampTarget = target; rampStart = SystemClock.elapsedRealtime(); pauseAfterRamp = pause;
        app.main.removeCallbacks(fadeTick); app.main.post(fadeTick);
    }
    public void next(boolean previous) {
        cancelWholeSongTimer();
        if (previous) player.seekToPreviousMediaItem(); else player.seekToNextMediaItem();
        play();
    }
    private void cancelWholeSongTimer() {
        if (sleepFinish && sleepDeadline > 0) {
            sleepDeadline = 0; sleepExpired = false; player.setPauseAtEndOfMediaItems(false);
            android.widget.Toast.makeText(this, "已取消整曲睡眠定时", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
    private void finishSleep() { sleepDeadline = 0; sleepExpired = false; sleepFinish = false; player.setPauseAtEndOfMediaItems(false); player.pause(); }
    public void sleep(int minutes, boolean finish) {
        if (minutes < 0 || minutes > 180) throw new IllegalArgumentException("定时范围为 0～180 分钟");
        sleepDeadline = minutes == 0 ? 0 : SystemClock.elapsedRealtime() + minutes * 60000L; sleepFinish = finish; sleepExpired = false;
        player.setPauseAtEndOfMediaItems(false);
    }

    public void configure(JSONObject update) throws Exception { configure(update, false); }
    private void configure(JSONObject update, boolean replace) throws Exception {
        JSONObject old = settings; JSONObject next = replace ? new JSONObject() : new JSONObject(settings.toString());
        Iterator<String> keys = update.keys(); while (keys.hasNext()) { String key = keys.next(); next.put(key, update.get(key)); }
        double speed = next.optDouble("rate", 1);
        if (speed < .25 || speed > 4 || !Double.isFinite(speed)) throw new IllegalArgumentException("速度范围为 0.25～4 倍");
        if (!Arrays.asList(0, 44100, 48000, 88200, 96000, 176400, 192000).contains(next.optInt("sampleRate"))) throw new IllegalArgumentException("不支持的提交采样率");
        settings = next;
        DspEngine.Config config;
        try { config = dspConfig(); } catch (Exception exception) { settings = old; throw exception; }
        boolean rebuild = old.optBoolean("eq") != next.optBoolean("eq") || !old.optString("replaygain", "off").equals(next.optString("replaygain", "off"))
            || old.optBoolean("reverb") != next.optBoolean("reverb") || old.optBoolean("compressor") != next.optBoolean("compressor") || old.optBoolean("hall") != next.optBoolean("hall") || old.optInt("sampleRate") != next.optInt("sampleRate") || old.optBoolean("reverse") != next.optBoolean("reverse");
        if (rebuild) {
            fileRequest++; preparingFile = false; preparingId = ""; conversion = "";
            List<MediaItem> items = new ArrayList<>(); for (int i = 0; i < player.getMediaItemCount(); i++) {
                JSONObject track = app.library.track(player.getMediaItemAt(i).mediaId); if (track != null) items.add(media(track));
            }
            int index = Math.max(0, player.getCurrentMediaItemIndex()); long position = player.getCurrentPosition(); boolean playing = player.getPlayWhenReady();
            if (old.optBoolean("reverse") != next.optBoolean("reverse")) position = 0;
            player.release(); buildPlayer(); session.setPlayer(sessionPlayer()); player.setMediaItems(items, index, position); syncQueue(app.settings("ui"));
            conversionPlay = playing;
            if (!prepareDsd()) { if (!items.isEmpty()) player.prepare(); player.setPlayWhenReady(playing); }
        }
        if (processor != null) processor.engine.set(config);
        applyRate(); app.save("audio", settings);
    }

    private void applyRate() {
        float speed = (float) settings.optDouble("rate", 1);
        player.setPlaybackParameters(new PlaybackParameters(speed, settings.optBoolean("preservePitch", true) ? 1 : speed));
    }
    public void restoreBackup(JSONObject backup) throws Exception {
        configure(backup.getJSONObject("audio"), true);
        pause();
        app.save("ui", backup.getJSONObject("ui"));
        stats = backup.getJSONObject("tools"); app.save("tools", stats);
        syncQueue(app.settings("ui"));
    }
    private DspEngine.Config dspConfig() throws Exception {
        List<DspEngine.Band> bands = new ArrayList<>();
        JSONArray values = settings.optJSONArray("peq");
        if (settings.optBoolean("eq") && values != null) {
            if (values.length() > 32) throw new IllegalArgumentException("最多 32 个滤波器");
            for (int i = 0; i < values.length(); i++) {
                JSONObject band = values.getJSONObject(i);
                if (band.optBoolean("enabled", true)) bands.add(new DspEngine.Band(band.optString("type", "PK"), band.getDouble("frequency"), band.optDouble("gain"), band.optDouble("q", 1.4)));
            }
        } else if (settings.optBoolean("eq")) {
            JSONArray basic = settings.optJSONArray("bands"); int[] frequencies = {31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000};
            if (basic != null) for (int i = 0; i < Math.min(10, basic.length()); i++) bands.add(new DspEngine.Band("PK", frequencies[i], basic.optDouble(i), 1.4));
        }
        double gain = 1;
        JSONObject track = currentTrack();
        JSONObject tags = track == null ? null : track.optJSONObject("replayGain");
        String mode = settings.optString("replaygain", "off");
        if (tags != null && !mode.equals("off")) {
            String prefix = "replaygain_" + mode;
            if (!tags.has(prefix + "_gain")) prefix = "replaygain_track";
            try {
                double db = Double.parseDouble(tags.optString(prefix + "_gain").replaceAll("(?i)\\s*dB\\s*$", ""));
                double peak = tags.optDouble(prefix + "_peak", 1);
                if (db >= -60 && db <= 30 && Double.isFinite(peak)) gain = Math.min(Math.pow(10, db / 20), peak > 0 ? 1 / peak : 1);
            } catch (Exception ignored) {}
        }
        return new DspEngine.Config(bands.toArray(new DspEngine.Band[0]), gain, settings.optBoolean("reverb"), settings.optBoolean("compressor"), settings.optBoolean("hall"));
    }
    private void applyDsp() { try { if (processor != null) processor.engine.set(dspConfig()); } catch (Exception exception) { error = exception.getMessage(); } }
    private void statsTime(long elapsed) { try { stats.put("seconds", stats.optDouble("seconds", 0) + elapsed / 1000d); } catch (Exception ignored) {} }
    private void savePosition() {
        if (player == null) return;
        JSONObject ui = app.settings("ui"), track = currentTrack();
        try {
            if (track != null) { ui.put("trackId", track.getString("id")); ui.put("position", player.getCurrentPosition() / 1000d); }
            app.save("ui", ui);
        } catch (Exception ignored) {}
    }

    public JSONObject snapshot() {
        JSONObject track = currentTrack();
        String route = "未检测到外接音频设备";
        AudioManager manager = (AudioManager) getSystemService(AUDIO_SERVICE);
        List<String> devices = new ArrayList<>();
        for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            if (device.getType() == AudioDeviceInfo.TYPE_USB_DEVICE || device.getType() == AudioDeviceInfo.TYPE_USB_HEADSET || device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
                || device.getType() == AudioDeviceInfo.TYPE_WIRED_HEADSET || device.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) devices.add(device.getProductName().toString());
        }
        if (!devices.isEmpty()) route = "系统已连接：" + android.text.TextUtils.join(" / ", devices);
        return json("id", track == null ? "" : track.optString("id"), "position", player.getCurrentPosition() / 1000d,
            "duration", player.getDuration() > 0 ? player.getDuration() / 1000d : track == null ? 0 : track.optDouble("duration"),
            "paused", !player.getPlayWhenReady(), "playing", player.isPlaying(), "ready", player.getPlaybackState() == Player.STATE_READY,
            "error", error, "conversion", conversion, "settings", settings, "stats", stats, "outputRate", outputRate,
            "outputEncoding", outputEncoding, "route", route, "sleepRemaining", sleepDeadline == 0 ? 0 : Math.max(0, sleepDeadline - SystemClock.elapsedRealtime()),
            "sleepFinish", sleepFinish, "sleepWaiting", sleepExpired);
    }
    @Override public MediaSession onGetSession(MediaSession.ControllerInfo controller) { return session; }
    @Override public void onDestroy() {
        destroyed = true;
        app.main.removeCallbacks(tick); app.main.removeCallbacks(fadeTick); fileRequest++; savePosition(); app.save("tools", stats);
        if (session != null) session.release(); if (player != null) player.release(); app.playback = null; super.onDestroy();
    }
}
