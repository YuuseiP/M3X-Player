package cn.yuusei.m3x;

import okhttp3.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Direct platform requests. No Python service, account cookies, audio upload or LDDC code. */
final class LyricsProviders {
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(22, TimeUnit.SECONDS).followRedirects(false).build();
    private final Map<String, Long> retryAt = new HashMap<>(), last = new HashMap<>();
    private static final String QQ = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String NE = "https://music.163.com";
    synchronized JSONObject request(String provider, HttpUrl url, JSONObject body) throws Exception {
        long now = System.currentTimeMillis();
        if (retryAt.getOrDefault(provider, 0L) > now) throw new IOException("该来源限流，请稍后重试");
        long wait = 750 - (now - last.getOrDefault(provider, 0L));
        if (wait > 0) Thread.sleep(wait);
        Request.Builder request = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 M3X-Player/0.5")
            .header("Referer", provider.equals("QQ") ? "https://y.qq.com/" : NE + "/");
        if (body != null) request.post(RequestBody.create(body.toString(), MediaType.parse("application/json; charset=utf-8")));
        last.put(provider, System.currentTimeMillis());
        try (Response response = http.newCall(request.build()).execute()) {
            if (response.code() == 429) {
                long seconds = 120;
                try { seconds = Long.parseLong(response.header("Retry-After", "120")); }
                catch (NumberFormatException ignored) { /* Non-numeric retry headers still receive conservative backoff. */ }
                retryAt.put(provider, System.currentTimeMillis() + Math.min(86400, Math.max(1, seconds)) * 1000);
                throw new IOException("该来源请求过多，已暂停查询");
            }
            if (!response.isSuccessful()) throw new IOException("来源返回 HTTP " + response.code());
            if (response.body() == null) throw new IOException("来源返回空内容");
            byte[] bytes = MusicLibrary.read(response.body().byteStream(), 2 * 1024 * 1024);
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        }
    }
    private static HttpUrl url(String base, String... params) {
        HttpUrl.Builder b = Objects.requireNonNull(HttpUrl.parse(base)).newBuilder();
        for (int i = 0; i < params.length; i += 2) b.addQueryParameter(params[i], params[i + 1]);
        return b.build();
    }
    static void provider(String value) throws IOException {
        if (!value.equals("QQ") && !value.equals("NE")) throw new IOException("未知歌词来源");
    }
    JSONArray search(String provider, String query) throws Exception {
        provider(provider);
        query = query.trim(); if (query.isEmpty() || query.length() > 250) throw new IOException("请输入 1～250 字的搜索词");
        boolean qq = provider.equals("QQ");
        JSONObject data = request(provider, qq ? url("https://c.y.qq.com/soso/fcgi-bin/client_search_cp", "w", query, "format", "json", "p", "1", "n", "20")
            : url(NE + "/api/search/get/web", "s", query, "type", "1", "limit", "20"), null);
        return searchResults(provider, data);
    }
    static JSONArray searchResults(String provider, JSONObject data) throws Exception {
        boolean qq = provider.equals("QQ");
        if (data.optInt("code", -1) != (qq ? 0 : 200)) throw new IOException("来源搜索失败或需要登录");
        JSONArray rows;
        if (qq) rows = data.getJSONObject("data").getJSONObject("song").optJSONArray("list");
        else rows = data.optJSONObject("result") == null ? null : data.getJSONObject("result").optJSONArray("songs");
        JSONArray result = new JSONArray();
        if (rows == null) return result;
        for (int i = 0; i < Math.min(20, rows.length()); i++) {
            JSONObject row = rows.getJSONObject(i), item = new JSONObject();
            JSONArray artists = row.optJSONArray(qq ? "singer" : "artists");
            List<String> names = new ArrayList<>();
            if (artists != null) for (int n = 0; n < artists.length(); n++) names.add(artists.getJSONObject(n).optString("name"));
            item.put("provider", provider).put("songId", row.get(qq ? "songid" : "id").toString())
                .put("mid", row.optString("songmid")).put("title", row.optString(qq ? "songname" : "name"))
                .put("artist", String.join(" / ", names)).put("album", qq ? row.optString("albumname") : row.getJSONObject("album").optString("name"))
                .put("durationMs", qq ? row.optLong("interval") * 1000 : row.optLong("duration"));
            result.put(item);
        }
        return result;
    }
    JSONObject fetch(JSONObject candidate) throws Exception {
        String source = candidate.getString("provider"), id = candidate.getString("songId"); provider(source);
        if (!id.matches("[0-9]{1,20}")) throw new IOException("歌曲来源编号无效");
        JSONObject data;
        if (source.equals("QQ")) {
            // The unencrypted, line-synchronised response is base64, not QRC cipher text.
            JSONObject p = new JSONObject().put("songID", Long.parseLong(id)).put("songName", b64(candidate.optString("title")))
                .put("singerName", b64(candidate.optString("artist"))).put("albumName", b64(candidate.optString("album")))
                .put("interval", candidate.optLong("durationMs") / 1000).put("crypt", 0).put("ct", 19).put("cv", 2111)
                .put("lrc_t", 0).put("qrc", 0).put("qrc_t", 0).put("roma", 0).put("roma_t", 0).put("trans", 1).put("trans_t", 0).put("type", 0);
            JSONObject body = new JSONObject().put("comm", new JSONObject().put("ct", 19).put("cv", 2111))
                .put("request", new JSONObject().put("method", "GetPlayLyricInfo").put("module", "music.musichallSong.PlayLyricInfo").put("param", p));
            data = request(source, HttpUrl.parse(QQ), body);
        } else data = request(source, url(NE + "/api/song/lyric", "id", id, "lv", "-1", "tv", "-1", "rv", "-1"), null);
        return lyricsResult(candidate, data);
    }
    static JSONObject lyricsResult(JSONObject candidate, JSONObject data) throws Exception {
        JSONObject doc = new JSONObject(candidate.toString());
        if (candidate.getString("provider").equals("QQ")) {
            if (data.optInt("code", -1) != 0 || data.getJSONObject("request").optInt("code", -1) != 0) throw new IOException("QQ 歌词接口暂不可用");
            JSONObject row = data.getJSONObject("request").getJSONObject("data");
            doc.put("original", unb64(row.optString("lyric"))).put("translation", unb64(row.optString("trans")));
        } else {
            // This endpoint may omit code on a successful response; the lrc object is still required.
            if (data.has("code") && data.optInt("code") != 200) throw new IOException("网易云歌词接口暂不可用");
            if (!data.has("lrc")) throw new IOException("该发行条目没有歌词");
            doc.put("original", data.getJSONObject("lrc").optString("lyric"));
            doc.put("translation", data.optJSONObject("tlyric") == null ? "" : data.getJSONObject("tlyric").optString("lyric"));
        }
        doc.put("sourceTitle", candidate.optString("title")).put("sourceArtist", candidate.optString("artist")).put("sourceAlbum", candidate.optString("album"));
        doc.put("offsetMs", 0).put("note", "").put("fetchedAt", System.currentTimeMillis());
        LyricsDocument.validate(doc); return doc;
    }
    private static String b64(String text) { return java.util.Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)); }
    private static String unb64(String text) throws IOException {
        if (text.isEmpty()) return "";
        try { return new String(java.util.Base64.getDecoder().decode(text), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException error) { throw new IOException("来源歌词编码发生变化", error); }
    }
}
