package local.ccguitweaks;

import com.intellij.openapi.application.PathManager;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.util.concurrency.AppExecutorUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Модуль «Spotify»: мини-плеер над вкладками CC GUI.
 *
 * Данные даёт helper.ps1 (Windows SMTC + громкость Spotify в микшере), HTTP на localhost:43117.
 * Хелпер запускается при включении модуля и выходит по /quit при выключении (или сам, если его
 * никто не спрашивает 90 с — например, IDE закрыли).
 */
final class SpotifyModule {
  private static final String HELPER = "http://localhost:43117";
  private static final int HELPER_VERSION = 3;
  private static final String NO_COVER = "\u0000none";
  private static final String SENT_COVER = "spotify.cover";
  private static final Pattern COVER = Pattern.compile("\"cover\"\\s*:\\s*\"([^\"]+)\"");
  private static final Object POLL = new Object();
  /** Команды по очереди, чтобы громкость не приходила вразнобой. */
  private static final ExecutorService COMMANDS = AppExecutorUtil.createBoundedApplicationPoolExecutor("CC GUI Tweaks Spotify", 1);

  private static ScheduledFuture<?> task;
  private static volatile boolean on;
  private static volatile String lastJson = "null";
  private static volatile String coverKey;
  private static volatile String coverUrl;
  private static volatile long helperStartedAt;
  private static volatile boolean oldHelperQuit;
  private static final AtomicInteger pendingVol = new AtomicInteger(-1);
  /** Позиция для перемотки в мс, -1 — нет. */
  private static final AtomicLong pendingSeek = new AtomicLong(-1);

  private SpotifyModule() {}

  /** Включает или выключает модуль по текущим настройкам. */
  static synchronized void apply() {
    boolean want = Tweaks.running() && Settings.bool("spotify", "enabled");
    if (want == on) return;
    on = want;
    if (want) {
      helperStartedAt = 0;
      task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(SpotifyModule::poll, 0, 1500, TimeUnit.MILLISECONDS);
      Tweaks.LOG.info(Tweaks.P + "Spotify включён");
    } else {
      cancel();
      Tweaks.broadcast("window.__cct&&__cct.spotify(null,null)");
      HttpClient client = Tweaks.http;
      if (client != null) COMMANDS.execute(() -> post(client, "/quit"));
      Tweaks.LOG.info(Tweaks.P + "Spotify выключен");
    }
  }

  /** Выгрузка плагина: только останавливаемся, хелпер подхватит новая версия. */
  static synchronized void stop() {
    on = false;
    cancel();
  }

  private static void cancel() {
    if (task != null) task.cancel(false);
    task = null;
    lastJson = "null";
    coverKey = null;
    coverUrl = null;
  }

  static void onReady(JBCefBrowserBase b) {
    Tweaks.Hook h = Tweaks.hookOf(b);
    if (h != null) h.sent.remove(SENT_COVER);
    if (on) push(List.of(b));
    else Tweaks.exec(b, "window.__cct&&__cct.spotify(null,null)");
  }

  static void command(String cmd) {
    HttpClient client = Tweaks.http;
    if (!on || client == null) return;
    if (cmd.matches("toggle|next|prev")) {
      COMMANDS.execute(() -> {
        post(client, "/cmd?c=" + cmd);
        sleep(350);
        poll();
      });
    } else if (cmd.matches("vol:\\d{1,3}")) {
      // Пока команда в очереди, ползунок мог уехать дальше — отправляем только последнее значение.
      if (pendingVol.getAndSet(Math.min(100, Integer.parseInt(cmd.substring(4)))) == -1) {
        COMMANDS.execute(() -> {
          int v = pendingVol.getAndSet(-1);
          if (v >= 0) post(client, "/vol?v=" + v);
        });
      }
    } else if (cmd.matches("seek:\\d{1,8}")) {
      // Перетаскивание шлёт позиции пачкой — отправляем только последнюю.
      if (pendingSeek.getAndSet(Long.parseLong(cmd.substring(5))) == -1) {
        COMMANDS.execute(() -> {
          long ms = pendingSeek.getAndSet(-1);
          if (ms < 0) return;
          post(client, "/seek?ms=" + ms);
          sleep(300);
          poll();
        });
      }
    } else if (cmd.matches("mute:[01]")) {
      COMMANDS.execute(() -> {
        post(client, "/mute?m=" + cmd.substring(5));
        poll();
      });
    }
  }

  private static void post(HttpClient client, String path) {
    try {
      client.send(HttpRequest.newBuilder(URI.create(HELPER + path)).timeout(Duration.ofSeconds(3))
          .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
    } catch (Exception e) {
      if (!path.equals("/quit")) Tweaks.LOG.info(Tweaks.P + "команда " + path + ": " + e);
    }
  }

  // ---------- данные от хелпера (фоновый поток) ----------

  private static void poll() {
    synchronized (POLL) {
      HttpClient client = Tweaks.http;
      if (!on || client == null) return;
      try {
        HttpResponse<String> r = client.send(HttpRequest.newBuilder(URI.create(HELPER + "/now")).timeout(Duration.ofSeconds(4)).GET().build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String json = r.body().trim();
        if (r.statusCode() != 200 || !json.startsWith("{")) throw new IOException("HTTP " + r.statusCode());
        if (!json.contains("\"v\":" + HELPER_VERSION) && !oldHelperQuit) {
          // Хелпер от прошлой версии плагина: просим выйти, следующий опрос запустит новый.
          oldHelperQuit = true;
          helperStartedAt = 0;
          Tweaks.LOG.info(Tweaks.P + "отвечает хелпер старой версии — перезапускаю");
          post(client, "/quit");
        }
        if (json.contains("\"ok\":false")) return; // временная ошибка SMTC — оставляем прежнее состояние
        Matcher m = COVER.matcher(json);
        String key = m.find() ? m.group(1) : null;
        if (key != null && !key.equals(coverKey)) loadCover(client, key);
        if (key == null) { coverKey = null; coverUrl = null; }
        lastJson = json;
      } catch (Exception e) {
        if (!on) return;
        lastJson = "null";
        ensureHelper();
      }
      push(Tweaks.browsers());
    }
  }

  private static void loadCover(HttpClient client, String key) {
    coverKey = key;
    coverUrl = null;
    try {
      HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create(HELPER + "/cover?k=" + key)).timeout(Duration.ofSeconds(3)).GET().build(),
          HttpResponse.BodyHandlers.ofByteArray());
      if (r.statusCode() == 200) {
        String type = r.headers().firstValue("Content-Type").orElse("image/jpeg");
        coverUrl = "data:" + type + ";base64," + Base64.getEncoder().encodeToString(r.body());
      }
    } catch (Exception e) {
      Tweaks.LOG.info(Tweaks.P + "обложка: " + e);
    }
  }

  private static void push(List<JBCefBrowserBase> targets) {
    if (!on) return;
    String key = coverKey == null ? NO_COVER : coverKey;
    String url = coverUrl;
    String json = lastJson;
    for (JBCefBrowserBase b : targets) {
      Tweaks.Hook h = Tweaks.hookOf(b);
      if (h == null || b.isDisposed()) continue;
      // Обложку (до сотни КБ) шлём только тому, кто её ещё не получил.
      String cover = key.equals(h.sent.get(SENT_COVER)) ? "undefined" : url == null ? "null" : "'" + url + "'";
      Tweaks.exec(b, "window.__cct&&__cct.spotify(" + json + "," + cover + ")");
      h.sent.put(SENT_COVER, key);
    }
  }

  // ---------- запуск хелпера ----------

  private static void ensureHelper() {
    long now = System.currentTimeMillis();
    if (!on || now - helperStartedAt < 30_000) return;
    helperStartedAt = now;
    try {
      Path dir = Path.of(PathManager.getSystemPath(), "cc-gui-tweaks");
      Files.createDirectories(dir);
      Path script = dir.resolve("helper.ps1");
      try (InputStream in = SpotifyModule.class.getResourceAsStream("/cc-gui-tweaks/helper.ps1")) {
        if (in == null) throw new IOException("нет helper.ps1 в плагине");
        byte[] bytes = in.readAllBytes();
        if (!Files.exists(script) || !Arrays.equals(Files.readAllBytes(script), bytes)) Files.write(script, bytes);
      }
      new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", script.toString())
          .directory(dir.toFile())
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start();
      Tweaks.LOG.info(Tweaks.P + "запущен хелпер " + script);
    } catch (Exception e) {
      Tweaks.LOG.warn(Tweaks.P + "не удалось запустить хелпер", e);
    }
  }

  private static void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
