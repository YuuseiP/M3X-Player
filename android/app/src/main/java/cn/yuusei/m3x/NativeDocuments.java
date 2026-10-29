package cn.yuusei.m3x;

import android.net.Uri;
import org.json.*;
import java.io.*;
import static cn.yuusei.m3x.PlayerApp.json;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class NativeDocuments {
    static void perform(MainActivity activity, String action, Uri uri, JSONObject data) throws Exception {
        PlayerApp app = PlayerApp.instance;
        switch (action) {
            case "scan-report-export":
                write(activity, uri, app.library.scanStatus().getJSONObject("scan").toString(2).getBytes("UTF-8")); break;
            case "backup-export": {
                JSONObject backup = json("schema", 1, "application", "cn.yuusei.m3x", "ui", app.settings("ui"), "audio", app.settings("audio"), "tools", app.settings("tools"));
                write(activity, uri, backup.toString(2).getBytes("UTF-8")); break;
            }
            case "backup-import": {
                JSONObject backup = new JSONObject(new String(MusicLibrary.read(activity.getContentResolver().openInputStream(uri), 8 * 1024 * 1024), "UTF-8"));
                if (backup.optInt("schema") != 1 || !backup.optString("application").equals("cn.yuusei.m3x")) throw new IOException("不是澄播放器备份");
                for (String key : new String[]{"ui", "audio", "tools"}) if (!(backup.opt(key) instanceof JSONObject)) throw new IOException("备份内容不完整");
                JSONObject audio = backup.getJSONObject("audio");
                // Validate all processing settings before committing any user data.
                JSONArray peq = audio.optJSONArray("peq");
                if (peq != null && peq.length() > 0) PeqPresets.parse(PeqPresets.export(peq));
                double rate = audio.optDouble("rate", 1); if (rate < .25 || rate > 4) throw new IOException("备份播放速度无效");
                java.util.concurrent.FutureTask<Void> restore = new java.util.concurrent.FutureTask<>(() -> {
                    app.playback.restoreBackup(backup); return null;
                });
                app.main.post(restore); restore.get(10, java.util.concurrent.TimeUnit.SECONDS);
                break;
            }
            case "peq-import": {
                JSONArray bands = PeqPresets.parse(MusicLibrary.decode(MusicLibrary.read(activity.getContentResolver().openInputStream(uri), 1024 * 1024)));
                activity.runOnUiThread(() -> { try { app.playback.configure(json("peq", bands, "eq", true)); } catch (Exception error) { activity.toast(error.getMessage()); } });
                break;
            }
            case "peq-export":
                write(activity, uri, PeqPresets.export(app.settings("audio").optJSONArray("peq") == null ? new JSONArray() : app.settings("audio").getJSONArray("peq")).getBytes("UTF-8")); break;
            case "clip-export": {
                JSONObject track = app.library.track(data.getString("id"));
                if (track == null) throw new IOException("歌曲不在曲库");
                if (app.library.identity(uri).equals(app.library.identity(Uri.parse(track.getString("uri"))))) throw new IOException("请选择不同于源歌曲的保存位置");
                long start = (long) (data.getDouble("start") * 1000) + track.optLong("cueStart");
                long end = (long) (data.getDouble("end") * 1000) + track.optLong("cueStart");
                if (start < track.optLong("cueStart") || end > track.optLong("cueStart") + track.optDouble("duration") * 1000 + 1) throw new IOException("片段超出歌曲范围");
                Uri inputUri = Uri.parse(track.getString("uri"));
                if (track.optString("codec").equals("DSD")) inputUri = Uri.fromFile(DsdDecoder.convert(app, inputUri, track.getString("id")));
                File file = PcmExport.decode(activity, inputUri, start, end, false, track.getString("id"));
                try (InputStream input = new FileInputStream(file); OutputStream output = activity.getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new IOException("无法写入所选位置");
                    byte[] bytes = new byte[65536]; int count; while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
                }
                break;
            }
            default: throw new IOException("未知文档操作");
        }
    }
    private static void write(MainActivity activity, Uri uri, byte[] data) throws Exception {
        try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IOException("无法写入所选位置"); output.write(data);
        }
    }
}
