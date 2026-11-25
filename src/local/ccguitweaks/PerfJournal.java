package local.ccguitweaks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.ui.jcef.JBCefBrowserBase;
import com.intellij.util.concurrency.AppExecutorUtil;

import javax.swing.SwingUtilities;
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Журнал лагов (настройка perf.journal, вкладка «Производительность»).
 *
 * Пишет в system/cc-gui-tweaks/perf-journal.jsonl:
 *  - со страницы чата — длинные кадры (Long Animation Frames: сколько длился, какие скрипты), зависания таймера;
 *  - из IDE — задержки окна IDE (EDT не отвечал дольше 300 мс, со стеком, если дольше секунды);
 *  - раз в минуту — загрузку процессора IDE и её дочерних процессов, память, сборку мусора.
 * По кнопке «Выгрузить логи» собирает zip в «Загрузки»: журнал, его читаемый вид, сводку о системе,
 * отфильтрованный idea.log, логи Chromium и дампы зависаний IDE. Токены и путь к профилю вычищаются.
 * Всё хранится локально и никуда не отправляется.
 *
 * Страница шлёт {@code perflog:[...]} и {@code journal:export:{...} | journal:clear | journal:reveal | journal:get},
 * состояние уходит вызовом {@code __cct.journal(...)}.
 */
final class PerfJournal {
  private static final long MAX_FILE = 3L << 20;           // после 3 МБ файл уходит в .1
  private static final long EDT_LAG_MS = 300, EDT_STACK_MS = 1000;
  private static final ExecutorService POOL = AppExecutorUtil.createBoundedApplicationPoolExecutor("CC GUI Tweaks Journal", 1);
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
  private static final Pattern LOG_HEAD = Pattern.compile("^\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d");
  private static final Pattern LOG_KEEP = Pattern.compile(
      "(?i)cc-gui-tweaks|claudecodegui|PerformanceWatcher|freez|UI was frozen|jcef|JBCef|chromium|OutOfMemory|LowMemory|"
          + "\\bERROR\\b|\\bSEVERE\\b|Low memory|GC overhead|slow operation");

  private static volatile boolean on;
  private static ScheduledFuture<?> pinger, minute;
  // EDT-пинг: когда отправлен, был ли уже снят стек, поток EDT
  private static volatile long pingAt;
  private static volatile Thread edt;
  private static volatile String edtStack;
  // за текущую минуту (меняется только в POOL)
  private static int minPings, minLags;
  private static long minMaxLag;
  private static long lastGc = -1, lastCpuAt;
  private static final Map<Long, Long> lastChildCpu = new HashMap<>();
  // состояние для страницы (только в POOL)
  private static int lags = -1;
  private static long since;
  private static String saved, err;
  private static Path savedPath;
  private static boolean busy;

  private PerfJournal() {}

  private static Path dir() {
    return Path.of(PathManager.getSystemPath(), "cc-gui-tweaks");
  }

  private static Path file() {
    return dir().resolve("perf-journal.jsonl");
  }

  private static Path old() {
    return dir().resolve("perf-journal.1.jsonl");
  }

  // ---------- включение ----------

  /** Вызывается при старте и при смене настроек. */
  static synchronized void apply() {
    boolean want = Tweaks.running() && Settings.bool("perf", "journal");
    if (want && !on) {
      on = true;
      pingAt = 0;
      var sch = AppExecutorUtil.getAppScheduledExecutorService();
      pinger = sch.scheduleWithFixedDelay(PerfJournal::ping, 1, 1, TimeUnit.SECONDS);
      minute = sch.scheduleWithFixedDelay(() -> POOL.execute(PerfJournal::minuteSummary), 60, 60, TimeUnit.SECONDS);
      POOL.execute(() -> {
        JsonObject o = new JsonObject();
        o.addProperty("k", "start");
        o.addProperty("ver", Updater.currentVersion());
        write("ide", o);
        resetMinute();
        sampleCpu(true);
      });
      Tweaks.LOG.info(Tweaks.P + "журнал лагов включён");
    } else if (!want && on) {
      halt();
      Tweaks.LOG.info(Tweaks.P + "журнал лагов выключен");
    }
    POOL.execute(PerfJournal::push);
  }

  static synchronized void stop() {
    if (on) halt();
  }

  private static void halt() {
    on = false;
    if (pinger != null) pinger.cancel(false);
    if (minute != null) minute.cancel(false);
    pinger = minute = null;
  }

  static void onReady(JBCefBrowserBase b) {
    POOL.execute(() -> pushTo(b));
  }

  // ---------- задержки окна IDE ----------

  private static void ping() {
    if (!on) return;
    long now = System.nanoTime(), sent = pingAt;
    if (sent != 0) {
      // прошлый пинг ещё не вернулся: EDT занят — один раз снимаем, чем
      long waited = (now - sent) / 1_000_000;
      Thread t = edt;
      if (waited >= EDT_STACK_MS && edtStack == null && t != null) edtStack = stack(t.getStackTrace());
      return;
    }
    pingAt = now;
    edtStack = null;
    SwingUtilities.invokeLater(() -> {
      edt = Thread.currentThread();
      long lag = (System.nanoTime() - pingAt) / 1_000_000;
      String st = edtStack;
      pingAt = 0;
      POOL.execute(() -> {
        minPings++;
        if (lag > minMaxLag) minMaxLag = lag;
        if (lag < EDT_LAG_MS) return;
        minLags++;
        JsonObject o = new JsonObject();
        o.addProperty("k", "edt");
        o.addProperty("d", lag);
        if (st != null) o.addProperty("stack", st);
        write("ide", o);
      });
    });
  }

  private static String stack(StackTraceElement[] st) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < st.length && i < 30; i++) sb.append(st[i]).append('\n');
    return sb.toString();
  }

  // ---------- раз в минуту: процессор и память ----------

  private static void resetMinute() {
    minPings = minLags = 0;
    minMaxLag = 0;
  }

  private static void minuteSummary() {
    if (!on) return;
    JsonObject o = sampleCpu(false);
    o.addProperty("k", "min");
    o.addProperty("edtLags", minLags);
    o.addProperty("edtMax", minMaxLag);
    resetMinute();
    write("ide", o);
    push();
  }

  /** Загрузка процессора IDE и дочерних процессов (по приращению их процессорного времени), память, GC. */
  private static JsonObject sampleCpu(boolean first) {
    JsonObject o = new JsonObject();
    long now = System.nanoTime(), wall = lastCpuAt == 0 ? 0 : now - lastCpuAt;
    lastCpuAt = now;
    try {
      if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
        o.addProperty("cpu", pct(os.getProcessCpuLoad()));
        o.addProperty("sys", pct(os.getCpuLoad()));
      }
    } catch (Throwable ignored) {}
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    o.addProperty("heapMb", heap.getUsed() >> 20);
    o.addProperty("heapMaxMb", heap.getMax() >> 20);
    long gc = 0;
    for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) gc += Math.max(0, g.getCollectionTime());
    if (lastGc >= 0) o.addProperty("gcMs", gc - lastGc);
    lastGc = gc;
    // дочерние процессы: Chromium (если в отдельном процессе), node/claude от CC GUI и т. п.
    Map<Long, Long> cur = new HashMap<>();
    List<JsonObject> kids = new ArrayList<>();
    int cores = Runtime.getRuntime().availableProcessors();
    try {
      ProcessHandle.current().descendants().forEach(p -> {
        long cpu = p.info().totalCpuDuration().map(d -> d.toNanos()).orElse(-1L);
        if (cpu < 0) return;
        cur.put(p.pid(), cpu);
        Long prev = lastChildCpu.get(p.pid());
        if (first || wall <= 0 || prev == null) return;
        double share = (cpu - prev) * 100.0 / wall / cores;
        if (share < 0.5) return;
        JsonObject k = new JsonObject();
        k.addProperty("name", p.info().command().map(c -> Path.of(c).getFileName().toString()).orElse("?"));
        k.addProperty("pid", p.pid());
        k.addProperty("cpu", Math.round(share * 10) / 10.0);
        kids.add(k);
      });
    } catch (Throwable ignored) {}
    lastChildCpu.clear();
    lastChildCpu.putAll(cur);
    kids.sort(Comparator.comparingDouble((JsonObject k) -> -k.get("cpu").getAsDouble()));
    JsonArray arr = new JsonArray();
    for (int i = 0; i < kids.size() && i < 6; i++) arr.add(kids.get(i));
    o.add("kids", arr);
    o.addProperty("nkids", cur.size());
    return o;
  }

  private static double pct(double load) {
    return load < 0 ? -1 : Math.round(load * 1000) / 10.0;
  }

  // ---------- события со страницы ----------

  /** perflog:[{k:'frame'|'stall'|'page', ...}, …] — не больше 60 за раз, строки обрезаются. */
  static void append(String json) {
    if (!on) return;
    POOL.execute(() -> {
      JsonArray arr;
      try {
        arr = JsonParser.parseString(json).getAsJsonArray();
      } catch (Exception e) {
        return;
      }
      int n = 0;
      for (JsonElement e : arr) {
        if (!e.isJsonObject() || ++n > 60) continue;
        JsonObject o = clean(e.getAsJsonObject(), 0);
        if (o.toString().length() > 6000) continue;
        write("page", o);
      }
      push();
    });
  }

  private static JsonObject clean(JsonObject in, int depth) {
    JsonObject out = new JsonObject();
    for (Map.Entry<String, JsonElement> e : in.entrySet()) {
      if (out.size() >= 30) break;
      String k = e.getKey().length() > 32 ? e.getKey().substring(0, 32) : e.getKey();
      JsonElement v = cleanValue(e.getValue(), depth);
      if (v != null) out.add(k, v);
    }
    return out;
  }

  private static JsonElement cleanValue(JsonElement v, int depth) {
    if (v.isJsonPrimitive()) {
      JsonPrimitive p = v.getAsJsonPrimitive();
      if (p.isString() && p.getAsString().length() > 300) return new JsonPrimitive(p.getAsString().substring(0, 300));
      return p;
    }
    if (depth >= 2) return null;
    if (v.isJsonObject()) return clean(v.getAsJsonObject(), depth + 1);
    if (v.isJsonArray()) {
      JsonArray out = new JsonArray();
      for (JsonElement x : v.getAsJsonArray()) {
        if (out.size() >= 10) break;
        JsonElement c = cleanValue(x, depth + 1);
        if (c != null) out.add(c);
      }
      return out;
    }
    return null;
  }

  // ---------- файл (только в POOL) ----------

  private static void write(String src, JsonObject o) {
    JsonObject line = new JsonObject();
    long ts = o.has("t") && o.get("t").isJsonPrimitive() && o.get("t").getAsJsonPrimitive().isNumber() ? o.get("t").getAsLong() : 0;
    long now = System.currentTimeMillis();
    if (ts <= 0 || Math.abs(now - ts) > 3_600_000) ts = now;
    line.addProperty("ts", ts);
    line.addProperty("src", src);
    for (Map.Entry<String, JsonElement> e : o.entrySet()) if (!e.getKey().equals("t")) line.add(e.getKey(), e.getValue());
    try {
      Files.createDirectories(dir());
      Path f = file();
      if (Files.exists(f) && Files.size(f) > MAX_FILE) {
        Files.move(f, old(), StandardCopyOption.REPLACE_EXISTING);   // самый старый .1 пропадает — пересчитать
        lags = -1;
      }
      count();
      Files.writeString(f, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
      if (isLag(line)) lags++;
      if (since == 0) since = ts;
    } catch (IOException e) {
      Tweaks.LOG.info(Tweaks.P + "журнал лагов: " + e);
    }
  }

  private static boolean isLag(JsonObject o) {
    String k = o.has("k") ? o.get("k").getAsString() : "";
    return k.equals("frame") || k.equals("stall") || k.equals("edt");
  }

  /** Сколько лагов в файлах и с какого времени — пересчитывается один раз, дальше на лету. */
  private static void count() {
    if (lags >= 0) return;
    lags = 0;
    since = 0;
    for (Path p : new Path[]{old(), file()}) {
      if (!Files.exists(p)) continue;
      try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
        String s;
        while ((s = r.readLine()) != null) {
          try {
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            if (since == 0 && o.has("ts")) since = o.get("ts").getAsLong();
            if (isLag(o)) lags++;
          } catch (Exception ignored) {}
        }
      } catch (IOException ignored) {}
    }
  }

  private static long size() {
    long s = 0;
    for (Path p : new Path[]{old(), file()}) {
      try { if (Files.exists(p)) s += Files.size(p); } catch (IOException ignored) {}
    }
    return s;
  }

  // ---------- команды ----------

  static void command(String cmd) {
    if (cmd.startsWith("export")) {
      String meta = cmd.startsWith("export:") ? cmd.substring(7) : "{}";
      POOL.execute(() -> {
        busy = true;
        err = null;
        push();
        try {
          savedPath = export(meta);
          saved = home(savedPath.toString());
          reveal();
          Tweaks.LOG.info(Tweaks.P + "журнал лагов: логи сохранены в " + saved);
        } catch (Exception e) {
          Tweaks.LOG.info(Tweaks.P + "журнал лагов: выгрузка не удалась — " + e);
          err = String.valueOf(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
        busy = false;
        push();
      });
    } else if (cmd.equals("clear")) {
      POOL.execute(() -> {
        try {
          Files.deleteIfExists(file());
          Files.deleteIfExists(old());
        } catch (IOException e) {
          err = e.getMessage();
        }
        lags = -1;
        saved = null;
        savedPath = null;
        push();
      });
    } else if (cmd.equals("reveal")) {
      POOL.execute(PerfJournal::reveal);
    } else if (cmd.equals("get")) {
      POOL.execute(PerfJournal::push);
    }
  }

  private static void reveal() {
    Path p = savedPath;
    if (p == null || !Files.exists(p)) return;
    try {
      RevealFileAction.openFile(p);   // Проводник с выделенным файлом
    } catch (Exception e) {
      Tweaks.LOG.info(Tweaks.P + "журнал лагов: не открыть папку — " + e);
    }
  }

  private static JsonObject state() {
    count();
    JsonObject o = new JsonObject();
    o.addProperty("on", on);
    o.addProperty("lags", lags);
    o.addProperty("since", since);
    o.addProperty("size", size());
    o.addProperty("busy", busy);
    if (saved != null) o.addProperty("saved", saved);
    if (err != null) o.addProperty("err", err);
    return o;
  }

  private static void push() {
    Tweaks.broadcast("window.__cct&&__cct.journal&&__cct.journal(" + state() + ")");
  }

  private static void pushTo(JBCefBrowserBase b) {
    Tweaks.exec(b, "window.__cct&&__cct.journal&&__cct.journal(" + state() + ")");
  }

  // ---------- выгрузка ----------

  private static Path export(String pageMeta) throws IOException {
    Path home = Path.of(System.getProperty("user.home"));
    Path downloads = home.resolve("Downloads");
    Path dst = (Files.isDirectory(downloads) ? downloads : home)
        .resolve("cc-gui-tweaks-logs-" + LocalDateTime.now().format(FILE_TS) + ".zip");
    Path logs = Path.of(PathManager.getLogPath());
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(dst))) {
      put(zip, "summary.txt", summary(pageMeta));
      put(zip, "lags.txt", readable());
      for (Path p : new Path[]{old(), file()}) {
        if (Files.exists(p)) put(zip, p.getFileName().toString(), scrub(Files.readString(p, StandardCharsets.UTF_8)));
      }
      put(zip, "idea-filtered.log", filteredIdeaLog(logs));
      int n = 0;
      for (Path p : newest(logs, "jcef_chromium_", false)) {
        if (n++ >= 3) break;
        put(zip, "jcef/" + p.getFileName(), scrub(tail(p, 400)));
      }
      long budget = 3L << 20;
      n = 0;
      for (Path d : newest(logs, "threadDumps-freeze-", true)) {
        if (n++ >= 3 || budget <= 0) break;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(d)) {
          for (Path f : ds) {
            if (!Files.isRegularFile(f) || budget <= 0) continue;
            long sz = Files.size(f);
            if (sz > budget) continue;
            budget -= sz;
            put(zip, "freezes/" + d.getFileName() + "/" + f.getFileName(), scrub(Files.readString(f, StandardCharsets.UTF_8)));
          }
        } catch (Exception ignored) {}
      }
    }
    return dst;
  }

  private static void put(ZipOutputStream zip, String name, String text) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(text.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static List<Path> newest(Path dir, String prefix, boolean dirs) {
    List<Path> out = new ArrayList<>();
    try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, prefix + "*")) {
      for (Path p : ds) if (Files.isDirectory(p) == dirs) out.add(p);
    } catch (IOException ignored) {}
    out.sort(Comparator.comparingLong((Path p) -> {
      try { return Files.getLastModifiedTime(p).toMillis(); } catch (IOException e) { return 0; }
    }).reversed());
    return out;
  }

  private static String tail(Path p, int lines) {
    try {
      List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
      return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    } catch (Exception e) {
      return "(не читается: " + e + ")";
    }
  }

  /** Записи idea.log, относящиеся к чату, JCEF, зависаниям и ошибкам, — вместе со стеками; последние ~5000 строк. */
  private static String filteredIdeaLog(Path logs) {
    ArrayDeque<String> keep = new ArrayDeque<>();
    for (String name : new String[]{"idea.1.log", "idea.log"}) {
      Path p = logs.resolve(name);
      if (!Files.exists(p)) continue;
      keep.add("===== " + name + " =====");
      try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
        String s;
        boolean take = false;
        int cont = 0;
        while ((s = r.readLine()) != null) {
          if (LOG_HEAD.matcher(s).lookingAt()) {
            take = LOG_KEEP.matcher(s).find();
            cont = 0;
            if (take) keep.add(s);
          } else if (take && cont++ < 60) {
            keep.add(s);
          }
          while (keep.size() > 5000) keep.poll();
        }
      } catch (Exception e) {
        keep.add("(не читается: " + e + ")");
      }
    }
    return scrub(String.join("\n", keep));
  }

  private static String summary(String pageMeta) {
    StringBuilder sb = new StringBuilder();
    sb.append("CC GUI Tweaks — выгрузка логов ").append(LocalDateTime.now().format(TS)).append("\n\n");
    sb.append("Плагин: ").append(Updater.currentVersion()).append('\n');
    try {
      ApplicationInfo ai = ApplicationInfo.getInstance();
      sb.append("IDE: ").append(ai.getFullApplicationName()).append(" (").append(ai.getBuild().asString()).append(")\n");
    } catch (Throwable ignored) {}
    PluginId ccId = PluginId.getId("com.github.idea-claude-code-gui");
    IdeaPluginDescriptor cc = PluginManagerCore.getPlugin(ccId);
    sb.append("CC GUI: ").append(cc == null ? "не установлен" : cc.getVersion() + (PluginManagerCore.isDisabled(ccId) ? " (выключен)" : "")).append('\n');
    sb.append("Java: ").append(System.getProperty("java.runtime.version")).append(' ').append(System.getProperty("java.vendor")).append('\n');
    sb.append("ОС: ").append(System.getProperty("os.name")).append(' ').append(System.getProperty("os.version"))
        .append(' ').append(System.getProperty("os.arch")).append(", ядер: ").append(Runtime.getRuntime().availableProcessors()).append('\n');
    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
    sb.append("Память IDE: ").append(heap.getUsed() >> 20).append(" / ").append(heap.getMax() >> 20).append(" МБ\n");
    sb.append("IDE работает: ").append(ManagementFactory.getRuntimeMXBean().getUptime() / 60000).append(" мин\n");
    sb.append("Страница чата: ").append(scrub(pageMeta)).append("\n\n");
    sb.append("Реестр JCEF: ").append(RegistryTweaks.state()).append('\n');
    JsonObject s = JsonParser.parseString(Settings.forPage()).getAsJsonObject();
    JsonObject spotify = s.getAsJsonObject("spotify");
    if (spotify != null && spotify.has("clientId") && !spotify.get("clientId").getAsString().isEmpty()) spotify.addProperty("clientId", "***");
    sb.append("Настройки плагина: ").append(s).append('\n');
    sb.append("Журнал лагов: ").append(on ? "включён" : "выключен").append(", лагов записано: ").append(lags)
        .append(since > 0 ? ", с " + fmt(since) : "").append("\n\n");
    sb.append("Дочерние процессы IDE сейчас:\n");
    try {
      ProcessHandle.current().descendants().limit(40).forEach(p -> sb.append("  ").append(p.pid()).append("  ")
          .append(p.info().command().map(c -> Path.of(c).getFileName().toString()).orElse("?"))
          .append("  cpu ").append(p.info().totalCpuDuration().map(d -> d.toSeconds() + " с").orElse("?")).append('\n'));
    } catch (Throwable ignored) {}
    return sb.toString();
  }

  /** Журнал по-человечески: одна строка на событие, старые сверху. */
  private static String readable() {
    StringBuilder sb = new StringBuilder();
    sb.append("Время                ОТКУДА  Что было\n");
    for (Path p : new Path[]{old(), file()}) {
      if (!Files.exists(p)) continue;
      try (BufferedReader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
        String s;
        while ((s = r.readLine()) != null) {
          try {
            JsonObject o = JsonParser.parseString(s).getAsJsonObject();
            String line = describe(o);
            if (line != null) sb.append(fmt(o.get("ts").getAsLong())).append("  ").append(line).append('\n');
          } catch (Exception ignored) {}
        }
      } catch (IOException ignored) {}
    }
    return scrub(sb.toString());
  }

  private static String describe(JsonObject o) {
    String k = s(o, "k");
    switch (k) {
      case "start":
        return "----   журнал включён (плагин " + s(o, "ver") + ")";
      case "edt": {
        String st = s(o, "stack");
        return "IDE    окно IDE не отвечало " + ms(o, "d") + (st.isEmpty() ? "" : "\n" + indent(st));
      }
      case "frame": {
        StringBuilder sb = new StringBuilder("ЧАТ    долгий кадр " + ms(o, "d"));
        if (o.has("b")) sb.append(" (блокировка ").append(ms(o, "b")).append(')');
        if (o.has("sl")) sb.append(" · стили/раскладка ").append(ms(o, "sl"));
        if (o.has("n")) sb.append(" · узлов ").append(s(o, "n"));
        if (o.has("vis") && !o.get("vis").getAsBoolean()) sb.append(" · чат скрыт");
        if (o.has("s") && o.get("s").isJsonArray()) {
          for (JsonElement e : o.getAsJsonArray("s")) {
            if (!e.isJsonObject()) continue;
            JsonObject x = e.getAsJsonObject();
            sb.append("\n        скрипт ").append(ms(x, "d")).append("  ").append(s(x, "i"));
            if (!s(x, "fn").isEmpty()) sb.append("  ").append(s(x, "fn"));
            if (!s(x, "f").isEmpty()) sb.append("  @ ").append(s(x, "f")).append(x.has("c") ? ":" + s(x, "c") : "");
            if (x.has("l") && x.get("l").getAsDouble() > 0) sb.append("  (принуд. раскладка ").append(ms(x, "l")).append(')');
          }
        }
        return sb.toString();
      }
      case "stall":
        return "ЧАТ    страница не обновлялась " + ms(o, "d");
      case "page":
        return "ЧАТ    за минуту: кадров >50 мс — " + s(o, "lf") + " (всего " + ms(o, "lfms") + ", самый долгий " + ms(o, "max") + ")"
            + (o.has("n") ? " · узлов " + s(o, "n") : "") + (o.has("mem") ? " · память страницы " + s(o, "mem") + " МБ" : "");
      case "min": {
        StringBuilder sb = new StringBuilder("CPU    IDE " + s(o, "cpu") + "% · система " + s(o, "sys") + "% · память " + s(o, "heapMb") + "/"
            + s(o, "heapMaxMb") + " МБ · GC " + s(o, "gcMs") + " мс · окно IDE: лагов " + s(o, "edtLags") + ", макс " + ms(o, "edtMax"));
        if (o.has("kids") && o.get("kids").isJsonArray() && !o.getAsJsonArray("kids").isEmpty()) {
          sb.append(" · процессы:");
          for (JsonElement e : o.getAsJsonArray("kids")) {
            JsonObject x = e.getAsJsonObject();
            sb.append(' ').append(s(x, "name")).append(' ').append(s(x, "cpu")).append('%');
          }
        }
        return sb.toString();
      }
      default:
        return null;
    }
  }

  private static String s(JsonObject o, String k) {
    JsonElement e = o.get(k);
    return e == null || e.isJsonNull() ? "" : e.isJsonPrimitive() ? e.getAsString() : e.toString();
  }

  private static String ms(JsonObject o, String k) {
    try {
      long v = Math.round(o.get(k).getAsDouble());
      return v >= 1000 ? String.format(java.util.Locale.ROOT, "%.1f с", v / 1000.0) : v + " мс";
    } catch (Exception e) {
      return "?";
    }
  }

  private static String indent(String st) {
    StringBuilder sb = new StringBuilder();
    for (String l : st.split("\n")) if (!l.isBlank()) sb.append("          ").append(l).append('\n');
    return sb.toString().stripTrailing();
  }

  private static String fmt(long ts) {
    return LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneId.systemDefault()).format(TS);
  }

  private static final Pattern[] SECRETS = {
      Pattern.compile("sk-ant-[A-Za-z0-9_\\-]+"),
      Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-~+/=]{8,}"),
      Pattern.compile("(?i)(\"?(?:access_?token|refresh_?token|id_?token|client_?secret|api_?key|password|authorization)\"?\\s*[:=]\\s*\"?)[^\"\\s,;}]{4,}"),
  };

  /** Убирает токены и путь к профилю пользователя. */
  static String scrub(String s) {
    s = SECRETS[0].matcher(s).replaceAll("sk-ant-***");
    s = SECRETS[1].matcher(s).replaceAll("$1***");
    s = SECRETS[2].matcher(s).replaceAll("$1***");
    return home(s);
  }

  private static String home(String s) {
    String h = System.getProperty("user.home");
    if (h == null || h.length() < 4) return s;
    s = s.replaceAll("(?i)" + Pattern.quote(h), Matcher.quoteReplacement("~"));
    s = s.replaceAll("(?i)" + Pattern.quote(h.replace('\\', '/')), Matcher.quoteReplacement("~"));
    return s.replaceAll("(?i)" + Pattern.quote(h.replace("\\", "\\\\")), Matcher.quoteReplacement("~"));
  }
}
