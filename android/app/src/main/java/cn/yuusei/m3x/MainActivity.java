package cn.yuusei.m3x;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.*;
import android.webkit.*;
import android.view.*;
import android.widget.Toast;
import org.json.*;
import java.io.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import static cn.yuusei.m3x.PlayerApp.json;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MainActivity extends Activity {
    WebView web;
    PlayerApp app;
    private ValueCallback<Uri[]> fileCallback;
    private String documentAction = "";
    private JSONObject documentData;
    private static final int DIRECTORY = 10, FILE = 11, EXPORT = 12, IMPORT = 13, STORAGE = 20;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = PlayerApp.instance;
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        getWindow().setStatusBarColor(Color.rgb(245, 242, 235));
        getWindow().setNavigationBarColor(Color.rgb(245, 242, 235));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        startService(new Intent(this, PlaybackService.class));
        web = new WebView(this); web.setBackgroundColor(Color.rgb(245, 242, 235));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(true); settings.setTextZoom(Math.round(100 * getResources().getConfiguration().fontScale));
        web.addJavascriptInterface(new Bridge(), "M3XNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if ("m3x.local".equals(request.getUrl().getHost()) && "https".equals(request.getUrl().getScheme())) return false;
                toast("请在系统浏览器中打开外部链接"); return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"m3x.local".equals(uri.getHost())) return response("text/plain", new ByteArrayInputStream(new byte[0]));
                String path = uri.getPath().substring(1);
                if (path.startsWith("native-api/")) {
                    app.awaitLibrary();
                    String api = "/api/" + path.substring("native-api/".length()) + (uri.getEncodedQuery() == null ? "" : "?" + uri.getEncodedQuery());
                    return response("application/json", new ByteArrayInputStream(new Bridge().request(api, "").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
                if (path.isEmpty()) path = "index.html";
                try {
                    if (path.contains("..")) throw new IOException();
                    if (path.startsWith("native-covers/")) {
                        File cover = new File(app.library.artwork, path.substring("native-covers/".length()));
                        if (cover.isFile()) return response("image/jpeg", new FileInputStream(cover));
                        return response("image/svg+xml", getAssets().open("player/cover-placeholder.svg"));
                    }
                    String mime = path.endsWith(".js") ? "text/javascript" : path.endsWith(".css") ? "text/css" : path.endsWith(".svg") ? "image/svg+xml" : path.endsWith(".html") ? "text/html" : path.endsWith(".woff2") ? "font/woff2" : "application/octet-stream";
                    return response(mime, getAssets().open("player/" + path));
                } catch (Exception error) { return response("text/plain", new ByteArrayInputStream(new byte[0])); }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                android.util.Log.d("M3X.Web", message.message() + " " + message.sourceId() + ":" + message.lineNumber()); return true;
            }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                try { startActivityForResult(intent, FILE); } catch (Exception error) { callback.onReceiveValue(null); fileCallback = null; }
                return true;
            }
        });
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        setContentView(web);
        web.loadUrl("https://m3x.local/");
        if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE);
        openExternal(getIntent());
    }

    private WebResourceResponse response(String type, InputStream stream) { return new WebResourceResponse(type, "UTF-8", stream); }
    public void toast(String text) { runOnUiThread(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show()); }
    public void refresh() { if (web != null) web.evaluateJavascript("window.dispatchEvent(new Event('m3x-native-library'))", null); }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == STORAGE) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) refresh();
            else toast("仍可在软件设置中选择音乐目录并授权读取");
        }
    }

    void directories() {
        if (app.library.scanStatus().optJSONObject("scan").optBoolean("running")) {
            toast("请等待扫描结束"); return;
        }
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), DIRECTORY);
    }

    void document(String action, JSONObject data) {
        documentAction = action; documentData = data;
        boolean export = action.equals("backup-export") || action.equals("peq-export") || action.equals("clip-export") || action.equals("scan-report-export");
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(action.equals("clip-export") ? "audio/wav" : action.startsWith("peq") ? "text/plain" : "application/json");
        if (export) intent.putExtra(Intent.EXTRA_TITLE, action.equals("scan-report-export") ? "澄-扫描报告.json" : action.equals("clip-export") ? "澄-片段.wav" : action.startsWith("peq") ? "澄-均衡器.txt" : "澄-音乐资料.json");
        startActivityForResult(intent, export ? EXPORT : IMPORT);
    }

    void externalEdit(JSONObject data) throws Exception {
        JSONObject track = app.library.track(data.getString("id"));
        if (track == null) throw new IOException("歌曲不在曲库");
        Uri uri = Uri.parse(track.getString("uri"));
        boolean lyrics = data.optBoolean("lyrics");
        if (lyrics) {
            File cached = new File(app.library.lyrics, track.getString("id") + ".lrc");
            uri = cached.isFile() ? Uri.fromFile(cached) : app.library.sibling(uri, java.util.Collections.singletonList(track.getString("filename").replaceFirst("\\.[^.]+$", ".lrc")));
            if (uri == null) throw new IOException("暂无独立 LRC 文件，可先导入歌词");
        }
        if (!"file".equals(uri.getScheme()) && !"content".equals(uri.getScheme())) throw new IOException("请先在本地下载该歌曲");
        if ("file".equals(uri.getScheme())) uri = androidx.core.content.FileProvider.getUriForFile(this, getPackageName() + ".files", new File(uri.getPath()));
        Intent edit = new Intent(Intent.ACTION_EDIT).setDataAndType(uri, lyrics ? "text/plain" : "audio/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (edit.resolveActivity(getPackageManager()) == null) throw new IOException("请先安装支持此文件的歌词／标签编辑器");
        startActivity(Intent.createChooser(edit, lyrics ? "编辑歌词" : "编辑歌曲标签"));
    }

    @Override protected void onActivityResult(int requestCode, int result, Intent intent) {
        super.onActivityResult(requestCode, result, intent);
        if (requestCode == FILE) {
            if (fileCallback != null) fileCallback.onReceiveValue(result == RESULT_OK && intent != null ? new Uri[]{intent.getData()} : null);
            fileCallback = null; return;
        }
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        Uri uri = intent.getData();
        if (requestCode == DIRECTORY) {
            try {
                if ((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new IOException("所选目录未授予读取权限");
                int grantFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if ((intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) grantFlags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, grantFlags);
                app.library.addRoot(uri); refresh();
            } catch (Exception error) { toast("无法保存目录授权：" + error.getMessage()); }
        } else if (requestCode == EXPORT || requestCode == IMPORT) {
            final String action = documentAction; final JSONObject data = documentData;
            app.jobs.execute(() -> {
                try {
                    NativeDocuments.perform(this, action, uri, data);
                    runOnUiThread(() -> { toast("已完成"); if (!isDestroyed()) web.reload(); });
                } catch (Exception error) { toast("未完成：" + error.getMessage()); }
            });
        }
    }

    private void openExternal(Intent intent) {
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        Uri uri = intent.getData();
        app.jobs.execute(() -> {
            try {
                JSONObject track = app.library.importUri(uri, uri.getLastPathSegment() == null ? "外部音频" : uri.getLastPathSegment());
                app.main.post(() -> { refresh(); if (app.playback != null) app.playback.load(track.optString("id"), true); });
            } catch (Exception error) { toast(error.getMessage()); }
        });
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); openExternal(intent); }
    @Override public boolean onKeyDown(int code, KeyEvent event) {
        PlaybackService service = app.playback;
        if (service != null && event.getRepeatCount() == 0) {
            if (code == KeyEvent.KEYCODE_MEDIA_NEXT) { service.next(false); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_PREVIOUS) { service.next(true); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY) { service.play(); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_STOP) { service.pause(); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_HEADSETHOOK) {
                if (service.player.getPlayWhenReady()) service.pause(); else service.play(); return true;
            }
        }
        return super.onKeyDown(code, event);
    }
    @Override public void onBackPressed() {
        web.evaluateJavascript("(function(){var a=document.querySelectorAll('dialog[open]'),d=a[a.length-1];if(d){if(window.M3X_UI)M3X_UI.closeDialog(d,true);else d.close();return true;}return window.M3X_UI?M3X_UI.back():false;})()", result -> { if (!"true".equals(result)) moveTaskToBack(true); });
    }
    @Override protected void onDestroy() { if (web != null) { web.removeJavascriptInterface("M3XNative"); web.destroy(); } super.onDestroy(); }

    public void lyricsResult(JSONObject result) {
        runOnUiThread(() -> { if (web != null && !isDestroyed()) web.evaluateJavascript(
            "window.dispatchEvent(new CustomEvent('m3x-lyrics-result',{detail:" + result.toString() + "}))", null); });
    }
    public final class Bridge {
        @JavascriptInterface public String bootstrap() {
            return json("ui", app.settings("ui"), "tools", app.settings("tools"), "audio", app.settings("audio")).toString();
        }
        @JavascriptInterface public String request(String path, String body) {
            try {
                app.awaitLibrary();
                JSONObject data = body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
                if (path.equals("/api/library")) return app.library.snapshot().toString();
                if (path.equals("/api/scan-status")) return app.library.scanStatus().toString();
                if (path.startsWith("/api/track?")) return app.library.details(Uri.parse(path).getQueryParameter("id")).toString();
                if (path.equals("/api/scan")) { app.library.start(); return app.library.snapshot().toString(); }
                if (path.equals("/api/scan-cancel")) { app.library.stop(); return app.library.snapshot().toString(); }
                if (path.equals("/api/scan-options")) { app.library.setOptions(data); return app.library.snapshot().toString(); }
                if (path.equals("/api/root-remove")) { app.library.removeRoot(data.getInt("index")); return app.library.snapshot().toString(); }
                throw new IOException("此操作不受支持");
            } catch (Exception error) { return json("error", error.getMessage()).toString(); }
        }
        @JavascriptInterface public String command(String name, String body) {
            try {
                final JSONObject data = body == null || body.isEmpty() ? new JSONObject() : new JSONObject(body);
                FutureTask<String> task = new FutureTask<>(() -> {
                    PlaybackService service = app.playback;
                    if (service == null) return json("error", "播放服务正在启动").toString();
                    switch (name) {
                        case "poll": return service.snapshot().toString();
                        case "queue": {
                            JSONArray ids = new JSONArray();
                            for (int i = 0; i < service.player.getMediaItemCount(); i++) ids.put(service.player.getMediaItemAt(i).mediaId);
                            return json("ids", ids).toString();
                        }
                        case "load": service.load(data.getString("id"), data.optBoolean("play")); break;
                        case "play": service.play(); break;
                        case "pause": service.pause(); break;
                        case "next": service.next(false); break;
                        case "previous": service.next(true); break;
                        case "seek": service.player.seekTo(Math.max(0, (long) (data.getDouble("position") * 1000))); break;
                        case "configure": service.configure(data); break;
                        case "sleep": service.sleep(data.optInt("minutes"), data.optBoolean("finish")); break;
                        case "ui-save": app.save("ui", data); service.syncQueue(data); return "{}";
                        case "tools-save": app.save("tools", data); return "{}";
                        case "directories": directories(); break;
                        case "lyrics": app.lyricManager.handle(MainActivity.this, data); break;
                        case "network": NativeNetwork.handle(MainActivity.this, data); break;
                        case "external-edit": externalEdit(data); break;
                        case "system-eq": {
                            Intent effects = new Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                                .putExtra(android.media.audiofx.AudioEffect.EXTRA_AUDIO_SESSION, service.player.getAudioSessionId())
                                .putExtra(android.media.audiofx.AudioEffect.EXTRA_PACKAGE_NAME, getPackageName())
                                .putExtra(android.media.audiofx.AudioEffect.EXTRA_CONTENT_TYPE, android.media.audiofx.AudioEffect.CONTENT_TYPE_MUSIC);
                            if (effects.resolveActivity(getPackageManager()) == null) throw new IOException("本系统未提供均衡器面板");
                            startActivity(effects); break;
                        }
                        case "licenses": {
                            String licenses = new String(MusicLibrary.read(getAssets().open("player/licenses.txt"), 1024 * 1024), "UTF-8");
                            android.widget.TextView text = new android.widget.TextView(MainActivity.this); text.setText(licenses); text.setTextSize(12); text.setPadding(24, 16, 24, 16); text.setTextIsSelectable(true);
                            android.widget.ScrollView scroll = new android.widget.ScrollView(MainActivity.this); scroll.addView(text);
                            new AlertDialog.Builder(MainActivity.this).setTitle("开源许可").setView(scroll).setPositiveButton("关闭", null).show(); break;
                        }
                        case "backup-export": case "backup-import": case "peq-import": case "peq-export": case "clip-export": case "scan-report-export": document(name, data); break;
                        case "clear-cache":
                            if (service.player.getPlayWhenReady()) throw new IOException("请先暂停播放，再清理缓存");
                            for (File directory : new File[]{app.library.artwork, new File(getCacheDir(), "decoded")}) {
                                File[] files = directory.listFiles();
                                if (files != null) for (File file : files) if (file.isFile()) file.delete();
                            }
                            toast("可再生成缓存已清理，音乐与歌单保留"); break;
                        default: throw new IOException("未知操作");
                    }
                    return service.snapshot().toString();
                });
                app.main.post(task); return task.get(5, TimeUnit.SECONDS);
            } catch (Exception error) { return json("error", error.getCause() == null ? error.getMessage() : error.getCause().getMessage()).toString(); }
        }
    }
}
