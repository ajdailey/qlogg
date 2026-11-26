# Installs the $env:TARGET setup from dist\ silently for all users and then for the current
# user. Each time it checks the Installed apps entry, the Start menu shortcut, no desktop
# shortcut, Open With for .log and .txt with their default handlers unchanged, that the folder
# holds no qlogg.conf or portable.txt and that no QLogg process starts; runs check-bundle.ps1 against the
# installed folder; installs again with /DESKTOP over a stale runtime file, which the upgrade
# removes, and checks the desktop shortcut on that scope's desktop only; then uninstalls
# silently and checks that every file, shortcut and registry entry is gone and the per-user
# settings stay. A portable install with /DESKTOP then gets portable.txt and no qlogg.conf, no
# registry entry and no shortcut, starts with its settings in the folder, and an install over it
# keeps both files. Setup refuses a portable folder under Program Files and a regular install into
# a folder holding portable.txt or qlogg.conf. Run from the repository root with no QLogg installed.
$ErrorActionPreference = 'Stop'

$setup = (Get-Item "dist\qlogg-*-$env:TARGET-setup.exe").FullName
$version = (Split-Path -Leaf $setup) -replace '^qlogg-', '' -replace "-$env:TARGET-setup\.exe$", ''
$uninstallKey = 'Software\Microsoft\Windows\CurrentVersion\Uninstall\QLogg'
$commonDesktop = Join-Path ([Environment]::GetFolderPath('CommonDesktopDirectory')) 'QLogg.lnk'
$userDesktop = Join-Path ([Environment]::GetFolderPath('Desktop')) 'QLogg.lnk'
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

# Waits up to 120 s for $Condition to hold; throws $Message otherwise.
function Wait-Until([scriptblock] $Condition, [string] $Message) {
  $deadline = (Get-Date).AddSeconds(120)
  while (-not (& $Condition)) {
    if ((Get-Date) -gt $deadline) { throw $Message }
    Start-Sleep -Milliseconds 500
  }
}

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
  Write-Output "PASS: $Scope install into $dir with its Installed apps entry, shortcut and Open With, no desktop shortcut, and no QLogg started"

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
  if (Test-Path $shortcut) { throw "$Scope uninstall left $shortcut" }
  if (Test-Path $desktop) { throw "$Scope uninstall left $desktop" }
  foreach ($key in @('QLogg.File', 'Applications\qlogg.exe')) {
    if (Test-Path "$classes\$key") { throw "$Scope uninstall left $classes\$key" }
  }
  foreach ($ext in @('.log', '.txt')) {
    $progids = Get-ItemProperty "$classes\$ext\OpenWithProgids" -ErrorAction SilentlyContinue
    if ($progids -and $null -ne $progids.'QLogg.File') { throw "$Scope uninstall left QLogg.File in $ext OpenWithProgids" }
    if ((Get-Handler $ext) -ne $handlers[$ext]) { throw "$Scope uninstall changed the $ext handler" }
  }
  if (-not (Test-Path $marker)) { throw "$Scope uninstall removed the per-user settings" }
  Remove-Item -Recurse -Force $settings
  Write-Output "PASS: $Scope uninstall removed every file, shortcut and registry entry and kept the settings"
}

Test-Scope 'machine'
Test-Scope 'user'

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
