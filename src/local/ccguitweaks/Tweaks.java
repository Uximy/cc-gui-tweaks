package local.ccguitweaks;

import com.intellij.ide.BrowserUtil;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.util.concurrency.AppExecutorUtil;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefDisplayHandler;
import org.cef.handler.CefDisplayHandlerAdapter;
import org.cef.handler.CefLoadHandler;
import org.cef.handler.CefLoadHandlerAdapter;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CC GUI Tweaks: дополнения к чату CC GUI без правки его JAR.
 *
 * Находит JCEF-браузеры в окнах IDE (JBCefBrowser кладёт себя в client property "JBCefBrowser.instance")
 * и внедряет в них tweaks.js. Скрипт сам проверяет, что это страница CC GUI, и отвечает "__cct:ready"
 * сообщением консоли; тем же каналом приходят команды и изменения настроек. Данные модулей уходят на
 * страницу вызовами window.__cct.*.
 *
 * Модули ({@link SpotifyModule} с {@link SpotifyWeb}, {@link LimitsModule}) работают, только пока включены в настройках.
 * Плагин динамический: {@link #stop()} снимает всё, что повешено на IDE и страницы (см. Updater).
 */
public final class Tweaks {
  static final String ID = "local.cc-gui-tweaks";
  static final String P = "cc-gui-tweaks: ";
  static final Logger LOG = Logger.getInstance(Tweaks.class);
  private static final String BROWSER_PROP = "JBCefBrowser.instance"; // JBCefBrowserBase.JBCEFBROWSER_INSTANCE_PROP

  /** Что повешено на браузер (чтобы снять при выгрузке) и что ему уже отправлено. */
  static final class Hook {
    final CefLoadHandler load;
    final CefDisplayHandler display;
    final Map<String, String> sent = new ConcurrentHashMap<>();

    Hook(CefLoadHandler load, CefDisplayHandler display) {
      this.load = load;
      this.display = display;
    }
  }

  private static final Map<JBCefBrowserBase, Hook> hooked = Collections.synchronizedMap(new WeakHashMap<>());

  static volatile HttpClient http;
  private static String js;
  private static Timer scanTimer;
  private static volatile boolean running;
  private static volatile boolean readySeen;

  private Tweaks() {}

  static boolean running() {
    return running;
  }

  static synchronized void start() {
    if (running || !SystemInfo.isWindows) return;
    try {
      js = resource("/cc-gui-tweaks/tweaks.js");
    } catch (IOException e) {
      LOG.warn(P + "нет tweaks.js", e);
      return;
    }
    running = true;
    Settings.load();
    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    SwingUtilities.invokeLater(() -> {
      synchronized (Tweaks.class) {
        if (!running) return;
        scanTimer = new Timer(2000, e -> scan());
        scanTimer.setInitialDelay(300);
        scanTimer.start();
      }
    });
    SpotifyModule.apply();
    LimitsModule.apply();
    PerfJournal.apply();
    Updater.start();
    LOG.info(P + "запущен, версия " + Updater.currentVersion());
  }

  /** Снимает таймеры, обработчики JCEF и скрипт со страниц. Хелпер Spotify остаётся: новая версия подхватит его. */
  static synchronized void stop() {
    if (!running) return;
    running = false;
    Updater.stop();
    SpotifyModule.stop();
    SpotifyWeb.stop();
    LimitsModule.stop();
    PerfJournal.stop();
    Timer t = scanTimer;
    scanTimer = null;
    // beforePluginUnload приходит в EDT — останавливаем сразу, не оставляя в очереди наших лямбд.
    if (t != null) {
      if (SwingUtilities.isEventDispatchThread()) t.stop(); else SwingUtilities.invokeLater(t::stop);
    }
    List<Map.Entry<JBCefBrowserBase, Hook>> all;
    synchronized (hooked) {
      all = new ArrayList<>(hooked.entrySet());
      hooked.clear();
    }
    for (Map.Entry<JBCefBrowserBase, Hook> e : all) {
      JBCefBrowserBase b = e.getKey();
      try {
        if (b.isDisposed()) continue;
        CefBrowser cef = b.getCefBrowser();
        b.getJBCefClient().removeLoadHandler(e.getValue().load, cef);
        b.getJBCefClient().removeDisplayHandler(e.getValue().display, cef);
        cef.executeJavaScript("window.__cctStop&&window.__cctStop()", "cc-gui-tweaks://stop", 0);
      } catch (Throwable ex) {
        LOG.info(P + "снятие с браузера: " + ex);
      }
    }
    HttpClient h = http;
    http = null;
    if (h != null) h.shutdownNow();
    LOG.info(P + "остановлен");
  }

  // ---------- поиск браузеров (EDT) ----------

  private static void scan() {
    if (!running) return;
    try {
      for (Window w : Window.getWindows()) {
        if (w.isDisplayable()) walk(w);
      }
    } catch (Throwable t) {
      LOG.warn(P + "scan", t);
    }
  }

  private static void walk(Component c) {
    if (c instanceof JComponent jc && jc.getClientProperty(BROWSER_PROP) instanceof JBCefBrowserBase b
        && !b.isDisposed() && !hooked.containsKey(b)) {
      hook(b);
    }
    if (c instanceof Container ct) {
      for (Component child : ct.getComponents()) walk(child);
    }
  }

  private static void hook(JBCefBrowserBase b) {
    CefBrowser cef = b.getCefBrowser();
    CefLoadHandler load = new CefLoadHandlerAdapter() {
      @Override
      public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
        Hook h = hooked.get(b);
        if (h != null && (frame == null || frame.isMain())) {
          h.sent.clear();
          inject(b);
        }
      }
    };
    CefDisplayHandler display = new CefDisplayHandlerAdapter() {
      @Override
      public boolean onConsoleMessage(CefBrowser browser, CefSettings.LogSeverity level, String message, String source, int line) {
        if (message == null || !message.startsWith("__cct:")) return false;
        if (running) onMessage(b, message.substring(6));
        return true;
      }
    };
    hooked.put(b, new Hook(load, display));
    b.getJBCefClient().addLoadHandler(load, cef);
    b.getJBCefClient().addDisplayHandler(display, cef);
    LOG.info(P + "найден браузер " + b.getClass().getSimpleName() + " " + cef.getURL());
    inject(b);
  }

  private static void inject(JBCefBrowserBase b) {
    if (b.isDisposed() || !running) return;
    b.getCefBrowser().executeJavaScript(js, "cc-gui-tweaks://tweaks.js", 0);
  }

  private static void onMessage(JBCefBrowserBase b, String msg) {
    if (msg.equals("ready")) {
      if (!readySeen) { readySeen = true; LOG.info(P + "скрипт активен в " + b.getCefBrowser().getURL()); }
      Hook h = hooked.get(b);
      if (h != null) h.sent.clear();
      exec(b, "window.__cct&&__cct.settings(" + Settings.forPage() + ")");
      AppExecutorUtil.getAppExecutorService().execute(() -> {
        SpotifyModule.onReady(b);
        SpotifyWeb.onReady(b);
        LimitsModule.onReady(b);
      });
      RegistryTweaks.onReady(b);
      PerfJournal.onReady(b);
    } else if (msg.startsWith("set:")) {
      if (!Settings.update(msg.substring(4))) return;
      broadcast("window.__cct&&__cct.settings(" + Settings.forPage() + ")");
      AppExecutorUtil.getAppExecutorService().execute(() -> {
        SpotifyModule.apply();
        SpotifyWeb.onSettings();
        LimitsModule.apply();
        PerfJournal.apply();
      });
    } else if (msg.startsWith("perflog:")) {
      PerfJournal.append(msg.substring(8));
    } else if (msg.startsWith("journal:")) {
      PerfJournal.command(msg.substring(8));
    } else if (msg.startsWith("spweb:")) {
      SpotifyWeb.command(msg.substring(6));
    } else if (msg.startsWith("lyrics:")) {
      Lyrics.request(b, msg.substring(7));
    } else if (msg.startsWith("spotify:")) {
      SpotifyModule.command(msg.substring(8));
    } else if (msg.equals("limits:refresh")) {
      LimitsModule.refreshSoon();
    } else if (msg.startsWith("reg:")) {
      RegistryTweaks.command(msg.substring(4));
    } else if (msg.startsWith("open:")) {
      // «О плагине»: только свои адреса, страница передаёт ключ, а не ссылку
      String url = switch (msg.substring(5)) {
        case "github" -> "https://github.com/Uximy";
        case "site" -> "https://himitsu-fm.online/";
        default -> null;
      };
      if (url != null) BrowserUtil.browse(url);
    }
  }

  // ---------- отправка на страницы ----------

  static Hook hookOf(JBCefBrowserBase b) {
    return hooked.get(b);
  }

  static List<JBCefBrowserBase> browsers() {
    synchronized (hooked) {
      return new ArrayList<>(hooked.keySet());
    }
  }

  static void exec(JBCefBrowserBase b, String code) {
    if (!running || b.isDisposed()) return;
    b.getCefBrowser().executeJavaScript(code, "cc-gui-tweaks://push", 0);
  }

  static void broadcast(String code) {
    for (JBCefBrowserBase b : browsers()) exec(b, code);
  }

  static String resource(String name) throws IOException {
    try (InputStream in = Tweaks.class.getResourceAsStream(name)) {
      if (in == null) throw new IOException(name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
