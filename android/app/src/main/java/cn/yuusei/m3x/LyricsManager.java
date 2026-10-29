package cn.yuusei.m3x;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.AtomicFile;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.yuusei.m3x.PlayerApp.json;

/** A bounded worker owns drafts, selections, write journals and recovery copies. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class LyricsManager {
    private final PlayerApp app;
    private final File root;
    private final LyricsProviders providers = new LyricsProviders();
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8));
    private final LinkedHashMap<String, JSONObject> drafts = new LinkedHashMap<>();
    LyricsManager(PlayerApp app) { this.app = app; root = new File(app.getFilesDir(), "lyric-selections"); root.mkdirs(); }
    private File file(String id, String suffix) throws Exception { return new File(root, MusicLibrary.hash(id) + suffix); }
    private JSONObject read(File path) throws Exception {
        AtomicFile f = new AtomicFile(path);
        if (!path.exists() && !new File(path + ".bak").exists()) return null;
        return new JSONObject(new String(MusicLibrary.read(f.openRead(), 3 * 1024 * 1024), "UTF-8"));
    }
    private synchronized void write(File path, JSONObject value) throws Exception {
        AtomicFile f = new AtomicFile(path); FileOutputStream out = f.startWrite();
        try { out.write(value.toString().getBytes("UTF-8")); f.finishWrite(out); }
        catch (Exception error) { f.failWrite(out); throw error; }
    }
    private String signature(JSONObject t) throws Exception {
        return MusicLibrary.hash(t.optString("uri") + "\n" + t.optString("title") + "\n" +
            t.optString("originalArtist", t.optString("artist")) + "\n" + t.optString("album") + "\n" + t.optDouble("duration") + "\n" + t.optLong("cueStart", -1));
    }
    synchronized JSONObject selected(String id) throws Exception {
        JSONObject doc = read(file(id, ".json")), track = app.library.track(id);
        if (doc == null || track == null || !doc.optString("uri").equals(track.optString("uri"))) return null;
        if (doc.has("trackSignature") && !doc.getString("trackSignature").equals(signature(track))) return null;
        LyricsDocument.validate(doc); return doc;
    }
    private JSONObject track(String id) throws Exception {
        JSONObject value = app.library.track(id);
        if (value == null) {
            JSONObject journal = read(file(id, ".journal"));
            value = journal == null ? null : journal.optJSONObject("track");
        }
        if (value == null) throw new IOException("歌曲已不在曲库，请重新打开歌词搜索");
        return new JSONObject(value.toString());
    }
    private String remember(String id, JSONObject item) throws Exception {
        String token = UUID.randomUUID().toString();
        item.put("trackId", id).put("uri", track(id).getString("uri"));
        drafts.put(token, item);
        while (drafts.size() > 60) drafts.remove(drafts.keySet().iterator().next());
        return token;
    }
    private JSONObject draft(String id, String token) throws Exception {
        JSONObject value = drafts.get(token);
        if (value == null || !id.equals(value.optString("trackId")) || !track(id).optString("uri").equals(value.optString("uri")))
            throw new IOException("候选已过期或歌曲已变更，请重新搜索");
        return value;
    }
    void handle(MainActivity activity, JSONObject input) {
        final String id = input.optString("id"), operation = input.optString("operation"), request = input.optString("requestId");
        if (request.length() > 100) return;
        try { worker.execute(() -> {
            JSONObject result = json("id", id, "operation", operation, "requestId", request);
            try {
                if (!operation.equals("recoveries")) track(id);
                switch (operation) {
                    case "recoveries": {
                        JSONArray entries = new JSONArray(); File[] files = root.listFiles((dir, name) -> name.endsWith(".journal") || name.endsWith(".journal.bak"));
                        Set<String> seen = new HashSet<>();
                        if (files != null) for (File path : files) {
                            if (path.getName().endsWith(".bak")) path = new File(root, path.getName().substring(0, path.getName().length() - 4));
                            if (!seen.add(path.getName())) continue;
                            try { JSONObject j = read(path); JSONObject t = j.optJSONObject("track");
                                if (t != null) entries.put(json("id", t.optString("id"), "title", t.optString("title"), "uri", t.optString("uri"), "state", j.optString("state")));
                            } catch (Exception ignored) { /* One damaged journal does not hide others. */ }
                        }
                        result.put("entries", entries); break;
                    }
                    case "state": {
                        JSONObject doc = selected(id), journal = read(file(id, ".journal"));
                        result.put("document", doc == null ? JSONObject.NULL : doc);
                        result.put("recovery", journal == null ? "" : journal.optString("state"));
                        result.put("writable", writable(track(id)));
                        break;
                    }
                    case "search": {
                        JSONArray rows = providers.search(input.getString("provider"), input.getString("query"));
                        for (int i = 0; i < rows.length(); i++) { JSONObject row = rows.getJSONObject(i); row.put("token", remember(id, new JSONObject(row.toString()))); }
                        result.put("entries", rows); break;
                    }
                    case "fetch": {
                        JSONObject doc = providers.fetch(draft(id, input.getString("token")));
                        String token = remember(id, doc); result.put("document", doc).put("token", token); break;
                    }
                    case "select": {
                        JSONObject source = input.has("token") ? draft(id, input.getString("token")) : selected(id);
                        if (source == null) throw new IOException("请先选择候选歌词");
                        if (!input.has("token") && !source.optString("revision").equals(input.optString("revision"))) throw new IOException("歌词标注已变更，请重新打开");
                        JSONObject doc = LyricsDocument.annotations(source, input.getJSONObject("annotations"));
                        doc.put("revision", UUID.randomUUID().toString()).put("selectedAt", System.currentTimeMillis()).put("trackSignature", signature(track(id)));
                        write(file(id, ".json"), doc); result.put("document", doc).put("message", "歌词和逐曲标注已离线保存"); break;
                    }
                    case "embed": {
                        JSONObject doc = selected(id);
                        if (doc == null || !doc.optString("revision").equals(input.optString("revision"))) throw new IOException("标注未保存或已变更，请先采用歌词");
                        if (LyricsDocument.parse(doc.optString("translation")).stream().noneMatch(line -> !line.text.isEmpty())) throw new IOException("此候选没有同步译文，不能作为双语歌词固化");
                        mutate(id, LyricsDocument.lrc(doc, true), false, false);
                        result.put("message", "双语歌词已写入歌曲标签并回读验证；播放已暂停"); break;
                    }
                    case "restore": {
                        mutate(id, "", true, input.optBoolean("recoverInterrupted"));
                        result.put("message", "已恢复固化前的歌曲文件；播放已暂停"); break;
                    }
                    default: throw new IOException("未知歌词操作");
                }
            } catch (Exception error) {
                android.util.Log.w("M3X.Lyrics", operation + " failed", error);
                result = json("id", id, "operation", operation, "requestId", request, "error", error.getMessage() == null ? "歌词操作失败" : error.getMessage());
            }
            activity.lyricsResult(result);
        }); } catch (RejectedExecutionException error) { activity.lyricsResult(json("id", id, "operation", operation, "requestId", request, "error", "请求过多，请等待当前操作完成")); }
    }
    static boolean writable(JSONObject t) {
        String scheme = Uri.parse(t.optString("uri")).getScheme();
        return !t.has("cueStart") && ("file".equals(scheme) || "content".equals(scheme)) && AudioTagEditor.supported(t.optString("filename"));
    }
    private InputStream open(Uri uri) throws Exception {
        InputStream in = app.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("无法读取歌曲文件"); return in;
    }
    private <T> T main(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call); app.main.post(task);
        try { return task.get(); } catch (ExecutionException error) { throw new IOException(error.getCause().getMessage(), error.getCause()); }
    }
    private void replace(Uri uri, File staged) throws Exception {
        if ("file".equals(uri.getScheme())) {
            File target = new File(uri.getPath());
            File sibling = File.createTempFile(".m3x-lyrics-", ".tmp", target.getParentFile());
            try {
                AudioTagEditor.copy(new FileInputStream(staged), sibling);
                android.system.Os.chmod(sibling.getPath(), android.system.Os.stat(target.getPath()).st_mode & 0777);
                android.system.Os.rename(sibling.getPath(), target.getPath());
            } finally { sibling.delete(); }
        } else {
            // SAF providers do not promise atomic replacement. Journal + private complete backup precede truncation.
            try (ParcelFileDescriptor fd = app.getContentResolver().openFileDescriptor(uri, "rwt")) {
                if (fd == null) throw new IOException("目录未授予写入权限");
                try (FileInputStream in = new FileInputStream(staged); FileOutputStream out = new FileOutputStream(fd.getFileDescriptor())) {
                    byte[] buffer = new byte[65536]; int n;
                    while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    out.flush(); out.getFD().sync();
                }
            }
        }
    }
    void mutate(String id, String text, boolean restore, boolean recoverInterrupted) throws Exception {
        JSONObject t = track(id);
        if (!writable(t)) throw new IOException("仅完整的本地音频可固化；CUE 分轨、网络歌曲及未支持格式可搜索和采用歌词");
        Uri uri = Uri.parse(t.getString("uri")); File journalFile = file(id, ".journal");
        JSONObject previous = read(journalFile);
        if (previous != null && !uri.toString().equals(previous.optString("uri"))) throw new IOException("源文件地址与恢复记录不同");
        if (!restore && previous != null && !previous.optString("state").equals("complete")) throw new IOException("上次写入未完成，请先恢复文件");
        try (ParcelFileDescriptor source = app.getContentResolver().openFileDescriptor(uri, "r")) {
            long size = source == null ? -1 : source.getStatSize();
            if (size > 0 && new android.os.StatFs(root.getPath()).getAvailableBytes() < size * 2 + 16 * 1024 * 1024L)
                throw new IOException("应用存储空间不足，需要容纳歌曲备份和暂存副本");
        }
        app.library.beginTagWrite(); PlaybackService service = app.playback;
        boolean held = false, writing = false, succeeded = false;
        File rollback = null, staged = null; JSONObject journal = null;
        try {
            if (service != null) { main(() -> { service.holdForTags(); return null; }); held = true; }
            String ext = AudioTagEditor.extension(t.getString("filename"));
            rollback = File.createTempFile("before-", "." + ext, root);
            AudioTagEditor.copy(open(uri), rollback);
            String before = AudioTagEditor.sha(rollback);
            if (restore) {
                if (previous == null) throw new IOException("没有可恢复的固化记录");
                if (!before.equals(previous.optString("afterSHA")) && !before.equals(previous.optString("beforeSHA")) &&
                    !(recoverInterrupted && !previous.optString("state").equals("complete")))
                    throw new IOException("歌曲已被外部修改，拒绝覆盖；恢复副本保留在应用内");
                staged = new File(root, previous.getString("backup"));
                if (!staged.getCanonicalFile().getParentFile().equals(root.getCanonicalFile()) || !AudioTagEditor.sha(staged).equals(previous.getString("beforeSHA"))) throw new IOException("恢复副本校验失败");
                text = AudioTagEditor.lyrics(staged);
            } else {
                staged = File.createTempFile("stage-", "." + ext, root);
                AudioTagEditor.copy(new FileInputStream(rollback), staged);
                AudioTagEditor.embed(staged, text);
            }
            String after = AudioTagEditor.sha(staged);
            // Detect edits between backup and commit, including changes made by another application.
            if (!before.equals(AudioTagEditor.sha(open(uri)))) throw new IOException("歌曲在准备期间被修改，请重新操作");
            journal = json("uri", uri.toString(), "state", "pending", "backup", rollback.getName(), "beforeSHA", before,
                "afterSHA", after, "createdAt", System.currentTimeMillis(), "restoring", restore, "track", t);
            if (restore) journal.put("backup", staged.getName()).put("beforeSHA", after).put("afterSHA", before);
            write(journalFile, journal); writing = true;
            replace(uri, staged);
            if (!after.equals(AudioTagEditor.sha(open(uri)))) throw new IOException("原文件写入后校验失败");
            journal.put("state", "complete"); write(journalFile, journal);
            app.library.lyricsWritten(id, text, staged.length());
            if (restore) { new AtomicFile(file(id, ".json")).delete(); new AtomicFile(journalFile).delete(); rollback.delete(); }
            if (previous != null) new File(root, previous.optString("backup")).delete();
            succeeded = true;
        } catch (Exception failure) {
            if (writing && rollback != null) {
                try {
                    if (!AudioTagEditor.sha(rollback).equals(AudioTagEditor.sha(open(uri)))) replace(uri, rollback);
                    if (!AudioTagEditor.sha(rollback).equals(AudioTagEditor.sha(open(uri)))) throw new IOException("回滚校验失败");
                    if (previous == null) new AtomicFile(journalFile).delete(); else write(journalFile, previous);
                    writing = false;
                } catch (Exception recovery) {
                    if (journal != null) { journal.put("state", "needs-recovery"); write(journalFile, journal); }
                    throw new IOException("写入及自动回滚未完成，完整副本已保留，请使用恢复文件功能。" + recovery.getMessage(), failure);
                }
            }
            throw failure;
        } finally {
            if (staged != null && (!restore || succeeded)) staged.delete();
            if (rollback != null && (!writing || !succeeded && journal == null)) rollback.delete();
            try { if (held) main(() -> { service.releaseTags(); return null; }); }
            finally { app.library.endTagWrite(); }
        }
    }
}
