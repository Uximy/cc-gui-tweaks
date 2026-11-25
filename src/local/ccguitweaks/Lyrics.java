package local.ccguitweaks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Текст песни для плеера Spotify (настройка spotify.lyrics).
 *
 * У Spotify Web API текстов нет, поэтому берём их из открытой базы LRCLIB (lrclib.net, без ключа):
 * синхронный LRC, если есть, иначе простой текст. На сервис уходят только название, исполнитель,
 * альбом и длительность трека — и только когда открыта панель текста. Ответы кешируются.
 *
 * Страница шлёт {@code lyrics:{"t":..,"a":..,"al":..,"d":..}}, ответ приходит вызовом {@code __cct.lyrics(...)}.
 */
final class Lyrics {
  private static final String API = "https://lrclib.net/api/";
  private static final String UA = "cc-gui-tweaks (JetBrains plugin)";
  private static final Pattern TAG = Pattern.compile("^\\[(\\d{1,3}):(\\d{1,2}(?:[.:]\\d{1,3})?)]");
  private static final ExecutorService POOL = AppExecutorUtil.createBoundedApplicationPoolExecutor("CC GUI Tweaks Lyrics", 1);
  /** Последние ответы: ключ «название|исполнитель|секунды». Сетевые ошибки не кешируются. */
  private static final Map<String, JsonObject> CACHE = new LinkedHashMap<>(16, .75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<String, JsonObject> e) {
      return size() > 40;
    }
  };

  private Lyrics() {}

  static void request(JBCefBrowserBase b, String json) {
    if (!Settings.bool("spotify", "enabled") || !Settings.bool("spotify", "lyrics")) return;
    String t, a, al;
    int d;
    try {
      JsonObject q = JsonParser.parseString(json).getAsJsonObject();
      t = str(q, "t");
      a = str(q, "a");
      al = str(q, "al");
      d = q.has("d") && q.get("d").isJsonPrimitive() ? (int) Math.round(q.get("d").getAsDouble()) : 0;
    } catch (Exception e) {
      return;
    }
    if (t.isEmpty()) return;
    POOL.execute(() -> {
      String key = t + "|" + a + "|" + d;
      JsonObject res;
      synchronized (CACHE) { res = CACHE.get(key); }
      if (res == null) {
        res = fetch(t, a, al, d);
        if (!res.has("err")) synchronized (CACHE) { CACHE.put(key, res); }
      }
      JsonObject out = res.deepCopy();
      out.addProperty("t", t);
      out.addProperty("a", a);
      Tweaks.exec(b, "window.__cct&&__cct.lyrics&&__cct.lyrics(" + out + ")");
    });
  }

  private static String str(JsonObject o, String k) {
    JsonElement e = o.get(k);
    String s = e != null && e.isJsonPrimitive() ? e.getAsString().trim() : "";
    return s.length() > 300 ? s.substring(0, 300) : s;
  }

  // ---------- LRCLIB (фоновый поток) ----------

  private static JsonObject fetch(String t, String a, String al, int d) {
    HttpClient client = Tweaks.http;
    JsonObject res = new JsonObject();
    if (client == null) { res.addProperty("err", "net"); return res; }
    try {
      // 1) точное совпадение по названию, исполнителю, альбому и длительности
      StringBuilder get = new StringBuilder(API + "get?track_name=").append(enc(t)).append("&artist_name=").append(enc(a));
      if (!al.isEmpty()) get.append("&album_name=").append(enc(al));
      if (d > 0) get.append("&duration=").append(d);
      HttpResponse<String> r = send(client, get.toString());
      if (r.statusCode() == 200) {
        JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
        if (hasText(o)) return toResult(o);
      } else if (r.statusCode() != 404) {
        res.addProperty("err", "http " + r.statusCode());
        return res;
      }
      // 2) поиск: сначала как есть, затем без «- Remastered», «(feat. …)» и т. п.
      JsonObject best = search(client, t, a, d);
      String clean = cleanTitle(t);
      if (best == null && !clean.equals(t)) best = search(client, clean, a, d);
      if (best != null) return toResult(best);
      res.addProperty("found", false);
      return res;
    } catch (Exception e) {
      Tweaks.LOG.info(Tweaks.P + "текст песни: " + e);
      res.addProperty("err", "net");
      return res;
    }
  }

  private static JsonObject search(HttpClient client, String t, String a, int d) throws Exception {
    HttpResponse<String> r = send(client, API + "search?track_name=" + enc(t) + (a.isEmpty() ? "" : "&artist_name=" + enc(a)));
    if (r.statusCode() != 200) return null;
    JsonArray arr = JsonParser.parseString(r.body()).getAsJsonArray();
    JsonObject best = null;
    int bestScore = -1;
    for (JsonElement e : arr) {
      if (!e.isJsonObject()) continue;
      JsonObject o = e.getAsJsonObject();
      if (!hasText(o)) continue;
      double dur = o.has("duration") && o.get("duration").isJsonPrimitive() ? o.get("duration").getAsDouble() : 0;
      double diff = d > 0 && dur > 0 ? Math.abs(dur - d) : 0;
      if (diff > 8) continue;   // другая версия трека — синхронизация разъедется
      int score = (nonEmpty(o, "syncedLyrics") ? 2 : 0) + (diff <= 2 ? 1 : 0);
      if (score > bestScore) { bestScore = score; best = o; }
    }
    return best;
  }

  private static HttpResponse<String> send(HttpClient client, String url) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("User-Agent", UA).GET().build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static String cleanTitle(String t) {
    String s = t.replaceAll("(?i)\\s*[(\\[](?:feat|ft|with|prod)\\.?\\s[^)\\]]*[)\\]]", "")
        .replaceAll("(?i)\\s+-\\s+.*(?:remaster|version|edit|mix|live|mono|stereo|single|deluxe).*$", "");
    s = s.replaceAll("(?i)\\s*[(\\[][^)\\]]*(?:remaster|version|edit|live|mono|stereo|deluxe)[^)\\]]*[)\\]]", "").trim();
    return s.isEmpty() ? t : s;
  }

  private static boolean nonEmpty(JsonObject o, String k) {
    JsonElement e = o.get(k);
    return e != null && e.isJsonPrimitive() && !e.getAsString().isBlank();
  }

  private static boolean hasText(JsonObject o) {
    return nonEmpty(o, "syncedLyrics") || nonEmpty(o, "plainLyrics") || (o.has("instrumental") && o.get("instrumental").getAsBoolean());
  }

  private static JsonObject toResult(JsonObject o) {
    JsonObject res = new JsonObject();
    res.addProperty("found", true);
    if (o.has("instrumental") && o.get("instrumental").getAsBoolean()) {
      res.addProperty("inst", true);
      return res;
    }
    if (nonEmpty(o, "syncedLyrics")) {
      JsonArray lines = parseLrc(o.get("syncedLyrics").getAsString());
      if (!lines.isEmpty()) { res.add("synced", lines); return res; }
    }
    if (nonEmpty(o, "plainLyrics")) res.addProperty("plain", o.get("plainLyrics").getAsString());
    else res.addProperty("found", false);
    return res;
  }

  /** LRC → [[мс, "строка"], …] по возрастанию времени; у строки может быть несколько меток. */
  static JsonArray parseLrc(String lrc) {
    List<long[]> times = new ArrayList<>();
    List<String> texts = new ArrayList<>();
    for (String raw : lrc.split("\\r?\\n")) {
      String line = raw.trim();
      List<Long> ts = new ArrayList<>();
      Matcher m;
      while ((m = TAG.matcher(line)).find()) {
        String sec = m.group(2).replace(':', '.');
        ts.add(Long.parseLong(m.group(1)) * 60_000 + Math.round(Double.parseDouble(sec) * 1000));
        line = line.substring(m.end()).trim();
      }
      for (long t : ts) { times.add(new long[]{t, texts.size()}); texts.add(line); }
    }
    times.sort((x, y) -> Long.compare(x[0], y[0]));
    JsonArray out = new JsonArray();
    for (long[] t : times) {
      JsonArray row = new JsonArray();
      row.add(t[0]);
      row.add(texts.get((int) t[1]));
      out.add(row);
    }
    return out;
  }
}
