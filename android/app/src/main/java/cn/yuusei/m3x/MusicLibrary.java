package cn.yuusei.m3x;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import static cn.yuusei.m3x.PlayerApp.json;

public final class MusicLibrary {
    private final PlayerApp app;
    private volatile List<JSONObject> tracks = Collections.emptyList();
    private volatile Map<String, JSONObject> byId = Collections.emptyMap();
    private volatile JSONObject scan = json("running", false);
    private volatile boolean cancelled;
    private final AtomicFile catalog;
    private final AtomicFile scanReport;
    private final ArtistClassification classification;
    private final ConcurrentMap<String, Object> artworkLocks = new ConcurrentHashMap<>();
    public final File artwork;
    public final File lyrics;
    private final Set<String> formats = new HashSet<>(Arrays.asList("flac", "mp3", "wav", "m4a", "aac", "ogg", "opus", "aif", "aiff", "dsf", "dff"));
    private boolean scanCandidate(String name) {
        String ext = extension(name);
        return formats.contains(ext) || ext.equals("cue") || ext.equals("wv");
    }

    public MusicLibrary(PlayerApp context) {
        app = context;
        catalog = new AtomicFile(new File(app.getFilesDir(), "library.json"));
        scanReport = new AtomicFile(new File(app.getFilesDir(), "scan-report.json"));
        artwork = new File(app.getCacheDir(), "covers"); artwork.mkdirs();
        lyrics = new File(app.getFilesDir(), "lyrics"); lyrics.mkdirs();
        try {
            classification = new ArtistClassification(new JSONObject(new String(
                read(app.getAssets().open("player/artist-classification.json"), 16 * 1024 * 1024), "UTF-8")));
        } catch (Exception error) { throw new IllegalStateException("内置分类配置不可读取", error); }
        try {
            JSONArray saved = new JSONArray(new String(read(catalog.openRead(), 64 * 1024 * 1024), "UTF-8"));
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < saved.length(); i++) list.add(saved.getJSONObject(i));
            setTracks(list);
        } catch (Exception ignored) {}
        try { scan = new JSONObject(new String(read(scanReport.openRead(), 16 * 1024 * 1024), "UTF-8")); }
        catch (Exception ignored) {}
    }

    public JSONObject options() {
        JSONObject settings = app.settings("scan");
        if (!settings.has("includeMediaStore")) {
            settings = json("includeMediaStore", true, "roots", new JSONArray(), "excludedFolders", new JSONArray(), "minDuration", 0);
        }
        return settings;
    }

    public synchronized void setOptions(JSONObject update) throws Exception {
        if (scan.optBoolean("running")) throw new IOException("请等待扫描结束");
        JSONObject options = options();
        int min = update.optInt("minDuration", options.optInt("minDuration"));
        if (min != 0 && min != 60) throw new IOException("扫描时长必须为 0 或 60 秒");
        JSONArray excluded = update.optJSONArray("excludedFolders");
        if (excluded != null) {
            if (excluded.length() > 100) throw new IOException("排除目录过多");
            JSONArray cleaned = new JSONArray();
            for (int i = 0; i < excluded.length(); i++) {
                String path = excluded.getString(i).trim().replace('\\', '/');
                if (path.startsWith("/") || path.contains(":") || Arrays.asList(path.split("/")).contains("..")) throw new IOException("请填写相对子目录");
                if (!path.isEmpty()) cleaned.put(path.replaceAll("/+$", ""));
            }
            options.put("excludedFolders", cleaned);
        }
        options.put("minDuration", min);
        if (update.has("includeMediaStore")) options.put("includeMediaStore", update.getBoolean("includeMediaStore"));
        app.save("scan", options);
    }

    public synchronized void addRoot(Uri uri) throws Exception {
        if (scan.optBoolean("running")) throw new IOException("请等待扫描结束");
        JSONObject options = options();
        JSONArray roots = options.optJSONArray("roots");
        for (int i = 0; i < roots.length(); i++) if (roots.getString(i).equals(uri.toString())) return;
        roots.put(uri.toString()); app.save("scan", options);
    }

    public synchronized void removeRoot(int index) throws Exception {
        if (scan.optBoolean("running")) throw new IOException("请等待扫描结束");
        JSONObject options = options();
        Uri removed = Uri.parse(options.getJSONArray("roots").getString(index));
        options.getJSONArray("roots").remove(index); app.save("scan", options);
        for (android.content.UriPermission grant : app.getContentResolver().getPersistedUriPermissions()) if (grant.getUri().equals(removed)) {
            int flags = (grant.isReadPermission() ? android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION : 0) | (grant.isWritePermission() ? android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION : 0);
            app.getContentResolver().releasePersistableUriPermission(removed, flags); break;
        }
    }

    public JSONObject snapshot() {
        JSONArray list = new JSONArray();
        Map<String, JSONObject> entities = new LinkedHashMap<>();
        int matched = 0;
        for (JSONObject item : tracks) {
            list.put(item);
            if (item.optBoolean("classificationMatched")) matched++;
            JSONArray credits = item.optJSONArray("credits");
            if (credits != null) for (int i = 0; i < credits.length(); i++) {
                JSONObject entity = credits.optJSONObject(i);
                entities.put(entity.optString("id"), entity);
            }
        }
        JSONObject options = options();
        JSONArray roots = options.optJSONArray("roots");
        JSONArray directories = new JSONArray();
        for (int i = 0; i < roots.length(); i++) {
            Uri uri = Uri.parse(roots.optString(i));
            String path = "file".equals(uri.getScheme()) ? uri.getPath() : DocumentsContract.getTreeDocumentId(uri);
            if (path.startsWith("primary:")) path = "内部存储/" + path.substring(8);
            directories.put(json("path", path));
        }
        return json("tracks", list, "scan", scan, "options", options, "directories", directories,
            "folder", roots.length() + " 个固定音乐目录",
            "classification", json("status", "confirmed", "builtin", true, "baseline", classification.baseline,
                "trackCount", classification.trackCount, "matched", matched, "entities", new JSONArray(entities.values())));
    }

    public JSONObject scanStatus() { return json("scan", scan); }

    public JSONObject track(String id) {
        return byId.get(id);
    }
    private void setTracks(List<JSONObject> list) throws Exception {
        Map<String, JSONObject> index = new HashMap<>();
        List<JSONObject> classified = new ArrayList<>();
        for (JSONObject item : list) {
            Uri uri = Uri.parse(item.optString("uri"));
            String path = DocumentsContract.isDocumentUri(app, uri) ? DocumentsContract.getDocumentId(uri) : uri.getPath();
            JSONObject track = classification.apply(item, "http".equals(uri.getScheme()) || "https".equals(uri.getScheme()) ? "" : path == null ? "" : path);
            index.put(track.optString("id"), track); classified.add(track);
        }
        byId = index; tracks = classified;
    }

    public List<JSONObject> all() { return tracks; }
    public void stop() { cancelled = true; }

    public synchronized void start() {
        if (scan.optBoolean("running")) return;
        cancelled = false;
        scan = json("running", true, "phase", "discovering", "processed", 0, "total", 0, "failed", 0, "skipped", 0);
        app.jobs.execute(this::performScan);
    }

    private boolean excluded(String relative, JSONObject options) {
        JSONArray list = options.optJSONArray("excludedFolders");
        String path = relative.toLowerCase(Locale.ROOT);
        for (int i = 0; i < list.length(); i++) {
            String prefix = list.optString(i).toLowerCase(Locale.ROOT);
            if (path.equals(prefix) || path.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    private static final class Source {
        Uri uri; String name, folder; long size, modified;
        Source(Uri uri, String name, String folder, long size, long modified) {
            this.uri = uri; this.name = name; this.folder = folder; this.size = size; this.modified = modified;
        }
    }
    String identity(Uri uri) throws IOException {
        if ("file".equals(uri.getScheme())) return "file:" + new File(uri.getPath()).getCanonicalPath();
        if (DocumentsContract.isDocumentUri(app, uri)) {
            String document = DocumentsContract.getDocumentId(uri);
            if ("com.android.externalstorage.documents".equals(uri.getAuthority()) && document.contains(":")) {
                String[] parts = document.split(":", 2);
                File volume = parts[0].equalsIgnoreCase("primary") ? android.os.Environment.getExternalStorageDirectory() : new File("/storage", parts[0]);
                File file = new File(volume, parts[1]);
                String parent = volume.getCanonicalPath(), path = file.getCanonicalPath();
                if (path.startsWith(parent + "/")) return "file:" + path;
            }
            return "document:" + uri.getAuthority() + ":" + document;
        }
        return uri.toString();
    }

    private void files(File dir, String relative, Map<String, Source> sources, JSONObject options) throws IOException {
        if (cancelled || excluded(relative, options)) return;
        File[] children = dir.listFiles();
        if (children == null) throw new IOException("目录不可读取：" + dir.getPath());
        for (File file : children) {
            if (cancelled) return;
            if (!file.getCanonicalPath().equals(new File(dir.getCanonicalFile(), file.getName()).getPath()) || file.getName().startsWith(".")) continue;
            String rel = relative.isEmpty() ? file.getName() : relative + "/" + file.getName();
            if (file.isDirectory()) files(file, rel, sources, options);
            else if (scanCandidate(file.getName()))
                sources.put(identity(Uri.fromFile(file)), new Source(Uri.fromFile(file), file.getName(), relative, file.length(), file.lastModified()));
        }
    }

    private void tree(Uri root, String document, String relative, Map<String, Source> sources, JSONObject options) throws IOException {
        if (cancelled || excluded(relative, options)) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(root, document);
        try (Cursor cursor = app.getContentResolver().query(children, new String[] {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED
        }, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取授权目录");
            while (!cancelled && cursor.moveToNext()) {
                String id = cursor.getString(0), name = cursor.getString(1);
                if (name.startsWith(".")) continue;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) tree(root, id, relative.isEmpty() ? name : relative + "/" + name, sources, options);
                else if (scanCandidate(name)) {
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(root, id);
                    sources.put(identity(uri), new Source(uri, name, relative, cursor.getLong(3), cursor.getLong(4)));
                }
            }
        }
    }

    private void performScan() {
        List<JSONObject> result = new ArrayList<>();
        for (JSONObject track : tracks) if (track.optString("uri").startsWith("https://") || track.optString("uri").startsWith("http://")) result.add(track);
        int done = 0, failed = 0, skipped = 0, cueFailed = 0, unsupported = 0;
        List<JSONObject> errors = new ArrayList<>();
        long started = SystemClock.elapsedRealtime();
        ExecutorService readers = Executors.newFixedThreadPool(4);
        JSONObject finished;
        try {
            JSONObject options = options();
            Map<String, Source> sources = new LinkedHashMap<>();
            if (options.optBoolean("includeMediaStore") && app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                try (Cursor cursor = app.getContentResolver().query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[] { "_id", "_data", "_display_name", "_size", "date_modified" }, null, null, null)) {
                    if (cursor == null) throw new IOException("无法读取 Android 媒体库");
                    while (!cancelled && cursor.moveToNext()) {
                        String path = cursor.getString(1), name = cursor.getString(2);
                        if (path != null && new File(path).isFile()) {
                            File file = new File(path);
                            String folder = mediaFolder(file);
                            if (!excluded(folder, options)) sources.put(identity(Uri.fromFile(file)),
                                new Source(Uri.fromFile(file), name, folder, file.length(), file.lastModified()));
                        } else {
                            Uri uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getString(0));
                            sources.put(identity(uri), new Source(uri, name, "媒体库", cursor.getLong(3), cursor.getLong(4) * 1000));
                        }
                    }
                }
            }
            JSONArray roots = options.getJSONArray("roots");
            if (options.optBoolean("includeMediaStore") && roots.length() == 0 && app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                throw new IOException("请先授予媒体库读取权限，或关闭 Android 媒体库来源");
            if (!options.optBoolean("includeMediaStore") && roots.length() == 0) throw new IOException("请先添加扫描目录或启用 Android 媒体库");
            for (int i = 0; i < roots.length() && !cancelled; i++) {
                Uri root = Uri.parse(roots.getString(i));
                if ("file".equals(root.getScheme())) files(new File(root.getPath()), "", sources, options);
                else tree(root, DocumentsContract.getTreeDocumentId(root), "", sources, options);
            }
            Map<String, JSONObject> previous = new HashMap<>();
            for (JSONObject item : tracks) if (!item.has("cueStart")) previous.put(identity(Uri.parse(item.optString("uri"))), item);
            List<Source> cues = new ArrayList<>();
            List<Source> audio = new ArrayList<>();
            for (Source source : sources.values()) {
                if (extension(source.name).equals("cue")) cues.add(source);
                else if (extension(source.name).equals("wv")) {
                    unsupported++; done++;
                    errors.add(scanError("unsupported", source, "暂不支持 WavPack 解码，未导入；原文件未修改"));
                } else audio.add(source);
            }
            CompletionService<JSONObject> completed = new ExecutorCompletionService<>(readers);
            Map<Future<JSONObject>, Source> pending = new HashMap<>();
            Map<Source, JSONObject> scanned = new HashMap<>();
            Map<String, String> folderCovers = new HashMap<>();
            Iterator<Source> remaining = audio.iterator();
            scan = json("running", true, "phase", "metadata", "total", sources.size(), "processed", 0);
            while (!cancelled && (remaining.hasNext() || !pending.isEmpty())) {
                while (remaining.hasNext() && pending.size() < 4 && !cancelled) {
                    Source source = remaining.next();
                    JSONObject old = previous.get(identity(source.uri));
                    pending.put(completed.submit(() -> {
                        JSONObject item = old != null && old.optInt("scanVersion") == 2 && old.optLong("modified") == source.modified && old.optLong("bytes") == source.size
                            && (!old.optString("cover").startsWith("native-covers/") || new File(artwork, old.optString("cover").substring(14)).isFile())
                            ? new JSONObject(old.toString()) : probe(source, folderCovers);
                        item.put("uri", source.uri.toString());
                        item.put("folder", source.folder);
                        if (old != null) item.put("added", old.optLong("added", System.currentTimeMillis()));
                        return item;
                    }), source);
                }
                Future<JSONObject> future = completed.poll(100, TimeUnit.MILLISECONDS);
                if (future == null) continue;
                Source source = pending.remove(future);
                try {
                    JSONObject item = future.get();
                    if (item.optDouble("duration") >= options.optInt("minDuration")) scanned.put(source, item); else skipped++;
                } catch (ExecutionException error) {
                    failed++; errors.add(scanError("audio", source, error.getCause().toString()));
                }
                scan = json("running", true, "phase", "metadata", "total", sources.size(), "processed", ++done, "failed", failed, "skipped", skipped,
                    "rate", done * 1000d / Math.max(1, SystemClock.elapsedRealtime() - started));
            }
            for (Source source : audio) if (scanned.containsKey(source)) result.add(scanned.get(source));
            for (Source source : cues) {
                if (cancelled) break;
                try { applyCue(source, sources, result); }
                catch (Exception error) { cueFailed++; errors.add(scanError("cue", source, error.toString())); }
                scan = json("running", true, "phase", "metadata", "total", sources.size(), "processed", ++done, "failed", failed, "skipped", skipped);
            }
            if (!cancelled) {
                JSONArray saved = new JSONArray(); for (JSONObject item : result) saved.put(item);
                FileOutputStream output = catalog.startWrite();
                try { output.write(saved.toString().getBytes("UTF-8")); catalog.finishWrite(output); }
                catch (Exception error) { catalog.failWrite(output); throw error; }
                setTracks(result);
            }
            finished = json("running", false, "processed", done, "total", sources.size(), "failed", failed,
                "cueFailed", cueFailed, "unsupported", unsupported, "skipped", skipped, "cancelled", cancelled,
                "lastScan", System.currentTimeMillis(), "errors", new JSONArray(errors),
                "rate", done * 1000d / Math.max(1, SystemClock.elapsedRealtime() - started));
        } catch (Exception error) {
            finished = json("running", false, "error", "扫描未完成：" + error.getMessage(), "failed", failed,
                "cueFailed", cueFailed, "unsupported", unsupported, "errors", new JSONArray(errors));
        } finally {
            readers.shutdownNow();
            try { while (!readers.awaitTermination(100, TimeUnit.MILLISECONDS)) {} }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        FileOutputStream report = null;
        try {
            report = scanReport.startWrite();
            report.write(finished.toString().getBytes("UTF-8")); scanReport.finishWrite(report);
        } catch (IOException error) {
            if (report != null) scanReport.failWrite(report);
            try { finished.put("reportError", "扫描报告保存失败：" + error.getMessage()); } catch (Exception ignored) {}
        }
        scan = finished;
    }

    private JSONObject scanError(String kind, Source source, String message) {
        return json("kind", kind, "path", Uri.decode(source.uri.toString()), "message", message);
    }

    private String mediaFolder(File file) throws IOException {
        String parent = file.getParentFile().getCanonicalPath();
        String primary = android.os.Environment.getExternalStorageDirectory().getCanonicalPath();
        if (parent.equals(primary)) return "";
        if (parent.startsWith(primary + "/")) return parent.substring(primary.length() + 1);
        return parent.replaceFirst("^/storage/[^/]+/?", "").replaceFirst("^/+", "");
    }

    public JSONObject importUri(Uri uri, String name) throws Exception {
        long size = 0;
        try (android.content.res.AssetFileDescriptor file = app.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (file != null) size = Math.max(0, file.getLength());
        } catch (IOException ignored) {}
        JSONObject track = probe(new Source(uri, name, "外部打开", size, 0));
        List<JSONObject> list = new ArrayList<>(tracks);
        if (track(track.getString("id")) == null) list.add(track);
        setTracks(list);
        persistCatalog();
        return track;
    }

    public synchronized JSONObject importNetwork(String url, String title, double duration) throws Exception {
        Uri uri = Uri.parse(url);
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) throw new IOException("仅支持 HTTP / HTTPS 音乐地址");
        if (uri.getHost() == null || uri.getUserInfo() != null) throw new IOException("音乐地址格式无效");
        String id = hash(url);
        JSONObject item = json("id", id, "uri", url, "path", url, "src", "native/" + id, "filename", uri.getLastPathSegment(),
            "title", title, "artist", "网络音乐", "album", uri.getHost(), "folder", uri.getHost(), "codec", extension(url).toUpperCase(Locale.ROOT),
            "duration", Math.max(0, duration), "bits", 0, "sampleRate", 0, "channels", 0, "bytes", 0, "cover", "cover-placeholder.svg", "added", System.currentTimeMillis());
        List<JSONObject> list = new ArrayList<>(tracks);
        list.removeIf(track -> track.optString("id").equals(id)); list.add(item); setTracks(list); persistCatalog(); return item;
    }

    private synchronized void persistCatalog() throws Exception {
        JSONArray saved = new JSONArray(); for (JSONObject item : tracks) saved.put(item);
        FileOutputStream output = catalog.startWrite();
        try { output.write(saved.toString().getBytes("UTF-8")); catalog.finishWrite(output); }
        catch (Exception error) { catalog.failWrite(output); throw error; }
    }

    private JSONObject probe(Source source) throws Exception {
        return probe(source, new HashMap<>());
    }

    private JSONObject probe(Source source, Map<String, String> folderCovers) throws Exception {
        String id = hash(identity(source.uri));
        JSONObject item = json("id", id, "uri", source.uri.toString(), "src", "native/" + id,
            "title", source.name.replaceFirst("\\.[^.]+$", ""), "filename", source.name,
            "artist", "未知歌手", "album", source.folder, "folder", source.folder,
            "path", source.uri.toString(), "cover", "cover-placeholder.svg", "bytes", source.size,
            "modified", source.modified, "added", System.currentTimeMillis(), "codec", extension(source.name).toUpperCase(Locale.ROOT),
            "bits", 0, "scanVersion", 2, "replayGain", new JSONObject());
        if (extension(source.name).equals("dsf") || extension(source.name).equals("dff")) {
            DsdDecoder.inspect(app, source.uri, item);
            return item;
        }
        byte[] picture = TagReader.read(app, source.uri, item);
        String metadataError = "";
        // A valid header is enough for import; platform tag/format readers are independent fallbacks.
        if (!validAudio(item) || !extension(source.name).equals("flac")) {
            MediaMetadataRetriever reader = new MediaMetadataRetriever();
            try {
                reader.setDataSource(app, source.uri);
                int[] keys = {7, 2, 1, 20, 0, 14, 9};
                String[] names = {"title", "artist", "album", "bitrate", "trackNumber", "year", "duration"};
                for (int i = 0; i < keys.length; i++) {
                    String value = reader.extractMetadata(keys[i]);
                    if (value != null && !value.trim().isEmpty())
                        item.put(names[i], names[i].equals("duration") ? Double.parseDouble(value) / 1000 : names[i].equals("bitrate") ? Long.parseLong(value) : value);
                }
                byte[] embedded = reader.getEmbeddedPicture();
                if (embedded != null) picture = embedded;
            } catch (Exception error) { metadataError = "标签：" + error; }
            finally { try { reader.release(); } catch (Exception ignored) {} }
        }
        if (!validAudio(item)) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(app, source.uri, null);
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat format = extractor.getTrackFormat(i);
                    if (!format.getString(MediaFormat.KEY_MIME).startsWith("audio/")) continue;
                    if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) item.put("sampleRate", format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                    if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) item.put("channels", format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    if (item.optDouble("duration", 0) <= 0 && format.containsKey(MediaFormat.KEY_DURATION)) item.put("duration", format.getLong(MediaFormat.KEY_DURATION) / 1e6);
                    break;
                }
            } catch (Exception error) { metadataError += " 格式：" + error; }
            finally { extractor.release(); }
        }
        if (picture != null) try { cacheArtwork(picture, item); } catch (IOException ignored) {}
        double duration = item.optDouble("duration", 0);
        if (!validAudio(item)) throw new IOException("音频参数无效或格式不支持 " + metadataError);
        if (item.optLong("bitrate") <= 0 && source.size > 0) item.put("bitrate", Math.round(source.size * 8d / duration));
        if (item.optString("cover").equals("cover-placeholder.svg")) {
            String parent = identity(source.uri);
            parent = parent.substring(0, parent.lastIndexOf('/') + 1);
            synchronized (folderCovers) {
                if (!folderCovers.containsKey(parent)) {
                    JSONObject cover = json("cover", "cover-placeholder.svg");
                    Uri sibling = sibling(source.uri, Arrays.asList("cover.jpg", "folder.jpg", "front.jpg", "cover.png", "folder.png"));
                    if (sibling != null) try (InputStream stream = app.getContentResolver().openInputStream(sibling)) { cacheArtwork(read(stream, 12 * 1024 * 1024), cover); } catch (IOException ignored) {}
                    folderCovers.put(parent, cover.getString("cover"));
                }
                item.put("cover", folderCovers.get(parent));
            }
        }
        return item;
    }

    private static boolean validAudio(JSONObject item) {
        double duration = item.optDouble("duration", 0);
        return Double.isFinite(duration) && duration > 0 && item.optInt("sampleRate") > 0 && item.optInt("channels") > 0;
    }

    private void cacheArtwork(byte[] bytes, JSONObject item) throws Exception {
        if (bytes.length > 12 * 1024 * 1024) throw new IOException("文件过大");
        String id = "art-" + hash(bytes);
        // Lock by image content so parallel readers transcode a shared cover only once.
        synchronized (artworkLocks.computeIfAbsent(id, key -> new Object())) {
            File file = new File(artwork, id + ".jpg");
            if (!file.isFile()) {
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) return;
                options.inJustDecodeBounds = false; options.inSampleSize = Math.max(1, Math.max(options.outWidth, options.outHeight) / 400);
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) return;
                AtomicFile target = new AtomicFile(file);
                FileOutputStream output = null;
                try {
                    output = target.startWrite();
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 84, output)) throw new IOException("封面编码失败");
                    target.finishWrite(output);
                } catch (Exception error) {
                    target.failWrite(output); throw error;
                } finally { bitmap.recycle(); }
            }
            item.put("cover", "native-covers/" + id + ".jpg");
        }
    }

    public Uri sibling(Uri uri, List<String> names) throws Exception {
        if ("file".equals(uri.getScheme())) {
            File parent = new File(uri.getPath()).getParentFile();
            File[] children = parent.listFiles();
            if (children != null) for (File file : children)
                for (String name : names) if (file.getName().equalsIgnoreCase(name) && file.isFile()) return Uri.fromFile(file);
        } else if (DocumentsContract.isDocumentUri(app, uri) && DocumentsContract.isTreeUri(uri)) {
            String id = DocumentsContract.getDocumentId(uri);
            int slash = id.lastIndexOf('/');
            if (slash < 0) return null;
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, id.substring(0, slash));
            try (Cursor cursor = app.getContentResolver().query(children, new String[] {"document_id", "_display_name"}, null, null, null)) {
                if (cursor != null) while (cursor.moveToNext())
                    for (String name : names) if (cursor.getString(1).equalsIgnoreCase(name)) return DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0));
            }
        }
        return null;
    }

    public JSONObject details(String id) throws Exception {
        JSONObject track = track(id);
        if (track == null) throw new IOException("歌曲不在当前曲库");
        JSONObject result = new JSONObject(track.toString());
        String filename = track.getString("filename").replaceFirst("\\.[^.]+$", ".lrc");
        Uri sidecar = sibling(Uri.parse(track.getString("uri")), Collections.singletonList(filename));
        String text = "";
        File downloaded = new File(lyrics, id + ".lrc");
        if (downloaded.isFile()) text = decode(read(new FileInputStream(downloaded), 2 * 1024 * 1024));
        else if (sidecar != null) text = decode(read(app.getContentResolver().openInputStream(sidecar), 2 * 1024 * 1024));
        else text = track.optString("embeddedLyrics");
        result.remove("embeddedLyrics"); result.put("lyrics", text);
        return result;
    }

    private void applyCue(Source cue, Map<String, Source> sources, List<JSONObject> result) throws Exception {
        List<CueParser.Entry> entries = CueParser.parse(decode(read(app.getContentResolver().openInputStream(cue.uri), 2 * 1024 * 1024)));
        if (entries.isEmpty()) throw new IOException("CUE 没有有效的音轨");
        String cuePath = cueLocation(cue);
        String parent = cuePath.substring(0, cuePath.lastIndexOf('/'));
        List<JSONObject> replacements = new ArrayList<>();
        Set<String> replaced = new HashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            CueParser.Entry entry = entries.get(i);
            Source audio = null;
            String target = CueParser.resolvePath(parent, entry.file);
            for (Source source : sources.values())
                if (!target.isEmpty() && cueLocation(source).equalsIgnoreCase(target)) { audio = source; break; }
            // Ripped albums are often flattened after the CUE was written; only allow a local basename fallback.
            if (audio == null) {
                String basename = entry.file.replace('\\', '/');
                basename = basename.substring(basename.lastIndexOf('/') + 1);
                for (Source source : sources.values()) {
                    String path = cueLocation(source);
                    if (source.name.equalsIgnoreCase(basename) && path.substring(0, path.lastIndexOf('/')).equalsIgnoreCase(parent)) {
                        if (audio != null) throw new IOException("CUE 引用存在重名文件：" + entry.file);
                        audio = source;
                    }
                }
            }
            if (audio == null) throw new IOException("CUE 引用的音频未找到：" + entry.file);
            JSONObject base = null;
            for (JSONObject song : result) if (song.optString("uri").equals(audio.uri.toString())) { base = song; break; }
            if (base == null) continue;
            long end = i + 1 < entries.size() && entries.get(i + 1).file.equals(entry.file) ? entries.get(i + 1).startMs : (long) (base.optDouble("duration") * 1000);
            if (end <= entry.startMs) throw new IOException("CUE 时间无效");
            JSONObject track = new JSONObject(base.toString());
            String id = hash(identity(cue.uri) + "#" + entry.number);
            track.put("id", id); track.put("src", "native/" + id);
            track.put("title", entry.title); track.put("artist", entry.artist.isEmpty() ? base.optString("artist") : entry.artist);
            track.put("originalArtist", entry.artist.isEmpty() ? base.optString("originalArtist", base.optString("artist")) : entry.artist);
            track.put("album", entry.album.isEmpty() ? base.optString("album") : entry.album);
            track.put("trackNumber", entry.number); track.put("cueStart", entry.startMs); track.put("cueEnd", end);
            track.put("duration", (end - entry.startMs) / 1000d); replacements.add(track);
            replaced.add(audio.uri.toString());
        }
        result.removeIf(item -> !item.has("cueStart") && replaced.contains(item.optString("uri")));
        result.addAll(replacements);
    }

    private String cueLocation(Source source) throws IOException {
        if (DocumentsContract.isDocumentUri(app, source.uri) && !"com.android.externalstorage.documents".equals(source.uri.getAuthority())) {
            // Provider document IDs may be opaque, so use the discovered tree-relative names.
            String root = DocumentsContract.getTreeDocumentId(source.uri);
            return "tree:" + source.uri.getAuthority() + ":" + root + "/" +
                (source.folder.isEmpty() ? "" : source.folder + "/") + source.name;
        }
        return identity(source.uri).replace('\\', '/');
    }

    static String extension(String name) { int at = name.lastIndexOf('.'); return at < 0 ? "" : name.substring(at + 1).toLowerCase(Locale.ROOT); }
    static String hash(String text) throws Exception {
        return hash(text.getBytes("UTF-8"));
    }
    static String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 12; i++) builder.append(String.format(Locale.ROOT, "%02x", digest[i]));
        return builder.toString();
    }
    public static byte[] read(InputStream input, int max) throws IOException {
        if (input == null) throw new IOException("无法读取文件");
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = stream.read(buffer)) != -1) { if (output.size() + n > max) throw new IOException("文件过大"); output.write(buffer, 0, n); }
            return output.toByteArray();
        }
    }
    static String decode(byte[] bytes) {
        if (bytes.length >= 2 && ((bytes[0] == -1 && bytes[1] == -2) || (bytes[0] == -2 && bytes[1] == -1))) return new String(bytes, Charset.forName("UTF-16"));
        try { return Charset.forName("UTF-8").newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().replace("\uFEFF", ""); }
        catch (Exception ignored) { return new String(bytes, Charset.forName("GB18030")); }
    }
}
