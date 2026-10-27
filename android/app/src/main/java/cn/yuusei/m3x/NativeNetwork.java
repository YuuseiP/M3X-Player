package cn.yuusei.m3x;

import android.content.Context;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.security.keystore.*;
import android.util.Base64;
import org.json.*;
import org.w3c.dom.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.io.*;
import java.net.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import static cn.yuusei.m3x.PlayerApp.json;

final class NativeNetwork {
    static final OkHttpClient HTTP = new OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false).build();
    private static final String AUTOEQ = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results/";

    static byte[] request(String url, String method, String body, Map<String, String> headers, int maximum) throws Exception {
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()) throw new IOException("网络地址无效");
        Request.Builder builder = new Request.Builder().url(parsed).header("User-Agent", "M3X-Player/0.2");
        for (Map.Entry<String, String> header : headers.entrySet()) builder.header(header.getKey(), header.getValue());
        if (method.equals("GET")) builder.get();
        else builder.method(method, RequestBody.create(body == null ? "" : body, MediaType.parse("text/xml; charset=utf-8")));
        try (Response response = HTTP.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("服务器返回 HTTP " + response.code());
            if (response.body() == null) throw new IOException("服务器没有返回内容");
            return MusicLibrary.read(response.body().byteStream(), maximum);
        }
    }

    static Map<String, String> headers(String url) {
        Map<String, String> result = new HashMap<>();
        try {
            JSONObject accounts = PlayerApp.instance.settings("network");
            Iterator<String> keys = accounts.keys();
            while (keys.hasNext()) {
                String base = keys.next();
                if (url.startsWith(base)) { result.put("Authorization", decrypt(accounts.getString(base))); break; }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias("m3x-network")) return (SecretKey) store.getKey("m3x-network", null);
        KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder("m3x-network", KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private static String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(value.getBytes("UTF-8")), Base64.NO_WRAP);
    }
    private static String decrypt(String value) throws Exception {
        String[] parts = value.split(":"); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), "UTF-8");
    }
    static Document xml(byte[] bytes) throws Exception {
        String text = MusicLibrary.decode(bytes);
        if (text.toUpperCase(Locale.ROOT).contains("<!DOCTYPE") || text.toUpperCase(Locale.ROOT).contains("<!ENTITY")) throw new IOException("不支持含 DTD 的服务描述");
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(new org.xml.sax.InputSource(new StringReader(text)));
    }
    static String value(Element element, String name) {
        NodeList nodes = element.getElementsByTagNameNS("*", name); return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent();
    }

    static JSONObject webdav(JSONObject data) throws Exception {
        String url = data.getString("url"); if (!url.endsWith("/")) url += "/";
        Map<String, String> headers = headers(url); headers.put("Depth", "1");
        if (!data.optString("username").isEmpty()) {
            if (!url.startsWith("https://")) throw new IOException("需要密码的 WebDAV 请使用 HTTPS");
            String auth = Credentials.basic(data.getString("username"), data.optString("password"), java.nio.charset.StandardCharsets.UTF_8);
            headers.put("Authorization", auth);
            JSONObject accounts = PlayerApp.instance.settings("network"); accounts.put(url, encrypt(auth)); PlayerApp.instance.save("network", accounts);
        }
        byte[] bytes = request(url, "PROPFIND", "<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>", headers, 4 * 1024 * 1024);
        Document doc = xml(bytes); NodeList responses = doc.getElementsByTagNameNS("*", "response"); JSONArray entries = new JSONArray();
        for (int i = 0; i < responses.getLength(); i++) {
            Element response = (Element) responses.item(i); String href = value(response, "href");
            HttpUrl resolved = HttpUrl.get(url).resolve(href); if (resolved == null) continue;
            if (!resolved.host().equals(HttpUrl.get(url).host()) || resolved.port() != HttpUrl.get(url).port()) continue;
            String full = resolved.toString(); if (full.replaceAll("/+$", "").equals(url.replaceAll("/+$", ""))) continue;
            boolean folder = response.getElementsByTagNameNS("*", "collection").getLength() > 0;
            String title = value(response, "displayname");
            if (title.isEmpty()) title = Uri.decode(resolved.pathSegments().get(Math.max(0, resolved.pathSegments().size() - (folder ? 2 : 1))));
            if (folder || value(response, "getcontenttype").startsWith("audio/") || full.matches("(?i).+\\.(flac|mp3|m4a|aac|ogg|opus|wav)(\\?.*)?"))
                entries.put(json("title", title, "url", full, "folder", folder));
        }
        return json("entries", entries, "url", url);
    }

    static JSONObject discover(Context context) throws Exception {
        WifiManager manager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiManager.MulticastLock lock = manager.createMulticastLock("m3x-dlna"); lock.acquire();
        Set<String> locations = new LinkedHashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(500);
            byte[] request = ("M-SEARCH * HTTP/1.1\r\nHOST:239.255.255.250:1900\r\nMAN:\"ssdp:discover\"\r\nMX:2\r\nST:urn:schemas-upnp-org:device:MediaServer:1\r\n\r\n").getBytes("US-ASCII");
            socket.send(new DatagramPacket(request, request.length, InetAddress.getByName("239.255.255.250"), 1900));
            long deadline = System.currentTimeMillis() + 3500;
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket packet = new DatagramPacket(new byte[8192], 8192);
                try { socket.receive(packet); }
                catch (SocketTimeoutException ignored) { continue; }
                for (String line : new String(packet.getData(), 0, packet.getLength(), "UTF-8").split("\\r?\\n"))
                    if (line.toLowerCase(Locale.ROOT).startsWith("location:")) locations.add(line.substring(9).trim());
            }
        } finally { lock.release(); }
        JSONArray entries = new JSONArray();
        for (String location : locations) try {
            Document doc = xml(request(location, "GET", null, Collections.emptyMap(), 2 * 1024 * 1024));
            NodeList services = doc.getElementsByTagNameNS("*", "service");
            for (int i = 0; i < services.getLength(); i++) {
                Element service = (Element) services.item(i);
                if (!value(service, "serviceType").contains("ContentDirectory")) continue;
                String control = HttpUrl.get(location).resolve(value(service, "controlURL")).toString();
                entries.put(json("title", value(doc.getDocumentElement(), "friendlyName"), "url", control, "service", value(service, "serviceType"), "object", "0", "folder", true));
            }
        } catch (Exception ignored) {}
        return json("entries", entries);
    }

    static JSONObject dlna(JSONObject data) throws Exception {
        String url = data.getString("url"), service = data.getString("service"), object = data.optString("object", "0");
        String safe = object.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;");
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:Browse xmlns:u=\"" + service
            + "\"><ObjectID>" + safe + "</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>" + data.optInt("start", 0) + "</StartingIndex><RequestedCount>200</RequestedCount><SortCriteria></SortCriteria></u:Browse></s:Body></s:Envelope>";
        Map<String, String> headers = new HashMap<>(); headers.put("SOAPACTION", "\"" + service + "#Browse\"");
        Document reply = xml(request(url, "POST", body, headers, 4 * 1024 * 1024));
        String result = value(reply.getDocumentElement(), "Result");
        Document didl = xml(result.getBytes("UTF-8")); JSONArray entries = new JSONArray();
        NodeList nodes = didl.getDocumentElement().getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element)) continue;
            Element element = (Element) nodes.item(i); boolean folder = element.getLocalName().equals("container");
            String media = value(element, "res");
            if (!folder && !value(element, "class").contains("audioItem")) continue;
            entries.put(json("title", value(element, "title"), "folder", folder, "object", element.getAttribute("id"),
                "url", folder ? url : media, "service", service));
        }
        return json("entries", entries, "total", value(reply.getDocumentElement(), "TotalMatches"));
    }

    static JSONObject autoeq(JSONObject data) throws Exception {
        if (data.has("profile")) {
            String profile = data.getString("profile");
            if (!profile.startsWith(AUTOEQ)) throw new IOException("不是 AutoEQ 参数地址");
            JSONArray bands = PeqPresets.parse(new String(request(profile, "GET", null, Collections.emptyMap(), 1024 * 1024), "UTF-8"));
            return json("peq", bands);
        }
        File index = new File(PlayerApp.instance.getCacheDir(), "autoeq-index.md");
        if (!index.isFile() || System.currentTimeMillis() - index.lastModified() > 7 * 86400000L) {
            byte[] bytes = request(AUTOEQ + "INDEX.md", "GET", null, Collections.emptyMap(), 3 * 1024 * 1024);
            try (FileOutputStream output = new FileOutputStream(index)) { output.write(bytes); }
        }
        String query = data.optString("query").trim().toLowerCase(Locale.ROOT);
        if (query.length() < 2) throw new IOException("请输入至少两个字符的耳机型号");
        JSONArray entries = new JSONArray();
        for (String line : new String(MusicLibrary.read(new FileInputStream(index), 3 * 1024 * 1024), "UTF-8").split("\\n")) {
            int at = line.indexOf("]("), end = line.lastIndexOf(") by ");
            if (!line.startsWith("- [") || at < 0 || end < at || !line.toLowerCase(Locale.ROOT).contains(query)) continue;
            String folder = line.substring(at + 2, end).replaceFirst("^\\./", "");
            entries.put(json("title", line.substring(3, at) + line.substring(end + 1), "profile", AUTOEQ + folder + "/" + folder.substring(folder.lastIndexOf('/') + 1) + "%20ParametricEQ.txt"));
            if (entries.length() == 100) break;
        }
        return json("entries", entries);
    }

    static void handle(MainActivity activity, JSONObject data) {
        PlayerApp app = PlayerApp.instance;
        app.jobs.execute(() -> {
            JSONObject result;
            try {
                switch (data.getString("operation")) {
                    case "webdav": result = webdav(data); break;
                    case "discover": result = discover(activity); break;
                    case "dlna": result = dlna(data); break;
                    case "autoeq":
                        result = autoeq(data);
                        if (result.has("peq")) {
                            JSONArray bands = result.getJSONArray("peq");
                            app.main.post(() -> { try { app.playback.configure(json("peq", bands, "eq", true)); } catch (Exception error) { activity.toast(error.getMessage()); } });
                        }
                        break;
                    case "lyrics": {
                        String id = data.getString("id"); if (app.library.track(id) == null) throw new IOException("歌曲不在曲库");
                        byte[] bytes = request(data.getString("url"), "GET", null, Collections.emptyMap(), 2 * 1024 * 1024);
                        try (FileOutputStream output = new FileOutputStream(new File(app.library.lyrics, id + ".lrc"))) { output.write(bytes); }
                        result = json("message", "歌词已保存"); break;
                    }
                    case "play": {
                        JSONObject track = app.library.importNetwork(data.getString("url"), data.optString("title", "网络音乐"), data.optDouble("duration", 0));
                        app.main.post(() -> { activity.refresh(); app.playback.load(track.optString("id"), true); });
                        result = json("message", "已加入曲库并开始播放"); break;
                    }
                    default: throw new IOException("未知网络操作");
                }
            } catch (Exception error) { result = json("error", error.getMessage()); }
            final JSONObject reply = result;
            app.main.post(() -> { if (!activity.isFinishing() && !activity.isDestroyed()) activity.web.evaluateJavascript("window.dispatchEvent(new CustomEvent('m3x-native-network',{detail:" + reply.toString() + "}))", null); });
        });
    }
}
