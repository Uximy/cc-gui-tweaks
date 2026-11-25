# CC GUI Tweaks: локальный источник данных для модуля Spotify.
# Читает системную медиа-сессию Windows (SMTC) приложения Spotify и его громкость в микшере Windows:
#   GET  /now              -> JSON с треком и громкостью
#   GET  /cover?k=<key>    -> обложка
#   POST /cmd?c=toggle|next|prev
#   POST /seek?ms=<позиция>   -> перемотка
#   POST /vol?v=0..100     -> громкость Spotify в микшере (заодно снимает «без звука»)
#   POST /mute?m=0|1
#   POST /quit             -> выход (модуль выключили)
# Слушает только localhost. Второй экземпляр сразу выходит (порт занят). Без запросов дольше $IdleExit с — выходит сам.
param([int]$Port = 43117, [int]$IdleExit = 90)

$ErrorActionPreference = 'Stop'
$Version = 3
$Dir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LogFile = Join-Path $Dir 'helper.log'

function Log([string]$msg) {
  try {
    if ((Test-Path $LogFile) -and (Get-Item $LogFile).Length -gt 256KB) { Remove-Item $LogFile -Force }
    Add-Content -Path $LogFile -Value ("{0:yyyy-MM-dd HH:mm:ss} {1}" -f (Get-Date), $msg) -Encoding UTF8
  } catch {}
}

Add-Type -AssemblyName System.Runtime.WindowsRuntime
[Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime] | Out-Null
[Windows.Storage.Streams.IRandomAccessStreamWithContentType, Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null

# Громкость приложения в микшере Windows (Core Audio). C# 5 — другого компилятора в PowerShell 5.1 нет.
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;

namespace CctAudio {
  [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface IMMDeviceEnumerator {
    [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
  }
  [ComImport, Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface IMMDeviceCollection {
    [PreserveSig] int GetCount(out int count);
    [PreserveSig] int Item(int index, out IMMDevice device);
  }
  [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface IMMDevice {
    [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object iface);
  }
  [ComImport, Guid("77AA99A0-1BD6-484F-8BC7-2C654C9A9B6F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface IAudioSessionManager2 {
    [PreserveSig] int GetAudioSessionControl(IntPtr sessionGuid, int flags, out IntPtr ctl);
    [PreserveSig] int GetSimpleAudioVolume(IntPtr sessionGuid, int flags, out IntPtr vol);
    [PreserveSig] int GetSessionEnumerator(out IAudioSessionEnumerator sessions);
  }
  [ComImport, Guid("E2F5BB11-0570-40CA-ACDD-3AA01277DEE8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface IAudioSessionEnumerator {
    [PreserveSig] int GetCount(out int count);
    [PreserveSig] int GetSession(int index, out IAudioSessionControl2 session);
  }
  [ComImport, Guid("bfb7ff88-7239-4fc9-8fa2-07c950be9c6d"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface IAudioSessionControl2 {
    [PreserveSig] int GetState(out int state);
    [PreserveSig] int GetDisplayName(out IntPtr name);
    [PreserveSig] int SetDisplayName(IntPtr name, IntPtr ctx);
    [PreserveSig] int GetIconPath(out IntPtr path);
    [PreserveSig] int SetIconPath(IntPtr path, IntPtr ctx);
    [PreserveSig] int GetGroupingParam(out Guid g);
    [PreserveSig] int SetGroupingParam(IntPtr g, IntPtr ctx);
    [PreserveSig] int RegisterAudioSessionNotification(IntPtr n);
    [PreserveSig] int UnregisterAudioSessionNotification(IntPtr n);
    [PreserveSig] int GetSessionIdentifier(out IntPtr id);
    [PreserveSig] int GetSessionInstanceIdentifier(out IntPtr id);
    [PreserveSig] int GetProcessId(out uint pid);
  }
  [ComImport, Guid("87CE5498-68D6-44E5-9215-6DA47EF883D8"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  public interface ISimpleAudioVolume {
    [PreserveSig] int SetMasterVolume(float level, ref Guid ctx);
    [PreserveSig] int GetMasterVolume(out float level);
    [PreserveSig] int SetMute([MarshalAs(UnmanagedType.Bool)] bool mute, ref Guid ctx);
    [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool mute);
  }
  [ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
  class MMDeviceEnumeratorCom {}

  public static class Mixer {
    // Все аудиосеансы процессов с этим именем на всех активных устройствах вывода.
    static List<ISimpleAudioVolume> Find(string process) {
      List<ISimpleAudioVolume> list = new List<ISimpleAudioVolume>();
      IMMDeviceEnumerator en = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
      IMMDeviceCollection devs;
      if (en.EnumAudioEndpoints(0 /*eRender*/, 1 /*ACTIVE*/, out devs) != 0) return list;
      int nd; devs.GetCount(out nd);
      Guid iid = typeof(IAudioSessionManager2).GUID;
      for (int d = 0; d < nd; d++) {
        IMMDevice dev; object o;
        if (devs.Item(d, out dev) != 0 || dev.Activate(ref iid, 23 /*CLSCTX_ALL*/, IntPtr.Zero, out o) != 0) continue;
        IAudioSessionEnumerator se;
        if (((IAudioSessionManager2)o).GetSessionEnumerator(out se) != 0) continue;
        int n; se.GetCount(out n);
        for (int i = 0; i < n; i++) {
          IAudioSessionControl2 c;
          if (se.GetSession(i, out c) != 0 || c == null) continue;
          uint pid;
          if (c.GetProcessId(out pid) != 0 || pid == 0) continue;
          string name = null;
          try { using (Process p = Process.GetProcessById((int)pid)) name = p.ProcessName; } catch {}
          if (name != null && string.Equals(name, process, StringComparison.OrdinalIgnoreCase)) list.Add((ISimpleAudioVolume)c);
        }
      }
      return list;
    }
    // [громкость 0..1, 1 = без звука] или null, если у процесса нет сеанса.
    public static float[] Get(string process) {
      foreach (ISimpleAudioVolume v in Find(process)) {
        float l; bool m;
        if (v.GetMasterVolume(out l) != 0 || v.GetMute(out m) != 0) continue;
        return new float[] { l, m ? 1f : 0f };
      }
      return null;
    }
    public static bool SetVolume(string process, float level) {
      Guid g = Guid.Empty; bool any = false;
      foreach (ISimpleAudioVolume v in Find(process)) { v.SetMasterVolume(level, ref g); v.SetMute(false, ref g); any = true; }
      return any;
    }
    public static bool SetMute(string process, bool mute) {
      Guid g = Guid.Empty; bool any = false;
      foreach (ISimpleAudioVolume v in Find(process)) { v.SetMute(mute, ref g); any = true; }
      return any;
    }
  }
}
'@

$asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
} | Select-Object -First 1

$AsStreamForRead = [System.IO.WindowsRuntimeStreamExtensions].GetMethods() | Where-Object {
  $_.Name -eq 'AsStreamForRead' -and $_.GetParameters().Count -eq 1
} | Select-Object -First 1

function Await($op, [Type]$type) {
  $task = $asTask.MakeGenericMethod($type).Invoke($null, @($op))
  if (-not $task.Wait(3000)) { throw 'SMTC timeout' }
  $task.Result
}

$MgrType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]
$PropsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties]
$script:mgr = $null

function Get-SpotifySession {
  if (-not $script:mgr) { $script:mgr = Await ($MgrType::RequestAsync()) $MgrType }
  foreach ($s in $script:mgr.GetSessions()) {
    if ($s.SourceAppUserModelId -match 'spotify') { return $s }
  }
  $null
}

$script:cover = @{ key = $null; bytes = $null; type = 'image/jpeg' }

function Read-Cover($thumb, [string]$key) {
  if ($script:cover.key -eq $key) { return }
  $script:cover = @{ key = $key; bytes = $null; type = 'image/jpeg' }
  if (-not $thumb) { return }
  try {
    $stream = Await ($thumb.OpenReadAsync()) ([Windows.Storage.Streams.IRandomAccessStreamWithContentType])
    # Поток приходит как __ComObject: PowerShell не подбирает перегрузку, поэтому через рефлексию.
    $net = $AsStreamForRead.Invoke($null, @($stream))
    $ms = New-Object System.IO.MemoryStream
    $net.CopyTo($ms)
    $net.Dispose()
    $bytes = $ms.ToArray()
    $script:cover.bytes = $bytes
    if ($bytes.Length -gt 3 -and $bytes[0] -eq 0x89 -and $bytes[1] -eq 0x50) { $script:cover.type = 'image/png' }
  } catch { Log "cover: $($_.Exception.Message)" }
}

function Get-Volume {
  try {
    $v = [CctAudio.Mixer]::Get('Spotify')
    if ($v) { return @{ vol = [int][Math]::Round($v[0] * 100); muted = ($v[1] -gt 0) } }
  } catch { Log "громкость: $($_.Exception.Message)" }
  @{ vol = -1; muted = $false }
}

function Get-Now {
  $s = Get-SpotifySession
  if (-not $s) { return @{ v = $Version; ok = $true; active = $false } }
  $p = Await ($s.TryGetMediaPropertiesAsync()) $PropsType
  $tl = $s.GetTimelineProperties()
  $pb = $s.GetPlaybackInfo()
  if (-not $p.Title) { return @{ v = $Version; ok = $true; active = $false } }

  $key = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("$($p.Title)|$($p.Artist)|$($p.AlbumTitle)")).Replace('/', '_').Replace('+', '-').TrimEnd('=')
  Read-Cover $p.Thumbnail $key
  $vol = Get-Volume

  @{
    v      = $Version
    ok     = $true
    active = $true
    title  = $p.Title
    artist = $p.Artist
    album  = $p.AlbumTitle
    status = "$($pb.PlaybackStatus)"   # Playing | Paused | Stopped | Changing ...
    pos    = [Math]::Round($tl.Position.TotalSeconds, 3)
    dur    = [Math]::Round(($tl.EndTime - $tl.StartTime).TotalSeconds, 3)
    at     = $tl.LastUpdatedTime.ToUnixTimeMilliseconds()
    now    = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
    cover  = $(if ($script:cover.bytes) { $key } else { $null })
    vol    = $vol.vol
    muted  = $vol.muted
  }
}

function Invoke-Cmd([string]$c) {
  $s = Get-SpotifySession
  if (-not $s) { return $false }
  $op = switch ($c) {
    'toggle' { $s.TryTogglePlayPauseAsync() }
    'next'   { $s.TrySkipNextAsync() }
    'prev'   { $s.TrySkipPreviousAsync() }
    default  { return $false }
  }
  Await $op ([bool])
}

# Страницу CC GUI JCEF открывает через loadHTML (file://, origin "null").
# Чужим сайтам из браузера ответы и команды не отдаём.
function Test-Origin([string]$origin) {
  -not $origin -or $origin -eq 'null' -or $origin.StartsWith('file://') -or $origin -eq 'https://cc-gui-webview.local'
}

function Send($ctx, [int]$code, [byte[]]$body, [string]$type) {
  $res = $ctx.Response
  try {
    $res.StatusCode = $code
    $origin = $ctx.Request.Headers['Origin']
    if ($origin -and (Test-Origin $origin)) { $res.AddHeader('Access-Control-Allow-Origin', $origin) }
    $res.AddHeader('Access-Control-Allow-Private-Network', 'true')
    $res.AddHeader('Cache-Control', 'no-store')
    if ($type) { $res.ContentType = $type }
    if ($body) { $res.ContentLength64 = $body.Length; $res.OutputStream.Write($body, 0, $body.Length) }
  } catch {} finally { try { $res.Close() } catch {} }
}

function Send-Json($ctx, $obj, [int]$code = 200) {
  Send $ctx $code ([Text.Encoding]::UTF8.GetBytes(($obj | ConvertTo-Json -Compress))) 'application/json; charset=utf-8'
}

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://localhost:$Port/")
try { $listener.Start() } catch { Log "порт $Port занят — уже запущен? $($_.Exception.Message)"; exit 0 }
Log "старт v$Version, pid $PID, порт $Port"

$seen = @{}
$last = [DateTime]::UtcNow
$pending = $null
$quit = $false
while ($listener.IsListening -and -not $quit) {
  # Ждём запрос кусками по 5 с, чтобы заметить простой.
  if (-not $pending) { $pending = $listener.BeginGetContext($null, $null) }
  if (-not $pending.AsyncWaitHandle.WaitOne(5000)) {
    if (([DateTime]::UtcNow - $last).TotalSeconds -gt $IdleExit) { Log "нет запросов $IdleExit с — выход"; break }
    continue
  }
  try { $ctx = $listener.EndGetContext($pending) } catch { $pending = $null; Log "GetContext: $($_.Exception.Message)"; Start-Sleep -Milliseconds 200; continue }
  $pending = $null
  $last = [DateTime]::UtcNow
  try {
    $req = $ctx.Request
    $origin = $req.Headers['Origin']
    if ($origin -and -not $seen.ContainsKey($origin)) { $seen[$origin] = 1; Log "origin: $origin" }
    if (-not (Test-Origin $origin)) { Send $ctx 403 $null $null; continue }

    $path = $req.Url.AbsolutePath
    $post = $req.HttpMethod -eq 'POST'
    if ($req.HttpMethod -eq 'OPTIONS') {
      $ctx.Response.AddHeader('Access-Control-Allow-Methods', 'GET, POST')
      Send $ctx 204 $null $null
    } elseif ($path -eq '/now') {
      try { Send-Json $ctx (Get-Now) }
      catch { $script:mgr = $null; Send-Json $ctx @{ v = $Version; ok = $false; error = $_.Exception.Message } }
    } elseif ($path -eq '/cover') {
      if ($script:cover.bytes -and $req.QueryString['k'] -eq $script:cover.key) { Send $ctx 200 $script:cover.bytes $script:cover.type }
      else { Send $ctx 404 $null $null }
    } elseif ($path -eq '/cmd' -and $post) {
      try { Send-Json $ctx @{ ok = [bool](Invoke-Cmd $req.QueryString['c']) } }
      catch { $script:mgr = $null; Send-Json $ctx @{ ok = $false; error = $_.Exception.Message } }
    } elseif ($path -eq '/seek' -and $post) {
      $ms = [long]0
      if (-not [long]::TryParse($req.QueryString['ms'], [ref]$ms) -or $ms -lt 0) { Send $ctx 400 $null $null; continue }
      try {
        $s = Get-SpotifySession
        $ok = $false
        if ($s) { $ok = [bool](Await ($s.TryChangePlaybackPositionAsync($ms * 10000)) ([bool])) }
        Send-Json $ctx @{ ok = $ok }
      } catch { $script:mgr = $null; Send-Json $ctx @{ ok = $false; error = $_.Exception.Message } }
    } elseif ($path -eq '/vol' -and $post) {
      $v = 0
      if (-not [int]::TryParse($req.QueryString['v'], [ref]$v)) { Send $ctx 400 $null $null; continue }
      $v = [Math]::Max(0, [Math]::Min(100, $v))
      try { Send-Json $ctx @{ ok = [CctAudio.Mixer]::SetVolume('Spotify', [float]($v / 100.0)) } }
      catch { Send-Json $ctx @{ ok = $false; error = $_.Exception.Message } }
    } elseif ($path -eq '/mute' -and $post) {
      try { Send-Json $ctx @{ ok = [CctAudio.Mixer]::SetMute('Spotify', $req.QueryString['m'] -eq '1') } }
      catch { Send-Json $ctx @{ ok = $false; error = $_.Exception.Message } }
    } elseif ($path -eq '/quit' -and $post) {
      Send-Json $ctx @{ ok = $true }
      Log 'выход по команде'
      $quit = $true
    } else {
      Send $ctx 404 $null $null
    }
  } catch {
    Log "запрос: $($_.Exception.Message)"
    try { $ctx.Response.Close() } catch {}
  }
}
try { $listener.Stop(); $listener.Close() } catch {}
