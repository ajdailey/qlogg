# Installs the $env:TARGET setup from dist\ silently for all users and then for the current
# user. Each time it checks the Installed apps entry, the Start menu shortcut, no desktop
# shortcut, Open With for .log and .txt with their default handlers unchanged, the Explorer
# menu verb QLogg.Open with the default verb of .log and .txt unchanged, that a .cmd
# still runs through ShellExecute, that the folder holds no qlogg.conf or portable.txt and that
# no QLogg process starts; runs check-bundle.ps1 against the installed folder; installs again with /DESKTOP over a stale runtime file, which the upgrade
# removes, and checks the desktop shortcut on that scope's desktop only; then uninstalls
# silently and checks that every file, shortcut and registry entry is gone and the per-user
# settings stay and a .cmd still runs. A portable install with /DESKTOP then gets portable.txt and no qlogg.conf, no
# registry entry and no shortcut, starts with its settings in the folder, and an install over it
# keeps both files. Setup refuses a portable folder under Program Files and a regular install into
# a folder holding portable.txt or qlogg.conf. /NOEXPLORERMENU over an install removes the verb.
# register-associations.cmd from the portable zip, in a folder named with a space, & and (,
# adds Open With and the verb under HKCU, the verb on two selected files hands both to the
# running QLogg, and /u removes every key. Run from the repository root with no QLogg installed.
$ErrorActionPreference = 'Stop'

$setup = (Get-Item "dist\qlogg-*-$env:TARGET-setup.exe").FullName
$version = (Split-Path -Leaf $setup) -replace '^qlogg-', '' -replace "-$env:TARGET-setup\.exe$", ''
$uninstallKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\QLogg'
$commonDesktop = Join-Path ([Environment]::GetFolderPath('CommonDesktopDirectory')) 'QLogg.lnk'
$userDesktop = Join-Path ([Environment]::GetFolderPath('Desktop')) 'QLogg.lnk'
$verbKey = 'Software\Classes\*\shell\QLogg.Open'
$work = Join-Path $env:RUNNER_TEMP 'installer-run'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Path $work | Out-Null

# Runs setup with $Arguments and returns its exit code.
function Invoke-Setup([string[]] $Arguments) {
  $process = Start-Process -FilePath $setup -ArgumentList $Arguments -PassThru
  if (-not $process.WaitForExit(300000)) { throw "setup $Arguments did not exit within 300 s" }
  $process.ExitCode
}

# The default value of HKEY_CLASSES_ROOT\$Extension, the extension's default handler; $null
# when the key is absent.
function Get-Handler([string] $Extension) {
  $key = Get-Item "Registry::HKEY_CLASSES_ROOT\$Extension" -ErrorAction SilentlyContinue
  if ($key) { $key.GetValue('') } else { $null }
}

Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using System.Text;

public static class Assoc {
  [DllImport("shlwapi.dll", CharSet = CharSet.Unicode)]
  static extern int AssocQueryString(int flags, int str, string assoc, string extra, StringBuilder output, ref int length);

  // ASSOCSTR_COMMAND of the default verb for extension; null when it has none.
  public static string Command(string extension) {
    int length = 4096;
    StringBuilder output = new StringBuilder(length);
    return AssocQueryString(0, 1, extension, null, output, ref length) == 0 ? output.ToString() : null;
  }
}
'@

# The default verb's command for .log and .txt, as Explorer runs it on a double-click.
function Get-DefaultVerbs {
  @{ '.log' = [Assoc]::Command('.log'); '.txt' = [Assoc]::Command('.txt') }
}

# Throws unless the default verb's command for .log and .txt is the one read before any install,
# or OpenWith.exe, and is no QLogg command. A type with no UserChoice whose OpenWithProgids gains
# QLogg.File resolves to OpenWith.exe, Windows' app picker (measured on windows-2025 for .log);
# the verb under * changes neither, which the /NOEXPLORERMENU step checks.
function Assert-DefaultVerbs([string] $What) {
  $now = Get-DefaultVerbs
  Write-Output "default verbs after $What`: .log [$($now['.log'])] .txt [$($now['.txt'])]"
  foreach ($ext in @('.log', '.txt')) {
    $command = $now[$ext]
    if ($command -like '*qlogg*') { throw "$What made QLogg the $ext default verb: $command" }
    if ($command -ne $defaultVerbs[$ext] -and $command -notlike '*\OpenWith.exe*') { throw "$What changed the $ext default verb from [$($defaultVerbs[$ext])] to [$command]" }
  }
}

# Throws unless $Classes holds the QLogg.Open verb for $Exe. The key path holds *, so every
# registry read takes -LiteralPath.
function Assert-Verb([string] $Classes, [string] $Exe, [string] $What) {
  $key = Get-Item -LiteralPath "$Classes\*\shell\QLogg.Open" -ErrorAction SilentlyContinue
  if (-not $key) { throw "$What wrote no $Classes\*\shell\QLogg.Open" }
  if ($key.GetValue('MUIVerb') -ne 'Open in QLogg') { throw "$What QLogg.Open MUIVerb is $($key.GetValue('MUIVerb'))" }
  if ($key.GetValue('Icon') -ne "`"$Exe`",0") { throw "$What QLogg.Open Icon is $($key.GetValue('Icon'))" }
  $command = (Get-Item -LiteralPath "$Classes\*\shell\QLogg.Open\command").GetValue('')
  if ($command -ne "`"$Exe`" `"%1`"") { throw "$What QLogg.Open runs $command" }
}

function Test-Verb([string] $Hive) {
  Test-Path -LiteralPath "$Hive\$verbKey"
}

# Prints the values and subkeys of .log and .txt under each hive's Software\Classes.
function Show-Extensions([string] $When) {
  foreach ($hive in @('HKLM:', 'HKCU:')) {
    foreach ($ext in @('.log', '.txt')) {
      $key = Get-Item "$hive\Software\Classes\$ext" -ErrorAction SilentlyContinue
      if ($key) {
        Write-Output "$When $hive $ext values: $($key.GetValueNames() -join ', ') subkeys: $($key.GetSubKeyNames() -join ', ')"
      } else {
        Write-Output "$When $hive $ext absent"
      }
    }
  }
}

# Throws when a process runs an executable under $Dir. Setup has exited, so a launch it
# started shows within the 5 s wait.
function Assert-NotRunning([string] $Dir, [string] $What) {
  Start-Sleep -Seconds 5
  $running = @(Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith("$Dir\", [StringComparison]::OrdinalIgnoreCase) })
  if ($running.Count -gt 0) { throw "$What started $($running[0].ExecutablePath)" }
}

function Get-ShortcutTarget([string] $Path) {
  (New-Object -ComObject WScript.Shell).CreateShortcut($Path).TargetPath
}

# Throws unless ShellExecute runs a batch file: QLogg.File in .cmd OpenWithProgids makes it
# fail.
function Assert-CmdRuns([string] $What) {
  $cmd = Join-Path $work 'ran.cmd'
  $out = Join-Path $work 'ran.txt'
  Set-Content -LiteralPath $cmd -Value "@echo ran>`"%~dp0ran.txt`""
  Remove-Item -LiteralPath $out -ErrorAction SilentlyContinue
  $process = Start-Process -FilePath $cmd -PassThru
  if (-not $process.WaitForExit(30000)) { throw "$What`: ran.cmd did not exit within 30 s" }
  if (-not (Test-Path $out)) { throw "$What`: ShellExecute did not run ran.cmd" }
}

# Waits up to 120 s for $Condition to hold; throws $Message otherwise.
function Wait-Until([scriptblock] $Condition, [string] $Message) {
  $deadline = (Get-Date).AddSeconds(120)
  while (-not (& $Condition)) {
    if ((Get-Date) -gt $deadline) { throw $Message }
    Start-Sleep -Milliseconds 500
  }
}

$defaultVerbs = Get-DefaultVerbs
Write-Output "default verbs before any install: .log [$($defaultVerbs['.log'])] .txt [$($defaultVerbs['.txt'])]"

function Test-Scope([string] $Scope) {
  if ($Scope -eq 'machine') {
    $hive = 'HKLM:'
    $dir = Join-Path $env:ProgramFiles 'QLogg'
    $shortcut = Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\QLogg.lnk'
    $switch = '/MACHINE'
    $desktop = $commonDesktop
    $otherDesktop = $userDesktop
  } else {
    $hive = 'HKCU:'
    $dir = Join-Path $env:LOCALAPPDATA 'Programs\QLogg'
    $shortcut = Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\QLogg.lnk'
    $switch = '/USER'
    $desktop = $userDesktop
    $otherDesktop = $commonDesktop
  }
  $classes = "$hive\Software\Classes"
  $handlers = @{ '.log' = (Get-Handler '.log'); '.txt' = (Get-Handler '.txt') }
  Show-Extensions "before $Scope install:"

  $code = Invoke-Setup @('/S', $switch)
  if ($code -ne 0) { throw "$Scope setup exited $code" }
  Assert-NotRunning $dir "silent $Scope setup"
  $entry = Get-ItemProperty "$hive\$uninstallKey"
  if ($entry.InstallLocation -ne $dir) { throw "$Scope InstallLocation is $($entry.InstallLocation), expected $dir" }
  if ($entry.DisplayVersion -ne $version) { throw "$Scope DisplayVersion is $($entry.DisplayVersion), expected $version" }
  if ($entry.DisplayIcon -ne "$dir\qlogg.exe,0") { throw "$Scope DisplayIcon is $($entry.DisplayIcon)" }
  if ($entry.QuietUninstallString -ne "`"$dir\uninstall.exe`" /S") { throw "$Scope QuietUninstallString is $($entry.QuietUninstallString)" }
  foreach ($file in @('qlogg.exe', 'qlogg.cmd', 'uninstall.exe', 'lib\qlogg.jar', 'runtime\bin\javaw.exe', 'LICENSE')) {
    if (-not (Test-Path (Join-Path $dir $file))) { throw "$Scope install lacks $file" }
  }
  foreach ($file in @('qlogg.conf', 'portable.txt')) {
    if (Test-Path (Join-Path $dir $file)) { throw "$Scope install holds $file" }
  }
  if ((Get-ShortcutTarget $shortcut) -ne "$dir\qlogg.exe") { throw "$Scope shortcut $shortcut does not start $dir\qlogg.exe" }
  foreach ($path in @($commonDesktop, $userDesktop)) {
    if (Test-Path $path) { throw "$Scope install without /DESKTOP wrote $path" }
  }
  foreach ($ext in @('.log', '.txt')) {
    $progids = Get-ItemProperty "$classes\$ext\OpenWithProgids"
    if ($null -eq $progids.'QLogg.File') { throw "$Scope $ext has no QLogg.File in OpenWithProgids" }
    if ((Get-Handler $ext) -ne $handlers[$ext]) { throw "$Scope install changed the $ext handler to $(Get-Handler $ext)" }
  }
  $command = (Get-Item "$classes\QLogg.File\shell\open\command").GetValue('')
  if ($command -ne "`"$dir\qlogg.exe`" `"%1`"") { throw "$Scope QLogg.File opens with $command" }
  Assert-Verb $classes "$dir\qlogg.exe" "$Scope install"
  Assert-DefaultVerbs "$Scope install"
  Assert-CmdRuns "$Scope install"
  Write-Output "PASS: $Scope install into $dir with its Installed apps entry, shortcut, Open With and Explorer menu verb, no desktop shortcut, the default verbs unchanged, a .cmd that still runs, and no QLogg started"

  $env:QLOGG_HOME = $dir
  try { & .github\scripts\check-bundle.ps1 } finally { Remove-Item Env:QLOGG_HOME }

  # An install over the last one replaces the runtime whole and adds no qlogg.conf; /DESKTOP
  # adds the shortcut to this scope's desktop only.
  $stale = Join-Path $dir 'runtime\bin\stale.dll'
  Set-Content -LiteralPath $stale -Value 'mock'
  $code = Invoke-Setup @('/S', $switch, '/DESKTOP')
  if ($code -ne 0) { throw "$Scope setup over the install exited $code" }
  if (Test-Path $stale) { throw "$Scope upgrade left $stale" }
  if (Test-Path (Join-Path $dir 'qlogg.conf')) { throw "$Scope upgrade added qlogg.conf" }
  if ((Get-ShortcutTarget $desktop) -ne "$dir\qlogg.exe") { throw "$Scope /DESKTOP shortcut $desktop does not start $dir\qlogg.exe" }
  if (Test-Path $otherDesktop) { throw "$Scope /DESKTOP wrote $otherDesktop" }
  Assert-Verb $classes "$dir\qlogg.exe" "$Scope install over the last one"
  Assert-NotRunning $dir "silent $Scope setup over the install"
  Write-Output "PASS: $Scope install over the last one removed the stale runtime file, added no qlogg.conf, and /DESKTOP wrote $desktop"

  $settings = Join-Path $env:APPDATA 'QLogg'
  New-Item -ItemType Directory -Path $settings -Force | Out-Null
  $marker = Join-Path $settings 'qlogg.conf'
  Set-Content -LiteralPath $marker -Value 'version=1'
  # The uninstaller runs from a copy in %TEMP% and returns at once.
  Start-Process -FilePath (Join-Path $dir 'uninstall.exe') -ArgumentList '/S' -Wait
  Wait-Until { -not (Test-Path $dir) } "$Scope uninstall left $dir"
  Wait-Until { -not (Test-Path "$hive\$uninstallKey") } "$Scope uninstall left the Installed apps entry"
  Show-Extensions "after $Scope uninstall:"
  Wait-Until { -not (Test-Path $shortcut) } "$Scope uninstall left $shortcut"
  Wait-Until { -not (Test-Path $desktop) } "$Scope uninstall left $desktop"
  foreach ($key in @('QLogg.File', 'Applications\qlogg.exe')) {
    Wait-Until { -not (Test-Path "$classes\$key") } "$Scope uninstall left $classes\$key"
  }
  Wait-Until { -not (Test-Verb $hive) } "$Scope uninstall left $hive\$verbKey"
  Assert-DefaultVerbs "$Scope uninstall"
  foreach ($ext in @('.log', '.txt')) {
    $progids = Get-ItemProperty "$classes\$ext\OpenWithProgids" -ErrorAction SilentlyContinue
    if ($progids -and $null -ne $progids.'QLogg.File') { throw "$Scope uninstall left QLogg.File in $ext OpenWithProgids" }
    if ((Get-Handler $ext) -ne $handlers[$ext]) { throw "$Scope uninstall changed the $ext handler" }
  }
  if (-not (Test-Path $marker)) { throw "$Scope uninstall removed the per-user settings" }
  Remove-Item -Recurse -Force $settings
  Assert-CmdRuns "$Scope uninstall"
  Write-Output "PASS: $Scope uninstall removed every file, shortcut and registry entry, kept the settings, and a .cmd still runs"
}

Test-Scope 'machine'
Test-Scope 'user'

# /NOEXPLORERMENU clears the Explorer menu component: an install over one with the verb
# removes it and keeps Open With, and the default verbs read the same with and without it.
$dir = Join-Path $env:LOCALAPPDATA 'Programs\QLogg'
$code = Invoke-Setup @('/S', '/USER')
if ($code -ne 0) { throw "per-user setup exited $code" }
Assert-Verb 'HKCU:\Software\Classes' "$dir\qlogg.exe" 'per-user install'
$withVerb = Get-DefaultVerbs
$code = Invoke-Setup @('/S', '/USER', '/NOEXPLORERMENU')
if ($code -ne 0) { throw "per-user setup with /NOEXPLORERMENU exited $code" }
foreach ($hive in @('HKLM:', 'HKCU:')) {
  if (Test-Verb $hive) { throw "per-user install with /NOEXPLORERMENU left $hive\$verbKey" }
}
if ($null -eq (Get-ItemProperty 'HKCU:\Software\Classes\.log\OpenWithProgids').'QLogg.File') { throw 'per-user install with /NOEXPLORERMENU dropped Open With' }
$withoutVerb = Get-DefaultVerbs
foreach ($ext in @('.log', '.txt')) {
  if ($withVerb[$ext] -ne $withoutVerb[$ext]) { throw "the verb changed the $ext default verb: [$($withVerb[$ext])] with it, [$($withoutVerb[$ext])] without" }
}
Start-Process -FilePath (Join-Path $dir 'uninstall.exe') -ArgumentList '/S' -Wait
Wait-Until { -not (Test-Path $dir) } "per-user uninstall left $dir"
Wait-Until { -not (Test-Path "HKCU:\$uninstallKey") } 'per-user uninstall left the Installed apps entry'
Write-Output 'PASS: /NOEXPLORERMENU over a per-user install removed the Explorer menu verb, kept Open With, and left the default verbs as they were with the verb'

# Portable: portable.txt, nothing registered, settings in the folder.
$portable = Join-Path $work 'QLogg Portable'
$code = Invoke-Setup @('/S', '/PORTABLE', '/DESKTOP', "/D=$portable")
if ($code -ne 0) { throw "portable setup exited $code" }
Assert-NotRunning $portable 'silent portable setup'
$marker = Join-Path $portable 'portable.txt'
$conf = Join-Path $portable 'qlogg.conf'
if (-not (Test-Path -PathType Leaf $marker)) { throw 'portable install wrote no portable.txt' }
if (@(Get-Content -LiteralPath $marker).Count -ne 1) { throw 'portable.txt is not one line' }
if (Test-Path $conf) { throw 'portable install wrote qlogg.conf' }
if (Test-Path (Join-Path $portable 'uninstall.exe')) { throw 'portable install wrote uninstall.exe' }
foreach ($hive in @('HKLM:', 'HKCU:')) {
  if (Test-Path "$hive\$uninstallKey") { throw "portable install wrote $hive\$uninstallKey" }
  if (Test-Path "$hive\Software\Classes\QLogg.File") { throw "portable install wrote $hive\Software\Classes\QLogg.File" }
}
foreach ($shortcut in @((Join-Path $env:ProgramData 'Microsoft\Windows\Start Menu\Programs\QLogg.lnk'), (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs\QLogg.lnk'), $commonDesktop, $userDesktop)) {
  if (Test-Path $shortcut) { throw "portable install wrote $shortcut" }
}

$before = @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" | ForEach-Object { [int] $_.ProcessId })
Start-Process -FilePath (Join-Path $portable 'qlogg.exe') -ArgumentList '-l' | Out-Null
$log = Join-Path $portable 'logs\qlogg.log'
try {
  Wait-Until { (Test-Path $log) -and (Get-Content -LiteralPath $log -Raw).Contains('Main window open') } "portable QLogg wrote no 'Main window open' to $log"
} finally {
  Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
    Where-Object { $before -notcontains [int] $_.ProcessId } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
Write-Output "PASS: portable install with /DESKTOP wrote portable.txt and no qlogg.conf, registered nothing, wrote no shortcut, and QLogg kept its log in $portable"

Set-Content -LiteralPath $conf -Value 'ui.theme=dark'
$stale = Join-Path $portable 'runtime\bin\stale.dll'
Set-Content -LiteralPath $stale -Value 'mock'
$code = Invoke-Setup @('/S', '/PORTABLE', "/D=$portable")
if ($code -ne 0) { throw "portable setup over the portable folder exited $code" }
if ((Get-Content -LiteralPath $conf -Raw).Trim() -ne 'ui.theme=dark') { throw 'portable upgrade changed qlogg.conf' }
if (-not (Test-Path $marker)) { throw 'portable upgrade removed portable.txt' }
if (-not (Test-Path $log)) { throw 'portable upgrade removed the logs' }
if (Test-Path $stale) { throw "portable upgrade left $stale" }
Write-Output 'PASS: portable install over the folder kept portable.txt, qlogg.conf and the logs and replaced the runtime'

# A folder holding either file is portable, so a regular install refuses it.
Remove-Item -LiteralPath $conf
$legacy = Join-Path $work 'QLogg Legacy'
New-Item -ItemType Directory -Path $legacy | Out-Null
New-Item -ItemType File -Path (Join-Path $legacy 'qlogg.conf') | Out-Null
foreach ($folder in @($portable, $legacy)) {
  $code = Invoke-Setup @('/S', '/USER', "/D=$folder")
  if ($code -ne 2) { throw "a per-user install into $folder exited $code, expected 2" }
  if (Test-Path "HKCU:\$uninstallKey") { throw "the refused per-user install into $folder wrote its Installed apps entry" }
}
if (Test-Path (Join-Path $legacy 'qlogg.exe')) { throw "the refused per-user install wrote into $legacy" }
$refused = Join-Path $env:ProgramFiles 'QLogg Portable'
$code = Invoke-Setup @('/S', '/PORTABLE', "/D=$refused")
if ($code -ne 2) { throw "a portable install under Program Files exited $code, expected 2" }
if (Test-Path $refused) { throw "the refused portable install created $refused" }
Write-Output 'PASS: setup refused a per-user install into a folder holding portable.txt or qlogg.conf and a portable folder under Program Files'
Remove-Item -Recurse -Force $portable, $legacy

# register-associations.cmd in the portable zip, unpacked into a folder whose name holds a
# space, & and (.
$unpacked = Join-Path $work 'Q & Logg (zip)'
Expand-Archive -LiteralPath (Get-Item "dist\qlogg-*-$env:TARGET-portable.zip").FullName -DestinationPath $unpacked
$tree = Join-Path $unpacked 'qlogg'
$exe = Join-Path $tree 'qlogg.exe'
$script = Join-Path $tree 'register-associations.cmd'
$classes = 'HKCU:\Software\Classes'

# Runs the script with $Arguments through cmd.exe /c ""<script>" <arguments>"; throws unless
# it exits 0 and prints $Expected.
function Invoke-Register([string] $Arguments, [string] $Expected) {
  $out = Join-Path $work 'register.out'
  $process = Start-Process -FilePath "$env:SystemRoot\System32\cmd.exe" -ArgumentList "/d /c `"`"$script`" $Arguments`"" -RedirectStandardOutput $out -NoNewWindow -Wait -PassThru
  $text = (Get-Content -LiteralPath $out -Raw).Trim()
  if ($process.ExitCode -ne 0) { throw "register-associations.cmd $Arguments exited $($process.ExitCode): $text" }
  if ($text -cne $Expected) { throw "register-associations.cmd $Arguments printed [$text], expected [$Expected]" }
}

Invoke-Register '' 'QLogg is in the Open With list for .log .txt and in the Explorer right-click menu.'
$command = "`"$exe`" `"%1`""
foreach ($key in @('QLogg.File\shell\open\command', 'Applications\qlogg.exe\shell\open\command')) {
  $value = (Get-Item -LiteralPath "$classes\$key").GetValue('')
  if ($value -ne $command) { throw "register-associations.cmd wrote $key as $value" }
}
if ((Get-Item -LiteralPath "$classes\QLogg.File\DefaultIcon").GetValue('') -ne "`"$exe`",0") { throw 'register-associations.cmd wrote the wrong QLogg.File icon' }
foreach ($ext in @('.log', '.txt')) {
  if ($null -eq (Get-ItemProperty "$classes\$ext\OpenWithProgids").'QLogg.File') { throw "register-associations.cmd left QLogg.File out of $ext OpenWithProgids" }
}
Assert-Verb $classes $exe 'register-associations.cmd'
if (Test-Verb 'HKLM:') { throw 'register-associations.cmd wrote HKLM' }
Assert-DefaultVerbs 'register-associations.cmd'
Assert-CmdRuns 'register-associations.cmd'
Write-Output "PASS: register-associations.cmd in '$tree' added Open With and the Explorer menu verb under HKCU, the default verbs unchanged and a .cmd still runs"

# The verb on a two-file selection through the shell's context menu: it starts qlogg.exe once
# per file, and each hands its file to the QLogg already running.
$logs = Join-Path $unpacked 'logs & more (2)'
New-Item -ItemType Directory -Path $logs | Out-Null
foreach ($name in @('first one.log', 'second & (two).log')) {
  Set-Content -LiteralPath (Join-Path $logs $name) -Value '2026-01-01 00:00:00 INFO mock line'
}
$log = Join-Path $tree 'logs\qlogg.log'
Start-Process -FilePath $exe -ArgumentList '-l' | Out-Null
try {
  Wait-Until { (Test-Path $log) -and (Get-Content -LiteralPath $log -Raw).Contains('Main window open') } "the zip's QLogg wrote no 'Main window open' to $log"
  $folder = (New-Object -ComObject Shell.Application).Namespace($logs)
  $names = @($folder.ParseName('first one.log').Verbs() | ForEach-Object { $_.Name -replace '&', '' })
  if ($names -notcontains 'Open in QLogg') { throw "the context menu of a .log lacks Open in QLogg: $($names -join ', ')" }
  $items = $folder.Items()
  # 0x40 SHCONTF_NONFOLDERS
  $items.Filter(0x40, '*.log')
  if ($items.Count -ne 2) { throw "the selection holds $($items.Count) files, expected 2" }
  $items.InvokeVerbEx('QLogg.Open')
  Wait-Until {
    $handed = 0
    foreach ($match in [regex]::Matches((Get-Content -LiteralPath $log -Raw), 'Handoff of (\d+) files taken')) { $handed += [int] $match.Groups[1].Value }
    $handed -ge 2
  } "QLogg took no handoff of both selected files; log:`n$(Get-Content -LiteralPath $log -Raw)"
} finally {
  Get-CimInstance Win32_Process | Where-Object { $_.ExecutablePath -and $_.ExecutablePath.StartsWith("$tree\", [StringComparison]::OrdinalIgnoreCase) } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
}
Write-Output '--- zip QLogg log'
Write-Output (Get-Content -LiteralPath $log -Raw)
Write-Output '---'
Write-Output 'PASS: Open in QLogg on two selected files handed both to the running QLogg'

Invoke-Register '/u' 'QLogg is out of the Open With lists and the Explorer right-click menu.'
foreach ($key in @('QLogg.File', 'Applications\qlogg.exe')) {
  if (Test-Path -LiteralPath "$classes\$key") { throw "register-associations.cmd /u left $classes\$key" }
}
foreach ($ext in @('.log', '.txt')) {
  $progids = Get-ItemProperty "$classes\$ext\OpenWithProgids" -ErrorAction SilentlyContinue
  if ($progids -and $null -ne $progids.'QLogg.File') { throw "register-associations.cmd /u left QLogg.File in $ext OpenWithProgids" }
}
if (Test-Verb 'HKCU:') { throw "register-associations.cmd /u left HKCU:\$verbKey" }
Assert-DefaultVerbs 'register-associations.cmd /u'
Write-Output 'PASS: register-associations.cmd /u removed Open With and the Explorer menu verb'
# The stopped QLogg's DLLs stay locked briefly after it exits.
Wait-Until { Remove-Item -Recurse -Force -LiteralPath $unpacked -ErrorAction SilentlyContinue; -not (Test-Path -LiteralPath $unpacked) } "cannot remove $unpacked"
