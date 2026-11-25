"""Сборка плагина CC GUI Tweaks без Gradle: javac из JBR + библиотеки установленной IDE.

  python -I build.py                 — собрать build/cc-gui-tweaks.jar
  python -I build.py install <cfg>   — собрать и поставить (cfg — папка конфига IDE, напр. %APPDATA%/JetBrains/WebStorm2026.3)

Если IDE запущена, плагин обновляется без перезапуска: JAR кладётся в <cfg>/cc-gui-tweaks/update/,
работающая версия его подхватывает и перезагружает себя (см. Updater.java). Если IDE закрыта — обычная установка.

Прежнее имя плагина — Spotify Mini for CC GUI (spotify-mini-ccgui). Если работает он, JAR уходит в его папку
обновлений: старый Updater выгружает себя и ставит CC GUI Tweaks, а тот при старте убирает старые копии.
"""
import glob
import os
import re
import shutil
import subprocess
import sys
import time
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
IDE = os.environ.get('IDE_HOME', r'C:\Program Files\JetBrains\WebStorm 2024.3.2.1')
OUT = os.path.join(HERE, 'build')
JAR = os.path.join(OUT, 'cc-gui-tweaks.jar')
NAME = 'cc-gui-tweaks'
ID = 'local.cc-gui-tweaks'
LEGACY = 'spotify-mini-ccgui'


def build():
    classes = os.path.join(OUT, 'classes')
    shutil.rmtree(classes, ignore_errors=True)
    os.makedirs(classes)
    cp = os.pathsep.join([os.path.join(IDE, 'lib', '*'), os.path.join(IDE, 'plugins', 'jcef-plugin', 'lib', 'modules', '*')])
    sources = glob.glob(os.path.join(HERE, 'src', '**', '*.java'), recursive=True)
    subprocess.run([os.path.join(IDE, 'jbr', 'bin', 'javac.exe'), '--release', '21', '-encoding', 'UTF-8', '-nowarn',
                    '-cp', cp, '-d', classes, *sources], check=True)
    # Каждая сборка — новая версия: по ней IDE выбирает свежую копию, а Updater называет файлы.
    version = '1.0.' + time.strftime('%y%m%d%H%M%S')
    resources = os.path.join(HERE, 'resources')
    with zipfile.ZipFile(JAR, 'w', zipfile.ZIP_DEFLATED) as z:
        for root in (classes, resources):
            for path in glob.glob(os.path.join(root, '**', '*'), recursive=True):
                if not os.path.isfile(path):
                    continue
                arc = os.path.relpath(path, root).replace(os.sep, '/')
                if arc == 'META-INF/plugin.xml':
                    xml = open(path, encoding='utf-8').read()
                    xml, n = re.subn(r'<version>[^<]*</version>', '<version>' + version + '</version>', xml, count=1)
                    assert n == 1, 'нет <version> в plugin.xml'
                    z.writestr(arc, xml.encode('utf-8'))
                else:
                    z.write(path, arc)
    print('собран', JAR, 'версия', version)
    return version


def installed_copies(plugins, name=NAME):
    return glob.glob(os.path.join(plugins, name)) + glob.glob(os.path.join(plugins, name + '-*.jar'))


def plugin_id(path):
    """id из plugin.xml в JAR или в папке плагина с lib/*.jar."""
    jars = [path] if os.path.isfile(path) else glob.glob(os.path.join(path, 'lib', '*.jar'))
    for j in jars:
        try:
            with zipfile.ZipFile(j) as z:
                m = re.search(r'<id>\s*([^<\s]+)\s*</id>', z.read('META-INF/plugin.xml').decode('utf-8', 'replace'))
                if m:
                    return m.group(1)
        except (OSError, KeyError, zipfile.BadZipFile):
            pass
    return None


def ide_running(cfg):
    """Пока IDE работает, в папке конфига лежит .lock с её PID. JAR-ы плагинов она не блокирует."""
    try:
        pid = int(open(os.path.join(cfg, '.lock'), encoding='ascii').read().strip())
    except (OSError, ValueError):
        return False
    import ctypes
    k32 = ctypes.windll.kernel32
    h = k32.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not h:
        return False
    try:
        code = ctypes.c_ulong()
        return bool(k32.GetExitCodeProcess(h, ctypes.byref(code))) and code.value == 259  # STILL_ACTIVE
    finally:
        k32.CloseHandle(h)


def idea_log(cfg):
    local = os.environ.get('LOCALAPPDATA', '')
    return os.path.join(local, 'JetBrains', os.path.basename(os.path.normpath(cfg)), 'log', 'idea.log')


def wait_for(pred, seconds):
    end = time.time() + seconds
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.5)
    return False


def hot_reload(cfg, version):
    """Отдаёт JAR работающей IDE. True — новая версия запустилась."""
    plugins = os.path.join(cfg, 'plugins')
    ours = any(plugin_id(c) == ID for c in installed_copies(plugins) + installed_copies(plugins, LEGACY))
    if ours:
        drop_dir, drop_name = os.path.join(cfg, 'cc-gui-tweaks', 'update'), NAME + '.jar'
    else:
        # Работает ещё Spotify Mini — его Updater сам заменит себя на CC GUI Tweaks.
        drop_dir, drop_name = os.path.join(cfg, 'spotify-mini', 'update'), LEGACY + '.jar'
        print('стоит прежняя версия (Spotify Mini) — переход на CC GUI Tweaks через её обновление')
    os.makedirs(drop_dir, exist_ok=True)
    drop = os.path.join(drop_dir, drop_name)
    tmp = drop + '.tmp'
    shutil.copyfile(JAR, tmp)
    os.replace(tmp, drop)
    print('IDE запущена — передаю обновление без перезапуска…')
    if not wait_for(lambda: not os.path.exists(drop), 15):
        # Запущенная версия без Updater (или IDE зависла) — забираем файл обратно.
        try:
            os.remove(drop)
        except OSError:
            pass
        return False
    log = idea_log(cfg)
    start = os.path.getsize(log) if os.path.exists(log) else 0
    mark = 'cc-gui-tweaks: запущен, версия ' + version
    failed = ('cc-gui-tweaks: обновление не удалось', 'spotify-mini: обновление не удалось')

    def started():
        try:
            with open(log, 'rb') as f:
                f.seek(max(0, start - 65536))
                tail = f.read().decode('utf-8', 'replace')
        except OSError:
            return False
        return mark in tail or any(f in tail for f in failed)

    if wait_for(started, 40):
        with open(log, 'rb') as f:
            f.seek(max(0, start - 65536))
            tail = f.read().decode('utf-8', 'replace')
        if mark in tail:
            print('перезагружен без перезапуска IDE, версия', version)
            return True
        print('Updater сообщил об ошибке — см. idea.log (cc-gui-tweaks: / spotify-mini:)')
        return False
    print('файл забран, но запуск новой версии в idea.log не виден — проверь idea.log (cc-gui-tweaks:)')
    return True


def install(cfg, version):
    plugins = os.path.join(cfg, 'plugins')
    copies = installed_copies(plugins) + installed_copies(plugins, LEGACY)
    running = ide_running(cfg)
    if running and hot_reload(cfg, version):
        return
    lib = os.path.join(plugins, NAME, 'lib')
    os.makedirs(lib, exist_ok=True)
    dst = os.path.join(lib, NAME + '.jar')
    if running:
        # Горячая замена не сработала (работает версия без Updater): ставим рядом, подхватится при перезапуске.
        shutil.copyfile(JAR, dst + '.tmp')
        os.replace(dst + '.tmp', dst)
        print('установлен', dst, '— перезапусти IDE один раз, дальше обновления пойдут без перезапуска')
        if installed_copies(plugins, LEGACY):
            print('внимание: в plugins/ ещё лежит Spotify Mini — при закрытой IDE запусти install ещё раз, он его уберёт')
        return
    for c in copies:
        if os.path.normpath(c) != os.path.normpath(os.path.join(plugins, NAME)):
            os.remove(c) if os.path.isfile(c) else shutil.rmtree(c, ignore_errors=True)
    for j in glob.glob(os.path.join(lib, '*')):
        os.remove(j)
    shutil.copyfile(JAR, dst)
    print('установлен', dst, '(IDE закрыта — подхватится при запуске)')


if __name__ == '__main__':
    v = build()
    if len(sys.argv) > 2 and sys.argv[1] == 'install':
        install(sys.argv[2], v)
