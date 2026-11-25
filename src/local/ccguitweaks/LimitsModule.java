package local.ccguitweaks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Модуль «Лимиты Claude»: сколько израсходовано из 5-часового окна и недельных лимитов.
 *
 * Те же данные, что показывает /usage в Claude Code: GET api.anthropic.com/api/oauth/usage с OAuth-токеном
 * из ~/.claude/.credentials.json (его обновляет сам Claude Code). Токен читается при каждом запросе,
 * уходит только в этот запрос и нигде не сохраняется.
 *
 * Смена аккаунта (/logout, /login): раз в 5 с проверяем, не изменился ли файл входа. Если токен другой —
 * сбрасываем паузу после 429 (её заработал прежний вход), убираем чужие цифры и спрашиваем сразу.
 */
final class LimitsModule {
  private static final String URL = "https://api.anthropic.com/api/oauth/usage";
  private static final Object FETCH = new Object();

  private static ScheduledFuture<?> task;
  private static int interval;
  private static volatile boolean on;
  private static volatile String last = "null";
  private static volatile long lastFetch;
  /** До какого момента сервер просил не спрашивать (Retry-After при 429); 0 — без ограничений. */
  private static volatile long retryAt = loadRetryAt();
  /** Сколько 429 подряд — пауза растёт: 1, 2, 4, 8, 15 мин (или дольше, если сервер попросит). */
  private static int limited;
  private static final String RETRY_KEY = "ccGuiTweaks.limitsRetryAt";
  /** Обновление по кнопке, отложенное из-за паузы между запросами. */
  private static ScheduledFuture<?> manual;
  /** Слежение за файлом входа Claude Code. */
  private static ScheduledFuture<?> watcher;
  private static volatile long credStamp = -1;
  /** Отпечаток токена, которым получены цифры в {@link #last} (сам токен не храним); 0 — неизвестно. */
  private static volatile int dataToken;
  /** Отпечаток токена последнего запроса. */
  private static volatile int seenToken;

  private LimitsModule() {}

  static synchronized void apply() {
    boolean want = Tweaks.running() && Settings.bool("limits", "enabled");
    int iv = Math.max(60, Math.min(3600, Settings.integer("limits", "interval")));
    if (want == on && (!want || iv == interval)) return;
    cancel();
    if (want) {
      boolean wasOn = on;
      on = true;
      interval = iv;
      long sinceLast = (System.currentTimeMillis() - lastFetch) / 1000;
      long delay = wasOn ? Math.max(0, iv - sinceLast) : 0;
      // После перезагрузки плагина не стучимся сразу, если сервер ещё просит подождать.
      delay = Math.max(delay, (retryAt - System.currentTimeMillis() + 999) / 1000);
      task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(LimitsModule::fetch, delay, iv, TimeUnit.SECONDS);
      if (watcher == null) {
        credStamp = stamp();
        watcher = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(LimitsModule::watch, 5, 5, TimeUnit.SECONDS);
      }
      if (!wasOn) Tweaks.LOG.info(Tweaks.P + "лимиты включены");
    } else {
      on = false;
      cancel();
      last = "null";
      dataToken = 0;
      Tweaks.broadcast("window.__cct&&__cct.limits(null)");
      Tweaks.LOG.info(Tweaks.P + "лимиты выключены");
    }
  }

  static synchronized void stop() {
    on = false;
    cancel();
  }

  private static void cancel() {
    if (task != null) task.cancel(false);
    task = null;
    if (!on && watcher != null) { watcher.cancel(false); watcher = null; }
  }

  /** Файл входа изменился и токен в нём другой — значит, вошли заново (возможно, другим аккаунтом). */
  private static void watch() {
    long st = stamp();
    if (st == credStamp) return;
    credStamp = st;
    Cred c = cred();
    int id = c == null ? 0 : c.token.hashCode();
    if (!on || id == seenToken) return;
    synchronized (LimitsModule.class) {
      limited = 0;
      if (retryAt != 0) setRetryAt(0);
      if (manual != null) manual.cancel(false);
      manual = null;
    }
    Tweaks.LOG.info(Tweaks.P + "лимиты: вход Claude Code сменился" + (c == null ? " (вышли)" : ", план " + c.plan));
    AppExecutorUtil.getAppExecutorService().execute(() -> fetch(true));
  }

  /** Время изменения и размер файла входа; -1 — файла нет. */
  private static long stamp() {
    try {
      Path f = credFile();
      return Files.isRegularFile(f) ? Files.getLastModifiedTime(f).toMillis() * 31 + Files.size(f) : -1;
    } catch (Exception e) {
      return -1;
    }
  }

  static void onReady(JBCefBrowserBase b) {
    Tweaks.exec(b, "window.__cct&&__cct.limits(" + (on ? last : "null") + ")");
  }

  /**
   * Кнопка «Обновить»: запрос не теряется — если с прошлого прошло меньше 15 с или сервер просил подождать,
   * он откладывается до конца паузы. Повторные нажатия, пока запрос ждёт, ничего не добавляют.
   */
  static synchronized void refreshSoon() {
    if (!on || (manual != null && !manual.isDone())) return;
    long now = System.currentTimeMillis();
    long delay = Math.max(0, Math.max(lastFetch + 15_000 - now, retryAt - now));
    manual = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> fetch(true), delay, TimeUnit.MILLISECONDS);
  }

  private static void fetch() {
    fetch(false);
  }

  private static void fetch(boolean byUser) {
    synchronized (FETCH) {
      HttpClient client = Tweaks.http;
      if (!on || client == null) return;
      // Плановое обновление не спорит с ограничением сервера — иначе пауза только продлевается.
      if (!byUser && System.currentTimeMillis() < retryAt) return;
      lastFetch = System.currentTimeMillis();
      JsonObject out = new JsonObject();
      String error = null;
      Cred c = cred();
      int id = c == null ? 0 : c.token.hashCode();
      seenToken = id;
      try {
        if (c == null) {
          error = "nologin";
        } else {
          String token = c.token;
          HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(URL)).timeout(Duration.ofSeconds(15))
              .header("Authorization", "Bearer " + token)
              .header("anthropic-beta", "oauth-2025-04-20")
              .header("Accept", "application/json")
              .header("User-Agent", "cc-gui-tweaks")
              .GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
          if (r.statusCode() == 401 || r.statusCode() == 403) {
            error = "auth";
          } else if (r.statusCode() == 429) {
            error = "http 429";
            String header = r.headers().firstValue("retry-after").orElse(null);
            limited++;
            // Сервер присылает Retry-After: 0 — верить нельзя, поэтому не меньше своей нарастающей паузы.
            long wait = Math.max(header != null ? seconds(header) : 0, Math.min(900, 60L << Math.min(4, limited - 1)));
            setRetryAt(lastFetch + Math.max(15, Math.min(3600, wait)) * 1000);
            Tweaks.LOG.info(Tweaks.P + "лимиты: 429 №" + limited + ", Retry-After=" + header + ", следующая попытка через " + wait + " с");
          } else if (r.statusCode() != 200) {
            error = "http " + r.statusCode();
          } else {
            JsonObject j = JsonParser.parseString(r.body()).getAsJsonObject();
            for (String k : new String[] {"limits", "five_hour", "seven_day"}) {
              JsonElement v = j.get(k);
              if (v != null && !v.isJsonNull()) out.add(k, v);
            }
          }
        }
      } catch (Exception e) {
        error = "network";
        Tweaks.LOG.info(Tweaks.P + "лимиты: " + e);
      }
      if (!on) return;
      if (error != null) {
        // Сеть или сервер подвели — показываем прежние цифры с пометкой; без входа — честно пусто.
        // Цифры другого входа (сменили аккаунт) не показываем.
        JsonObject prev = id == dataToken ? parse(last) : null;
        if (prev != null && !error.equals("nologin") && !error.equals("auth")) {
          prev.addProperty("stale", true);
          out = prev;
        }
        out.addProperty("error", error);
        if (retryAt > System.currentTimeMillis()) out.addProperty("retry", retryAt);
        if (!error.equals("network")) Tweaks.LOG.info(Tweaks.P + "лимиты: " + error);
      } else {
        limited = 0;
        if (retryAt != 0) setRetryAt(0);
        out.addProperty("at", lastFetch);
        dataToken = id;
      }
      if (c != null && c.plan != null) out.addProperty("plan", c.plan);
      last = out.toString();
      Tweaks.broadcast("window.__cct&&__cct.limits(" + last + ")");
    }
  }

  private static long loadRetryAt() {
    try {
      return Long.parseLong(PropertiesComponent.getInstance().getValue(RETRY_KEY, "0"));
    } catch (Exception e) {
      return 0;
    }
  }

  private static void setRetryAt(long t) {
    retryAt = t;
    PropertiesComponent.getInstance().setValue(RETRY_KEY, t == 0 ? null : Long.toString(t));
  }

  /** Retry-After в секундах; дату в этом заголовке не разбираем — тогда минута. */
  private static long seconds(String v) {
    try {
      return Long.parseLong(v.trim());
    } catch (NumberFormatException e) {
      return 60;
    }
  }

  private static JsonObject parse(String json) {
    try {
      JsonElement e = JsonParser.parseString(json);
      return e.isJsonObject() ? e.getAsJsonObject() : null;
    } catch (Exception e) {
      return null;
    }
  }

  private record Cred(String token, String plan) {}

  private static Path credFile() {
    String dir = System.getenv("CLAUDE_CONFIG_DIR");
    Path base = dir != null && !dir.isBlank() ? Path.of(dir) : Path.of(System.getProperty("user.home"), ".claude");
    return base.resolve(".credentials.json");
  }

  private static Cred cred() {
    try {
      Path file = credFile();
      if (!Files.isRegularFile(file)) return null;
      JsonObject oauth = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("claudeAiOauth");
      if (oauth == null || !oauth.has("accessToken")) return null;
      String t = oauth.get("accessToken").getAsString();
      return t.isBlank() ? null : new Cred(t, plan(str(oauth, "subscriptionType"), str(oauth, "rateLimitTier")));
    } catch (Exception e) {
      return null;
    }
  }

  private static String str(JsonObject o, String k) {
    JsonElement v = o.get(k);
    return v != null && v.isJsonPrimitive() ? v.getAsString() : "";
  }

  /** «max» + «default_claude_max_20x» → «Max 20x»; «pro» → «Pro». */
  static String plan(String type, String tier) {
    if (type.isBlank()) return null;
    String p = Character.toUpperCase(type.charAt(0)) + type.substring(1).toLowerCase();
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("_(\\d+x)$").matcher(tier);
    return m.find() ? p + " " + m.group(1) : p;
  }
}
