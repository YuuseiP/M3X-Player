package cn.yuusei.m3x;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.regex.*;
import static cn.yuusei.m3x.PlayerApp.json;

final class PeqPresets {
    static JSONArray parse(String text) throws Exception {
        JSONArray bands = new JSONArray();
        Pattern pattern = Pattern.compile("Filter\\s+\\d+\\s*:\\s*(ON|OFF)\\s+(PK|LSC?|HSC?|LP|HP)\\s+Fc\\s+([\\d.]+)\\s+Hz(?:\\s+Gain\\s+([+-]?[\\d.]+)\\s+dB)?\\s+Q\\s+([\\d.]+)", Pattern.CASE_INSENSITIVE);
        for (String line : text.split("\\r?\\n")) {
            Matcher matcher = pattern.matcher(line);
            if (!matcher.find()) continue;
            String type = matcher.group(2).toUpperCase(java.util.Locale.ROOT).replace("LSC", "LS").replace("HSC", "HS");
            double frequency = Double.parseDouble(matcher.group(3)), gain = matcher.group(4) == null ? 0 : Double.parseDouble(matcher.group(4)), q = Double.parseDouble(matcher.group(5));
            new DspEngine.Band(type, frequency, gain, q);
            if (bands.length() == 32) throw new IllegalArgumentException("最多支持 32 个滤波器");
            bands.put(json("type", type, "frequency", frequency, "gain", gain, "q", q, "enabled", matcher.group(1).equalsIgnoreCase("ON")));
        }
        if (bands.length() == 0) throw new IllegalArgumentException("未找到有效的 AutoEQ / Equalizer APO 参数滤波器");
        return bands;
    }
    static String export(JSONArray bands) {
        StringBuilder text = new StringBuilder("# M3X Player parametric EQ\n");
        for (int i = 0; i < bands.length(); i++) {
            JSONObject band = bands.optJSONObject(i); if (band == null) continue;
            text.append(String.format(java.util.Locale.ROOT, "Filter %d: %s %s Fc %.2f Hz Gain %.2f dB Q %.3f\n",
                i + 1, band.optBoolean("enabled", true) ? "ON" : "OFF", band.optString("type", "PK"), band.optDouble("frequency"), band.optDouble("gain"), band.optDouble("q", 1.4)));
        }
        return text.toString();
    }
}
