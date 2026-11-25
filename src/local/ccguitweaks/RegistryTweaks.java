package local.ccguitweaks;

import com.google.gson.JsonObject;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;
import com.intellij.ui.jcef.JBCefBrowserBase;

/**
 * Ключи реестра IDE, от которых зависит, как рисуется встроенный браузер (а значит и чат CC GUI).
 * Это настройки всей IDE: меняются только по кнопкам на странице и возвращаются к значениям
 * по умолчанию кнопкой «Вернуть как было». Действуют после перезапуска IDE.
 */
final class RegistryTweaks {
  private record Key(String id, String key, boolean bool) {}

  private static final Key[] KEYS = {
      new Key("fps", "ide.browser.jcef.osr.framerate", false),      // кадров в секунду для «закадрового» браузера, по умолчанию 120
      new Key("oop", "ide.browser.jcef.out-of-process.enabled", true), // браузер в отдельном процессе, по умолчанию вкл.
      new Key("gpu", "ide.browser.jcef.gpu.disable", true),          // отключить аппаратное ускорение, по умолчанию выкл.
  };
  private static final int[] FPS = {30, 60, 120};

  private RegistryTweaks() {}

  static void onReady(JBCefBrowserBase b) {
    Tweaks.exec(b, "window.__cct&&__cct.registry&&__cct.registry(" + state() + ")");
  }

  /** reg:get | reg:set:<id>:<value> | reg:reset:<id> | reg:resetall | reg:restart */
  static void command(String cmd) {
    ApplicationManager.getApplication().invokeLater(() -> {
      try {
        run(cmd);
      } catch (Exception e) {
        Tweaks.LOG.info(Tweaks.P + "реестр: " + cmd + " — " + e);
      }
      push();
    }, ModalityState.any());
  }

  private static void run(String cmd) {
    if (cmd.equals("restart")) {
      Tweaks.LOG.info(Tweaks.P + "реестр: перезапуск IDE по кнопке");
      ApplicationManager.getApplication().restart();
      return;
    }
    if (cmd.equals("resetall")) {
      for (Key k : KEYS) {
        RegistryValue v = value(k);
        if (v != null && v.isChangedFromDefault()) {
          v.resetToDefault();
          Tweaks.LOG.info(Tweaks.P + "реестр: " + k.key + " сброшен к " + v.asString());
        }
      }
      return;
    }
    String[] p = cmd.split(":");
    Key k = find(p.length > 1 ? p[1] : "");
    RegistryValue v = k == null ? null : value(k);
    if (v == null) return;
    if (p[0].equals("reset") && p.length == 2) {
      v.resetToDefault();
    } else if (p[0].equals("set") && p.length == 3) {
      if (k.bool) {
        if (!p[2].equals("true") && !p[2].equals("false")) return;
        v.setValue(Boolean.parseBoolean(p[2]));
      } else {
        int n;
        try {
          n = Integer.parseInt(p[2]);
        } catch (NumberFormatException e) {
          return;
        }
        boolean ok = false;
        for (int f : FPS) ok |= f == n;
        if (!ok) return;
        v.setValue(n);
      }
    } else {
      return;
    }
    Tweaks.LOG.info(Tweaks.P + "реестр: " + k.key + " = " + v.asString());
  }

  private static void push() {
    Tweaks.broadcast("window.__cct&&__cct.registry&&__cct.registry(" + state() + ")");
  }

  static String state() {
    JsonObject out = new JsonObject();
    boolean restart = false;
    for (Key k : KEYS) {
      JsonObject o = new JsonObject();
      RegistryValue v = value(k);
      if (v == null) {
        o.addProperty("missing", true);
      } else {
        if (k.bool) o.addProperty("value", v.asBoolean());
        else o.addProperty("value", v.asInteger());
        o.addProperty("changed", v.isChangedFromDefault());
        boolean pending = v.isChangedSinceAppStart();
        o.addProperty("pending", pending);
        restart |= pending;
      }
      out.add(k.id, o);
    }
    out.addProperty("restart", restart);
    return out.toString();
  }

  private static Key find(String id) {
    for (Key k : KEYS) if (k.id.equals(id)) return k;
    return null;
  }

  /** null — такого ключа в этой версии IDE нет. */
  private static RegistryValue value(Key k) {
    try {
      RegistryValue v = Registry.get(k.key);
      v.asString();   // несуществующий ключ падает здесь
      return v;
    } catch (Exception e) {
      return null;
    }
  }
}
