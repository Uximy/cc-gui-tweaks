package local.ccguitweaks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.credentialStore.CredentialAttributes;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.SwingUtilities;
import java.awt.Desktop;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Очередь и плейлист текущего трека через Spotify Web API — системная медиа-сессия Windows их не отдаёт.
 *
 * Вход: OAuth 2.0 Authorization Code + PKCE с Client ID приложения, которое пользователь сам создаёт на
 * developer.spotify.com (секрет не нужен). Ответ Spotify принимает временный сервер на {@link #REDIRECT},
 * который живёт только пока идёт вход. Refresh-токен хранится в хранилище паролей IDE, access-токен — только
 * в памяти. Запросы уходят только на accounts.spotify.com и api.spotify.com; токены в лог не пишутся.
 */
final class SpotifyWeb {
  static final String REDIRECT = "http://127.0.0.1:43118/callback";
  private static final int PORT = 43118;
  private static final String SCOPES = "user-read-playback-state user-read-currently-playing user-modify-playback-state playlist-read-private playlist-read-collaborative";
  private static final String CONTROL_SCOPE = "user-modify-playback-state";
  private static final String API = "https://api.spotify.com/v1";
  private static final String ACCOUNTS = "https://accounts.spotify.com";
  private static final Pattern CLIENT_ID = Pattern.compile("[0-9a-fA-F]{32}");
  private static final Pattern PLAY = Pattern.compile("play:(\\d{1,2}):(spotify:\\S{1,300})");
  private static final Pattern OPENABLE = Pattern.compile("spotify:(playlist|album|artist|show|track|episode):[A-Za-z0-9]{10,40}|spotify:user:[^:\\s]{1,64}:collection");
  private static final ExecutorService EXEC = AppExecutorUtil.createBoundedApplicationPoolExecutor("CC GUI Tweaks Spotify Web", 1);
  private static final CredentialAttributes CRED = new CredentialAttributes(CredentialAttributesKt.generateServiceName("CC GUI Tweaks", "Spotify"));
  private static final SecureRandom RANDOM = new SecureRandom();

  private static volatile String access;
  private static volatile long accessUntil;
  private static volatile String refreshTok;
  private static volatile String refreshClient;
  private static volatile boolean credLoaded;
  private static volatile String user;
  private static volatile String error;
  /** Разрешения последнего токена; null — ещё не знаем. */
  private static volatile String scopes;
  /** Последняя показанная очередь — по ней проверяем, куда переключать. */
  private static volatile Snap snap;
  private static volatile String lastQueue;
  private static volatile long lastFetch;
  private static volatile long retryAfter;
  private static final AtomicBoolean fetchQueued = new AtomicBoolean();
  /** uri контекста → {name, img}: названия плейлистов и альбомов меняются редко. */
  private static final Map<String, JsonObject> contexts = new ConcurrentHashMap<>();

  private static HttpServer server;
  private static ScheduledFuture<?> serverTimeout;
  private static String state;
  private static String verifier;
  private static String loginClient;

  private SpotifyWeb() {}

  private record Snap(List<String> uris, String ctxType, String ctxUri, boolean shuffle, String device) {}

  /** Ошибка для страницы: код из {@code noclient|nologin|auth|forbidden|nodevice|ratelimit|http N}. */
  private static final class WebError extends Exception {
    final String code;

    WebError(String code) {
      super(code, null, false, false);
      this.code = code;
    }
  }

  // ---------- команды со страницы ----------

  static void command(String cmd) {
    switch (cmd) {
      case "login" -> EXEC.execute(SpotifyWeb::login);
      case "logout" -> EXEC.execute(() -> { forget(); error = null; pushStatus(); });
      case "queue" -> requestQueue();
      case "dashboard" -> BrowserUtil.browse("https://developer.spotify.com/dashboard");
      case "copy" -> SwingUtilities.invokeLater(() -> CopyPasteManager.getInstance().setContents(new StringSelection(REDIRECT)));
      default -> {
        Matcher m = PLAY.matcher(cmd);
        if (m.matches()) {
          int idx = Integer.parseInt(m.group(1));
          String uri = m.group(2);
          EXEC.execute(() -> play(idx, uri));
          return;
        }
        if (cmd.startsWith("open:") && OPENABLE.matcher(cmd.substring(5)).matches()) open(cmd.substring(5));
      }
    }
  }

  static void onReady(JBCefBrowserBase b) {
    EXEC.execute(() -> {
      loadCred();
      if (connected() && user == null) fetchUser();
      Tweaks.exec(b, "window.__cct&&__cct.spotifyWeb&&__cct.spotifyWeb(" + status() + ")");
      String q = lastQueue;
      if (q != null) Tweaks.exec(b, "window.__cct&&__cct.queue&&__cct.queue(" + q + ")");
    });
  }

  /** Сменился Client ID в настройках — прежний вход к нему не подходит. */
  static void onSettings() {
    EXEC.execute(SpotifyWeb::pushStatus);
  }

  static synchronized void stop() {
    stopServer();
    access = null;
    lastQueue = null;
  }

  private static String clientId() {
    String id = Settings.string("spotify", "clientId");
    return CLIENT_ID.matcher(id).matches() ? id : null;
  }

  private static boolean connected() {
    String id = clientId();
    return id != null && refreshTok != null && id.equalsIgnoreCase(refreshClient);
  }

  private static String status() {
    JsonObject o = new JsonObject();
    o.addProperty("client", clientId() != null);
    o.addProperty("connected", connected());
    o.addProperty("pending", server != null);
    o.addProperty("redirect", REDIRECT);
    if (user != null) o.addProperty("user", user);
    if (connected() && !canControl()) o.addProperty("scope", false);
    if (error != null) o.addProperty("error", error);
    return o.toString();
  }

  private static void pushStatus() {
    Tweaks.broadcast("window.__cct&&__cct.spotifyWeb&&__cct.spotifyWeb(" + status() + ")");
  }

  // ---------- вход (OAuth + PKCE) ----------

  private static void login() {
    String id = clientId();
    if (id == null) {
      error = "noclient";
      pushStatus();
      return;
    }
    stopServer();
    try {
      verifier = random(48);
      state = random(16);
      loginClient = id;
      String challenge = Base64.getUrlEncoder().withoutPadding()
          .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
      HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
      s.createContext("/callback", SpotifyWeb::callback);
      s.start();
      synchronized (SpotifyWeb.class) {
        server = s;
        serverTimeout = AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> {
          if (stopServer()) { error = "timeout"; pushStatus(); }
        }, 5, TimeUnit.MINUTES);
      }
      error = null;
      pushStatus();
      BrowserUtil.browse(ACCOUNTS + "/authorize?response_type=code&client_id=" + enc(id) + "&redirect_uri=" + enc(REDIRECT) +
          "&code_challenge_method=S256&code_challenge=" + challenge + "&state=" + state + "&scope=" + enc(SCOPES));
      Tweaks.LOG.info(Tweaks.P + "Spotify Web API: ждём вход в браузере");
    } catch (IOException e) {
      error = "port";
      Tweaks.LOG.info(Tweaks.P + "Spotify Web API: порт " + PORT + " занят: " + e);
      pushStatus();
    } catch (Exception e) {
      error = "network";
      Tweaks.LOG.warn(Tweaks.P + "Spotify Web API: вход", e);
      pushStatus();
    }
  }

  private static void callback(HttpExchange ex) throws IOException {
    Map<String, String> q = query(ex.getRequestURI().getRawQuery());
    String title, text;
    if (state == null || !state.equals(q.get("state"))) {
      title = "Ссылка устарела";
      text = "Начните подключение заново: настройки CC GUI → CC GUI Tweaks → Spotify.";
    } else if (q.containsKey("error")) {
      error = "denied".equals(q.get("error")) || "access_denied".equals(q.get("error")) ? "denied" : "oauth";
      title = "Вход отменён";
      text = "Spotify не дал доступ (" + q.get("error") + "). Можно попробовать ещё раз из настроек.";
    } else {
      String err = exchange(q.get("code"));
      if (err == null) {
        error = null;
        fetchUser();
        title = "Spotify подключён";
        text = "CC GUI Tweaks теперь показывает очередь и плейлист. Вкладку можно закрыть.";
        Tweaks.LOG.info(Tweaks.P + "Spotify Web API: подключено");
      } else {
        error = err;
        title = "Не получилось";
        text = "Spotify не принял вход (" + err + "). Проверьте Client ID и redirect URI в приложении и попробуйте ещё раз.";
        Tweaks.LOG.info(Tweaks.P + "Spotify Web API: обмен кода не удался: " + err);
      }
    }
    byte[] body = ("<!doctype html><meta charset=utf-8><title>CC GUI Tweaks</title>" +
        "<body style=\"font:15px system-ui,sans-serif;background:#121212;color:#eee;display:grid;place-items:center;height:90vh;margin:0\">" +
        "<div style=\"text-align:center;max-width:460px\"><h2 style=\"color:#1db954\">" + title + "</h2><p>" + text + "</p></div>")
        .getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
    ex.sendResponseHeaders(200, body.length);
    try (OutputStream out = ex.getResponseBody()) {
      out.write(body);
    }
    state = null;
    // Сервер гасим не из его же обработчика.
    AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> { stopServer(); pushStatus(); }, 500, TimeUnit.MILLISECONDS);
  }

  /** Код → токены. null — успех, иначе код ошибки. */
  private static String exchange(String code) {
    HttpClient client = Tweaks.http;
    if (code == null || client == null) return "oauth";
    try {
      HttpResponse<String> r = form("grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT) +
          "&client_id=" + enc(loginClient) + "&code_verifier=" + enc(verifier));
      if (r.statusCode() != 200) return "http " + r.statusCode();
      takeTokens(JsonParser.parseString(r.body()).getAsJsonObject(), loginClient);
      return null;
    } catch (Exception e) {
      return "network";
    }
  }

  private static synchronized boolean stopServer() {
    if (serverTimeout != null) serverTimeout.cancel(false);
    serverTimeout = null;
    HttpServer s = server;
    server = null;
    if (s == null) return false;
    s.stop(0);
    return true;
  }

  // ---------- токены ----------

  private static void loadCred() {
    if (credLoaded) return;
    credLoaded = true;
    try {
      Credentials c = PasswordSafe.getInstance().get(CRED);
      if (c != null && c.getPasswordAsString() != null) {
        refreshTok = c.getPasswordAsString();
        refreshClient = c.getUserName();
      }
    } catch (Exception e) {
      Tweaks.LOG.info(Tweaks.P + "Spotify Web API: хранилище паролей недоступно: " + e);
    }
  }

  private static boolean canControl() {
    String sc = scopes;
    return sc == null || (" " + sc + " ").contains(" " + CONTROL_SCOPE + " ");
  }

  private static void takeTokens(JsonObject o, String client) {
    access = o.get("access_token").getAsString();
    if (o.has("scope") && o.get("scope").isJsonPrimitive()) scopes = o.get("scope").getAsString();
    accessUntil = System.currentTimeMillis() + 1000L * (o.has("expires_in") ? o.get("expires_in").getAsInt() : 3600);
    if (o.has("refresh_token") && !o.get("refresh_token").isJsonNull()) {
      String rt = o.get("refresh_token").getAsString();
      if (!rt.equals(refreshTok) || !client.equals(refreshClient)) {
        refreshTok = rt;
        refreshClient = client;
        credLoaded = true;
        try {
          PasswordSafe.getInstance().set(CRED, new Credentials(client, rt));
        } catch (Exception e) {
          Tweaks.LOG.info(Tweaks.P + "Spotify Web API: не удалось сохранить вход: " + e);
        }
      }
    }
  }

  private static void forget() {
    access = null;
    refreshTok = null;
    refreshClient = null;
    user = null;
    scopes = null;
    snap = null;
    lastQueue = null;
    contexts.clear();
    try {
      PasswordSafe.getInstance().set(CRED, null);
    } catch (Exception ignored) {
    }
  }

  private static String token() throws WebError, IOException, InterruptedException {
    if (access != null && System.currentTimeMillis() < accessUntil - 30_000) return access;
    loadCred();
    String id = clientId();
    if (id == null) throw new WebError("noclient");
    if (!connected()) throw new WebError("nologin");
    HttpResponse<String> r = form("grant_type=refresh_token&refresh_token=" + enc(refreshTok) + "&client_id=" + enc(id));
    if (r.statusCode() == 400 || r.statusCode() == 401) {
      // Вход отозван (или приложение удалено) — нужно подключиться заново.
      forget();
      pushStatus();
      throw new WebError("auth");
    }
    if (r.statusCode() != 200) throw new WebError("http " + r.statusCode());
    takeTokens(JsonParser.parseString(r.body()).getAsJsonObject(), id);
    return access;
  }

  private static HttpResponse<String> form(String body) throws IOException, InterruptedException {
    HttpClient client = Tweaks.http;
    if (client == null) throw new IOException("плагин остановлен");
    return client.send(HttpRequest.newBuilder(URI.create(ACCOUNTS + "/api/token")).timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
  }

  /** GET к Web API. null — 204 (ничего не играет). */
  private static JsonObject get(String path) throws WebError, IOException, InterruptedException {
    HttpClient client = Tweaks.http;
    if (client == null) throw new IOException("плагин остановлен");
    for (int attempt = 0; ; attempt++) {
      HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(10))
          .header("Authorization", "Bearer " + token()).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int c = r.statusCode();
      if (c == 200) return r.body().isBlank() ? null : JsonParser.parseString(r.body()).getAsJsonObject();
      if (c == 204) return null;
      if (c == 401 && attempt == 0) { access = null; continue; }
      if (c == 401) throw new WebError("auth");
      if (c == 403) throw new WebError("forbidden");
      if (c == 404) throw new WebError("http 404");
      if (c == 429) {
        long sec = r.headers().firstValue("Retry-After").map(v -> { try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return 30L; } }).orElse(30L);
        retryAfter = System.currentTimeMillis() + Math.min(600, Math.max(5, sec)) * 1000;
        throw new WebError("ratelimit");
      }
      throw new WebError("http " + c);
    }
  }

  /** PUT/POST к плееру. Возвращает HTTP-код; 401/403/429 и «нет устройства» — исключением. */
  private static int control(String method, String path, String json) throws WebError, IOException, InterruptedException {
    HttpClient client = Tweaks.http;
    if (client == null) throw new IOException("плагин остановлен");
    for (int attempt = 0; ; attempt++) {
      HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(API + path)).timeout(Duration.ofSeconds(10))
          .header("Authorization", "Bearer " + token());
      if (json != null) rb.header("Content-Type", "application/json");
      HttpResponse<String> r = client.send(rb.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)).build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int c = r.statusCode();
      String body = r.body() == null ? "" : r.body().toLowerCase();
      if (c == 401 && attempt == 0) { access = null; continue; }
      if (c == 401) throw new WebError("auth");
      if (c == 403) {
        if (body.contains("premium")) throw new WebError("premium");
        if (body.contains("scope")) throw new WebError("scope");
        throw new WebError("forbidden");
      }
      if (c == 404 && body.contains("no_active_device")) throw new WebError("nodevice");
      if (c == 429) {
        retryAfter = System.currentTimeMillis() + 10_000;
        throw new WebError("ratelimit");
      }
      return c;
    }
  }

  private static boolean ok(int code) {
    return code >= 200 && code < 300;
  }

  /**
   * Переход к треку №idx из очереди. Плейлист или альбом без перемешивания — запускаем контекст с этого трека;
   * если так не вышло (трек добавлен в очередь вручную) или источник другой — пропускаем треки до него.
   * При перемешивании не переключаем.
   */
  private static void play(int idx, String uri) {
    String result;
    try {
      Snap s = snap;
      if (s == null || idx >= s.uris().size() || !uri.equals(s.uris().get(idx))) result = "stale";
      else if (s.shuffle()) result = "shuffle";
      else if (!canControl()) result = "scope";
      else {
        String dev = s.device() == null ? "" : "?device_id=" + enc(s.device());
        boolean inContext = false;
        if (s.ctxUri() != null && ("playlist".equals(s.ctxType()) || "album".equals(s.ctxType())) && uri.matches("spotify:(track|episode):\\w+")) {
          JsonObject body = new JsonObject();
          body.addProperty("context_uri", s.ctxUri());
          JsonObject off = new JsonObject();
          off.addProperty("uri", uri);
          body.add("offset", off);
          inContext = ok(control("PUT", "/me/player/play" + dev, body.toString()));
        }
        if (!inContext) {
          for (int i = 0; i <= idx; i++) {
            int c = control("POST", "/me/player/next" + dev, null);
            if (!ok(c)) throw new WebError("http " + c);
            if (i < idx) Thread.sleep(250);
          }
        }
        result = "ok";
        Tweaks.LOG.info(Tweaks.P + "Spotify Web API: переключено на трек №" + (idx + 1) + (inContext ? " (в плейлисте)" : " (пропуском)"));
        Thread.sleep(800); // даём Spotify обновить очередь
      }
    } catch (WebError e) {
      result = e.code;
      Tweaks.LOG.info(Tweaks.P + "Spotify Web API: переключение: " + e.code);
    } catch (Exception e) {
      result = "network";
      Tweaks.LOG.info(Tweaks.P + "Spotify Web API: переключение: " + e);
    }
    fetchQueue(result);
  }

  private static void fetchUser() {
    try {
      JsonObject me = get("/me");
      if (me != null) user = str(me, "display_name") != null ? str(me, "display_name") : str(me, "id");
    } catch (Exception e) {
      Tweaks.LOG.info(Tweaks.P + "Spotify Web API: /me: " + (e instanceof WebError w ? w.code : e.toString()));
    }
  }

  // ---------- очередь ----------

  /** Не чаще раза в 2 с и с учётом Retry-After; лишние просьбы склеиваются в одну. */
  private static void requestQueue() {
    if (!fetchQueued.compareAndSet(false, true)) return;
    long now = System.currentTimeMillis();
    long wait = Math.max(0, Math.max(lastFetch + 2000, retryAfter) - now);
    AppExecutorUtil.getAppScheduledExecutorService().schedule(() -> EXEC.execute(() -> {
      fetchQueued.set(false);
      fetchQueue(null);
    }), wait, TimeUnit.MILLISECONDS);
  }

  /** played — итог переключения трека (ok или код ошибки), чтобы страница сняла индикатор. */
  private static void fetchQueue(String played) {
    if (!Tweaks.running()) return;
    lastFetch = System.currentTimeMillis();
    JsonObject out = new JsonObject();
    try {
      JsonObject player = get("/me/player?additional_types=episode");
      JsonObject q = get("/me/player/queue");
      JsonElement cur = q == null ? null : q.get("currently_playing");
      if (q == null || player == null || cur == null || cur.isJsonNull()) throw new WebError("nodevice");
      JsonObject now = item(cur);
      if (now != null) out.add("now", now);
      JsonArray items = new JsonArray();
      List<String> uris = new ArrayList<>();
      if (q.get("queue") instanceof JsonArray arr) {
        for (JsonElement e : arr) {
          JsonObject it = item(e);
          if (it != null) { items.add(it); uris.add(String.valueOf(str(it, "uri"))); }
          if (items.size() >= 30) break;
        }
      }
      out.add("items", items);
      String ctxType = null, ctxUri = null, devId = null;
      if (player.get("context") instanceof JsonObject ctx) {
        out.add("context", context(ctx, cur.getAsJsonObject()));
        ctxType = str(ctx, "type");
        ctxUri = str(ctx, "uri");
      }
      boolean restricted = false;
      if (player.get("device") instanceof JsonObject dev) {
        if (str(dev, "name") != null) out.addProperty("device", str(dev, "name"));
        devId = str(dev, "id");
        restricted = "true".equals(str(dev, "is_restricted"));
      }
      boolean shuffle = "true".equals(str(player, "shuffle_state")) || "true".equals(str(player, "smart_shuffle"));
      snap = new Snap(uris, ctxType, ctxUri, shuffle, devId);
      out.addProperty("shuffle", shuffle);
      // Можно ли включать треки кликом: нужно разрешение на управление, устройство без ограничений и без перемешивания.
      out.addProperty("control", !canControl() ? "scope" : restricted ? "restricted" : shuffle ? "shuffle" : "ok");
      out.addProperty("ok", true);
    } catch (WebError e) {
      out.addProperty("error", e.code);
    } catch (Exception e) {
      out.addProperty("error", "network");
      Tweaks.LOG.info(Tweaks.P + "Spotify Web API: очередь: " + e);
    }
    out.addProperty("at", System.currentTimeMillis());
    lastQueue = out.toString();
    if (played != null) out.addProperty("played", played); // разовое событие — в lastQueue не кладём
    Tweaks.broadcast("window.__cct&&__cct.queue&&__cct.queue(" + out + ")");
  }

  /** Трек или эпизод подкаста → {name, artists, img, dur, uri}. */
  private static JsonObject item(JsonElement el) {
    if (!(el instanceof JsonObject o)) return null;
    JsonObject it = new JsonObject();
    it.addProperty("name", str(o, "name"));
    it.addProperty("uri", str(o, "uri"));
    if (o.has("duration_ms") && o.get("duration_ms").isJsonPrimitive()) it.addProperty("dur", o.get("duration_ms").getAsLong() / 1000.0);
    StringBuilder artists = new StringBuilder();
    if (o.get("artists") instanceof JsonArray arr) {
      for (JsonElement a : arr) {
        String n = a instanceof JsonObject ao ? str(ao, "name") : null;
        if (n != null) artists.append(artists.length() > 0 ? ", " : "").append(n);
      }
    } else if (o.get("show") instanceof JsonObject show && str(show, "name") != null) {
      artists.append(str(show, "name"));
    }
    it.addProperty("artists", artists.toString());
    JsonElement images = o.get("album") instanceof JsonObject album ? album.get("images") : o.get("images");
    String img = smallImage(images);
    if (img == null && o.get("show") instanceof JsonObject show) img = smallImage(show.get("images"));
    if (img != null) it.addProperty("img", img);
    return it;
  }

  /** Контекст воспроизведения: плейлист, альбом, исполнитель, «Любимые треки», подкаст. */
  private static JsonObject context(JsonObject ctx, JsonObject current) {
    String type = str(ctx, "type"), uri = str(ctx, "uri");
    JsonObject out = new JsonObject();
    out.addProperty("type", type);
    out.addProperty("uri", uri);
    if (uri == null) return out;
    if (uri.endsWith(":collection")) {
      out.addProperty("name", "Любимые треки");
      return out;
    }
    JsonObject known = contexts.get(uri);
    if (known == null) {
      known = new JsonObject();
      String id = uri.substring(uri.lastIndexOf(':') + 1);
      try {
        if ("album".equals(type) && current.get("album") instanceof JsonObject album && uri.equals(str(album, "uri"))) {
          known.addProperty("name", str(album, "name"));
          String img = smallImage(album.get("images"));
          if (img != null) known.addProperty("img", img);
        } else {
          String path = switch (type == null ? "" : type) {
            case "playlist" -> "/playlists/" + id + "?fields=name,images,owner(display_name)";
            case "album" -> "/albums/" + id;
            case "artist" -> "/artists/" + id;
            case "show" -> "/shows/" + id;
            default -> null;
          };
          JsonObject o = path == null ? null : get(path);
          if (o != null) {
            known.addProperty("name", str(o, "name"));
            String img = smallImage(o.get("images"));
            if (img != null) known.addProperty("img", img);
            if (o.get("owner") instanceof JsonObject owner && str(owner, "display_name") != null)
              known.addProperty("owner", str(owner, "display_name"));
          }
        }
        contexts.put(uri, known);
      } catch (WebError e) {
        // Подборки Spotify («Микс дня» и т. п.) Web API приложениям не отдаёт — покажем без названия.
        if (e.code.equals("http 404")) contexts.put(uri, known);
      } catch (Exception e) {
        Tweaks.LOG.info(Tweaks.P + "Spotify Web API: контекст: " + e);
      }
    }
    for (Map.Entry<String, JsonElement> e : known.entrySet()) out.add(e.getKey(), e.getValue());
    return out;
  }

  /** Самая маленькая картинка не меньше 48px (обычно 64px). */
  private static String smallImage(JsonElement images) {
    if (!(images instanceof JsonArray arr)) return null;
    String best = null;
    int bestW = Integer.MAX_VALUE;
    for (JsonElement e : arr) {
      if (!(e instanceof JsonObject o) || str(o, "url") == null) continue;
      int w = o.get("width") != null && o.get("width").isJsonPrimitive() ? o.get("width").getAsInt() : 300;
      if (best == null || (w >= 48 && w < bestW)) { best = str(o, "url"); bestW = w; }
    }
    return best;
  }

  private static void open(String uri) {
    // Протокол spotify: открывает плейлист прямо в приложении Spotify.
    String target = uri.endsWith(":collection") ? "spotify:collection:tracks" : uri;
    AppExecutorUtil.getAppExecutorService().execute(() -> {
      try {
        Desktop.getDesktop().browse(URI.create(target));
      } catch (Exception e) {
        Tweaks.LOG.info(Tweaks.P + "Spotify: не открылось " + target + ": " + e);
      }
    });
  }

  // ---------- мелочи ----------

  private static String str(JsonObject o, String key) {
    JsonElement e = o.get(key);
    return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  private static String random(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
  }

  private static Map<String, String> query(String raw) {
    Map<String, String> out = new HashMap<>();
    if (raw == null) return out;
    for (String part : raw.split("&")) {
      int i = part.indexOf('=');
      if (i <= 0) continue;
      out.put(URLDecoder.decode(part.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
    }
    return out;
  }
}
