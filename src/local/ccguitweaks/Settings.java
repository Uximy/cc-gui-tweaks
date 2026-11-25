package local.ccguitweaks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.ide.util.PropertiesComponent;

import java.util.Map;

/**
 * Настройки модулей. Хранятся в PropertiesComponent уровня приложения (options/other.xml),
 * поэтому переживают перезапуск IDE. По умолчанию всё выключено.
 *
 * Страница настроек присылает объект целиком; из него берутся только известные ключи нужного типа
 * (строки — только из латиницы и цифр: это Client ID).
 */
final class Settings {
  private static final String KEY = "ccGuiTweaks.settings";
  private static volatile JsonObject current = defaults();

  private Settings() {}

  private static JsonObject defaults() {
    JsonObject spotify = new JsonObject();
    spotify.addProperty("enabled", false);
    spotify.addProperty("time", true);
    spotify.addProperty("volume", true);
    spotify.addProperty("queue", false);
    spotify.addProperty("clientId", "");
    spotify.addProperty("size", 1);          // 1 — обычный плеер, 2 — крупный
    spotify.addProperty("lyrics", false);    // кнопка «Текст песни» (LRCLIB)
    spotify.addProperty("collapsed", false); // плеер свёрнут в кнопку со значком Spotify

    JsonObject limits = new JsonObject();
    limits.addProperty("enabled", false);
    limits.addProperty("models", true);
    limits.addProperty("resets", true);
    limits.addProperty("interval", 60);

    JsonObject perf = new JsonObject();
    perf.addProperty("enabled", false);
    perf.addProperty("anim", true);
    perf.addProperty("blur", true);
    perf.addProperty("journal", false);      // журнал лагов (PerfJournal), не зависит от enabled

    JsonObject all = new JsonObject();
    all.add("spotify", spotify);
    all.add("limits", limits);
    all.add("perf", perf);
    return all;
  }

  static void load() {
    JsonObject merged = defaults();
    String saved = PropertiesComponent.getInstance().getValue(KEY);
    if (saved != null) {
      try {
        merge(merged, JsonParser.parseString(saved).getAsJsonObject());
      } catch (Exception e) {
        Tweaks.LOG.info(Tweaks.P + "сохранённые настройки не читаются, беру по умолчанию: " + e);
      }
    }
    current = merged;
  }

  /** Принимает настройки со страницы. Возвращает false, если пришла ерунда. */
  static synchronized boolean update(String json) {
    JsonObject in;
    try {
      in = JsonParser.parseString(json).getAsJsonObject();
    } catch (Exception e) {
      return false;
    }
    JsonObject merged = current.deepCopy();
    merge(merged, in);
    current = merged;
    PropertiesComponent.getInstance().setValue(KEY, merged.toString());
    return true;
  }

  private static void merge(JsonObject target, JsonObject in) {
    for (Map.Entry<String, JsonElement> module : target.entrySet()) {
      JsonElement src = in.get(module.getKey());
      if (src == null || !src.isJsonObject()) continue;
      JsonObject dst = module.getValue().getAsJsonObject();
      for (Map.Entry<String, JsonElement> e : dst.entrySet()) {
        JsonElement v = src.getAsJsonObject().get(e.getKey());
        if (v == null || !v.isJsonPrimitive()) continue;
        JsonPrimitive def = e.getValue().getAsJsonPrimitive(), p = v.getAsJsonPrimitive();
        if (def.isBoolean() && p.isBoolean()) e.setValue(p);
        else if (def.isNumber() && p.isNumber()) e.setValue(new JsonPrimitive(p.getAsInt()));
        else if (def.isString() && p.isString()) {
          String str = p.getAsString().trim();
          if (str.length() <= 64 && str.matches("[A-Za-z0-9]*")) e.setValue(new JsonPrimitive(str));
        }
      }
    }
  }

  static boolean bool(String module, String key) {
    return current.getAsJsonObject(module).get(key).getAsBoolean();
  }

  static String string(String module, String key) {
    return current.getAsJsonObject(module).get(key).getAsString();
  }

  static int integer(String module, String key) {
    return current.getAsJsonObject(module).get(key).getAsInt();
  }

  /** То, что уходит на страницу: настройки и версия плагина. */
  static String forPage() {
    JsonObject out = current.deepCopy();
    JsonObject meta = new JsonObject();
    meta.addProperty("version", Updater.currentVersion());
    out.add("meta", meta);
    return out.toString();
  }
}
