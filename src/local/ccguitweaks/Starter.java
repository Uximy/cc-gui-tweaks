package local.ccguitweaks;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.plugins.DynamicPluginListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import org.jetbrains.annotations.NotNull;

/**
 * Запуск и остановка: при старте IDE (appStarted) и при горячей загрузке/выгрузке
 * этого плагина без перезапуска (pluginLoaded / beforePluginUnload).
 */
public final class Starter implements AppLifecycleListener, DynamicPluginListener {
  @Override
  public void appStarted() {
    Tweaks.start();
  }

  @Override
  public void pluginLoaded(@NotNull IdeaPluginDescriptor plugin) {
    if (isSelf(plugin)) Tweaks.start();
  }

  @Override
  public void beforePluginUnload(@NotNull IdeaPluginDescriptor plugin, boolean isUpdate) {
    if (isSelf(plugin)) Tweaks.stop();
  }

  private static boolean isSelf(IdeaPluginDescriptor plugin) {
    return Tweaks.ID.equals(plugin.getPluginId().getIdString());
  }
}
