package local.ccguitweaks;

import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.util.text.VersionComparatorUtil;

import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Горячая замена плагина без перезапуска IDE.
 *
 * build.py кладёт свежий JAR в {@code <config>/cc-gui-tweaks/update/cc-gui-tweaks.jar}. Раз в 2 с мы его
 * забираем и просим IDE выгрузить текущую версию и поставить новую — штатными DynamicPlugins.unloadPlugin и
 * PluginInstaller.installAndLoadDynamicPlugin (то же, что «Install Plugin from Disk»).
 *
 * Оба вызова уходят в EDT как Runnable из MethodHandleProxies: в стеке выгрузки нет ни одного нашего кадра,
 * поэтому старый загрузчик классов ничем не удерживается. Новая версия ложится в plugins/ под именем с
 * версией, а лишние старые копии удаляет уже новая версия при старте.
 *
 * Прежнее имя плагина — «Spotify Mini for CC GUI» (id local.spotify-mini.ccgui); его копии и папки
 * тоже убираются, когда он уже не загружен.
 */
final class Updater {
  private static final String LEGACY_ID = "local.spotify-mini.ccgui";
  private static final Pattern VERSION = Pattern.compile("<version>\\s*([^<\\s]+)\\s*</version>");
  private static final Pattern ID = Pattern.compile("<id>\\s*([^<\\s]+)\\s*</id>");

  private static ScheduledFuture<?> task;
  private static volatile boolean scheduled;

  private Updater() {}

  static Path dropFile() {
    return PathManager.getConfigDir().resolve("cc-gui-tweaks").resolve("update").resolve("cc-gui-tweaks.jar");
  }

  private static Path stageDir() {
    return PathManager.getSystemDir().resolve("cc-gui-tweaks").resolve("stage");
  }

  static String currentVersion() {
    IdeaPluginDescriptor d = PluginManagerCore.getPlugin(PluginId.getId(Tweaks.ID));
    return d == null ? "?" : d.getVersion();
  }

  static synchronized void start() {
    scheduled = false;
    AppExecutorUtil.getAppExecutorService().execute(Updater::cleanupOldCopies);
    task = AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(Updater::check, 3, 2, TimeUnit.SECONDS);
  }

  static synchronized void stop() {
    if (task != null) task.cancel(false);
    task = null;
  }

  private static void check() {
    if (scheduled) return;
    Path drop = dropFile();
    try {
      if (!Files.isRegularFile(drop)) return;
      // Ждём, пока файл допишут.
      if (System.currentTimeMillis() - Files.getLastModifiedTime(drop).toMillis() < 1000) return;
      String[] meta = readMeta(drop);
      if (meta == null || !Tweaks.ID.equals(meta[0])) throw new IOException("в " + drop + " не CC GUI Tweaks");
      Path staged = stageDir().resolve("cc-gui-tweaks-" + meta[1] + ".jar");
      Files.createDirectories(staged.getParent());
      Files.move(drop, staged, StandardCopyOption.REPLACE_EXISTING);
      reload(staged, meta[1]);
    } catch (Throwable t) {
      Tweaks.LOG.warn(Tweaks.P + "обновление не удалось", t);
      try { Files.deleteIfExists(drop); } catch (IOException ignored) {}
    }
  }

  private static void reload(Path staged, String version) throws Throwable {
    ClassLoader core = PluginManagerCore.class.getClassLoader();
    Class<?> loader = Class.forName("com.intellij.ide.plugins.PluginDescriptorLoader", true, core);
    Class<?> buildNumber = Class.forName("com.intellij.openapi.util.BuildNumber", true, core);
    Object newDesc = loader.getMethod("loadDescriptorFromArtifact", Path.class, buildNumber).invoke(null, staged, null);
    if (newDesc == null) throw new IOException("не читается plugin.xml в " + staged);
    IdeaPluginDescriptor oldDesc = PluginManagerCore.getPlugin(PluginId.getId(Tweaks.ID));
    if (oldDesc == null) throw new IOException("текущая версия плагина не найдена");

    Class<?> dynamic = Class.forName("com.intellij.ide.plugins.DynamicPlugins", true, core);
    Object dynamicInstance = dynamic.getField("INSTANCE").get(null);
    MethodHandle unload = MethodHandles.insertArguments(
        MethodHandles.publicLookup().unreflect(method(dynamic, "unloadPlugin", 1)), 0, dynamicInstance, oldDesc);

    Class<?> installer = Class.forName("com.intellij.ide.plugins.PluginInstaller", true, core);
    Class<?> descImpl = Class.forName("com.intellij.ide.plugins.IdeaPluginDescriptorImpl", true, core);
    MethodHandle install = MethodHandles.insertArguments(
        MethodHandles.publicLookup().unreflect(installer.getMethod("installAndLoadDynamicPlugin", Path.class, descImpl)), 0, staged, newDesc);

    Runnable unloadTask = MethodHandleProxies.asInterfaceInstance(Runnable.class, unload.asType(MethodType.methodType(void.class)));
    Runnable installTask = MethodHandleProxies.asInterfaceInstance(Runnable.class, install.asType(MethodType.methodType(void.class)));

    scheduled = true;
    Tweaks.LOG.info(Tweaks.P + "перезагрузка " + oldDesc.getVersion() + " -> " + version);
    // nonModal: install не запустится, пока идёт модальный прогресс выгрузки.
    ApplicationManager.getApplication().invokeLater(unloadTask, ModalityState.nonModal());
    ApplicationManager.getApplication().invokeLater(installTask, ModalityState.nonModal());
  }

  private static Method method(Class<?> c, String name, int params) throws NoSuchMethodException {
    for (Method m : c.getMethods()) if (m.getName().equals(name) && m.getParameterCount() == params) return m;
    throw new NoSuchMethodException(c.getName() + "." + name);
  }

  /**
   * Удаляет из plugins/ копии этого плагина не новее загруженной и копии старого «Spotify Mini»,
   * если тот уже не загружен. Заблокированные файлы — до следующего раза.
   */
  private static void cleanupOldCopies() {
    try {
      IdeaPluginDescriptor self = PluginManagerCore.getPlugin(PluginId.getId(Tweaks.ID));
      if (self == null || self.getPluginPath() == null) return;
      Path current = self.getPluginPath().toAbsolutePath().normalize();
      // getPlugin после динамической выгрузки ещё может вернуть дескриптор — смотрим именно «загружен».
      boolean legacyLoaded = PluginManagerCore.isLoaded(PluginId.getId(LEGACY_ID));
      try (DirectoryStream<Path> ds = Files.newDirectoryStream(PathManager.getPluginsDir(),
          p -> { String n = p.getFileName().toString(); return n.startsWith("cc-gui-tweaks") || n.startsWith("spotify-mini-ccgui"); })) {
        for (Path p : ds) {
          Path abs = p.toAbsolutePath().normalize();
          if (abs.equals(current) || current.startsWith(abs)) continue;
          String[] meta = metaOf(abs);
          boolean delete;
          if (meta == null) delete = false;                       // не наш файл или битый — не трогаем
          else if (Tweaks.ID.equals(meta[0])) delete = VersionComparatorUtil.compare(meta[1], self.getVersion()) <= 0;
          else delete = LEGACY_ID.equals(meta[0]) && !legacyLoaded;
          if (delete && deleteRecursively(abs)) Tweaks.LOG.info(Tweaks.P + "удалена старая копия " + abs);
        }
      }
      Path stage = stageDir();
      if (Files.isDirectory(stage)) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(stage)) {
          for (Path p : ds) deleteRecursively(p);
        }
      }
      if (!legacyLoaded) {
        // Рабочие папки «Spotify Mini»: его папка обновлений и распакованный хелпер.
        for (Path old : new Path[] {PathManager.getConfigDir().resolve("spotify-mini"), PathManager.getSystemDir().resolve("spotify-mini")}) {
          if (Files.exists(old) && deleteRecursively(old)) Tweaks.LOG.info(Tweaks.P + "удалена папка старой версии " + old);
        }
      }
    } catch (Throwable t) {
      Tweaks.LOG.info(Tweaks.P + "уборка старых копий: " + t);
    }
  }

  /** {id, version} плагина в JAR или папке с lib/*.jar. */
  private static String[] metaOf(Path pluginPath) {
    try {
      if (Files.isRegularFile(pluginPath)) return readMeta(pluginPath);
      Path lib = pluginPath.resolve("lib");
      if (!Files.isDirectory(lib)) return null;
      try (Stream<Path> s = Files.list(lib)) {
        for (Path jar : (Iterable<Path>) s.filter(f -> f.toString().endsWith(".jar"))::iterator) {
          String[] m = readMeta(jar);
          if (m != null) return m;
        }
      }
    } catch (IOException ignored) {
    }
    return null;
  }

  private static String[] readMeta(Path jar) throws IOException {
    try (ZipFile z = new ZipFile(jar.toFile())) {
      ZipEntry e = z.getEntry("META-INF/plugin.xml");
      if (e == null) return null;
      try (InputStream in = z.getInputStream(e)) {
        String xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        Matcher id = ID.matcher(xml), v = VERSION.matcher(xml);
        return id.find() && v.find() ? new String[] {StringUtil.trim(id.group(1)), StringUtil.trim(v.group(1))} : null;
      }
    }
  }

  private static boolean deleteRecursively(Path p) {
    try (Stream<Path> s = Files.walk(p)) {
      for (Path f : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.delete(f);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
