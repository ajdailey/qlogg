# Unpacks the $env:TARGET bundle from dist\, checks that its runtime reports
# os.arch $env:EXPECTED_ARCH, has jdk.charsets and no keytool.exe, then starts qlogg.exe and
# finds the javaw.exe it starts. Passes only when that child runs runtime\bin\javaw.exe from the
# bundle, the image's PE machine is $env:EXPECTED_MACHINE and its log shows the
# main window and no failure. An ARM64 image
# cannot run under x86 or x64 emulation, so ARM64 proves a native arm64 JVM.
# qlogg.cmd then runs the same check; it also runs LauncherProbe in place of qlogg.jar to check
# the Java it picks, the JVM options and the arguments it passes, unquoted names as Explorer
# drops them and escaped ones typed at a prompt, none of which may run a command, and its -v must
# print the version. Both launchers of the universal zip, which has no runtime, then run the
# first check and pass when the child runs javaw.exe from $env:JAVA_HOME.
$ErrorActionPreference = 'Stop'

function Get-PeMachine([string] $Path) {
  $stream = [System.IO.File]::OpenRead($Path)
  try {
    $reader = New-Object System.IO.BinaryReader($stream)
    [void] $stream.Seek(0x3C, 'Begin')
    $peOffset = $reader.ReadInt32()
    [void] $stream.Seek($peOffset + 4, 'Begin')
    $machine = $reader.ReadUInt16()
  } finally {
    $stream.Dispose()
  }
  switch ($machine) {
    0x014C { 'x86' }
    0x8664 { 'x64' }
    0xAA64 { 'ARM64' }
    default { '0x{0:X4}' -f $machine }
  }
}

# Reads $Path, shared with the process still writing it; empty when missing.
function Read-Log([string] $Path) {
  if (-not (Test-Path $Path)) { return '' }
  $stream = [System.IO.File]::Open($Path, 'Open', 'Read', 'ReadWrite,Delete')
  try {
    $reader = New-Object System.IO.StreamReader($stream)
    return $reader.ReadToEnd()
  } finally {
    $stream.Dispose()
  }
}

# Waits up to 60 s for $Text in the log file $Path.
function Wait-LogLine([string] $Path, [string] $Text) {
  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline) {
    if ((Read-Log $Path).Contains($Text)) { return }
    Start-Sleep -Milliseconds 200
  }
}

# Starts $Exe, qlogg.exe or qlogg.cmd, on the mock log and checks the javaw.exe child it starts
# (for qlogg.cmd, the child of its cmd.exe): its image must be $Javaw with PE machine $env:EXPECTED_MACHINE, and its log
# (-l; qlogg-<pid>.log under -m) must show the main window and no unhandled or
# UI task failure. $Name names the run's settings directory.
function Test-Launcher([string] $Exe, [string] $Javaw, [string] $Name) {
  $settings = Join-Path $work "settings-$Name"
  $exeName = Split-Path -Leaf $Exe
  if ($exeName -like '*.exe') { Write-Output "$Name $exeName image: $(Get-PeMachine $Exe)" }
  $launcher = Start-Process -FilePath $Exe -ArgumentList @('-m', '-l', '--settings', "`"$settings`"", "`"$log`"") -PassThru

  $child = $null
  $deadline = (Get-Date).AddSeconds(60)
  while (-not $child -and (Get-Date) -lt $deadline) {
    $child = Get-CimInstance Win32_Process -Filter "ParentProcessId = $($launcher.Id) AND Name = 'javaw.exe'" |
      # Under load WMI has listed the child with no ExecutablePath; poll until it has one.
      Where-Object { $_.CommandLine -like '*qlogg.jar*' -and $_.ExecutablePath } |
      Select-Object -First 1
    if (-not $child) { Start-Sleep -Milliseconds 100 }
  }
  if (-not $child) { throw "$Name $exeName (pid $($launcher.Id)) started no javaw.exe running qlogg.jar within 60 s" }

  $path = $child.ExecutablePath
  $machine = Get-PeMachine $path
  Write-Output "$Name child pid $($child.ProcessId) of $exeName pid $($launcher.Id)"
  Write-Output "$Name child image: $path"
  Write-Output "$Name child image machine: $machine, expected $env:EXPECTED_MACHINE"
  Write-Output "$Name child command line: $($child.CommandLine)"

  $logFile = Join-Path $settings "logs\qlogg-$($child.ProcessId).log"
  try {
    Wait-LogLine $logFile 'Main window open'
    $text = Read-Log $logFile
  } finally {
    Stop-Process -Id $child.ProcessId -Force -ErrorAction SilentlyContinue
  }
  Write-Output "--- $Name log"
  Write-Output $text
  Write-Output '---'

  if ($path -ine $Javaw) { throw "$Name child image is $path, expected $Javaw" }
  if ($machine -ne $env:EXPECTED_MACHINE) { throw "$Name child image machine is $machine" }
  if (-not $text.Contains('Main window open')) { throw "$Name opened no main window within 60 s" }
  if ($text.Contains('Unhandled failure')) { throw "$Name logged an unhandled failure" }
  if ($text.Contains('UI task failed')) { throw "$Name logged a UI task failure" }
  Write-Output "PASS: $Name $exeName started the $machine runtime $path and opened the main window"
}

# Runs cmd.exe /d /s /c "$Line" with $Line passed as given, standard input closed and each
# $Environment entry set, or removed when its value is $null. Returns the exit code, stdout and
# stderr, which close only when every process holding them ends, Java's included.
function Invoke-Cmd([string] $Line, [hashtable] $Environment) {
  $info = New-Object System.Diagnostics.ProcessStartInfo $env:ComSpec
  $info.Arguments = "/d /s /c `"$Line`""
  $info.UseShellExecute = $false
  $info.RedirectStandardInput = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  foreach ($key in $Environment.Keys) {
    if ($null -eq $Environment[$key]) { [void] $info.Environment.Remove($key) }
    else { $info.Environment[$key] = $Environment[$key] }
  }
  $process = [System.Diagnostics.Process]::Start($info)
  $process.StandardInput.Close()
  $stdout = $process.StandardOutput.ReadToEndAsync()
  $stderr = $process.StandardError.ReadToEndAsync()
  if (-not $process.WaitForExit(60000)) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    throw "cmd.exe /c $Line did not exit within 60 s"
  }
  # javaw.exe shows a launcher error, such as an unreadable jar, in a dialog and holds the output.
  if (-not ($stdout.Wait(60000) -and $stderr.Wait(60000))) { throw "cmd.exe /c $Line started a Java that has not ended within 60 s" }
  [pscustomobject] @{ ExitCode = $process.ExitCode; Stdout = $stdout.Result; Stderr = $stderr.Result }
}

# Copies $Bundle's qlogg.cmd, with LauncherProbe as lib\qlogg.jar, into $Folder\qlogg, and
# $Bundle's runtime unless $NoRuntime. Returns the copy's qlogg folder.
function New-ProbeTree([string] $Bundle, [string] $Folder, [switch] $NoRuntime) {
  $tree = Join-Path $Folder 'qlogg'
  New-Item -ItemType Directory -Path (Join-Path $tree 'lib') -Force | Out-Null
  Copy-Item -LiteralPath (Join-Path $Bundle 'qlogg.cmd') -Destination $tree
  Copy-Item -LiteralPath $probeJar -Destination (Join-Path $tree 'lib\qlogg.jar')
  if (-not $NoRuntime) { Copy-Item -LiteralPath (Join-Path $Bundle 'runtime') -Destination (Join-Path $tree 'runtime') -Recurse }
  $tree
}

# Runs $Tree\qlogg.cmd with the raw argument text $Arguments and returns LauncherProbe's lines.
function Invoke-Probe([string] $Tree, [string] $Arguments, [hashtable] $Environment) {
  $out = Join-Path $work "probe-$([guid]::NewGuid()).txt"
  $Environment['QLOGG_PROBE_OUT'] = $out
  $result = Invoke-Cmd "`"$(Join-Path $Tree 'qlogg.cmd')`" $Arguments" $Environment
  if ($result.ExitCode -ne 0) { throw "qlogg.cmd in $Tree exited $($result.ExitCode): $($result.Stderr)" }
  # qlogg.cmd returns once javaw starts; the probe writes its file after.
  $deadline = (Get-Date).AddSeconds(60)
  while (-not (Test-Path -LiteralPath $out) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 100 }
  if (-not (Test-Path -LiteralPath $out)) { throw "LauncherProbe under $Tree wrote no $out within 60 s" }
  Get-Content -LiteralPath $out -Encoding utf8
}

# Fails unless $Lines name $JavaHome, $Tree\lib\qlogg.jar, the JVM options of qlogg.exe and
# exactly $Expected as the arguments.
function Assert-Probe([string[]] $Lines, [string] $Tree, [string] $JavaHome, [string[]] $Expected) {
  $want = @("home=$JavaHome", "jar=$(Join-Path $Tree 'lib\qlogg.jar')", 'opt=-XX:MaxRAMPercentage=50', 'opt=-XX:GCTimeRatio=1')
  foreach ($arg in $Expected) { $want += "arg=$arg" }
  $got = $Lines -join "`n"
  if ($got -cne ($want -join "`n")) { throw "qlogg.cmd in $Tree passed`n$got`nexpected`n$($want -join "`n")" }
}

$cmdArguments = @('-m', '+3:2', 'a b.txt', 'x&y.txt', 'c^d.txt', '100%.txt', '5%x%.txt', 'bang!.txt', '(p).txt', "it's.txt", '')
# Each argument quoted, as typed at a command prompt.
$cmdLine = ($cmdArguments | ForEach-Object { "`"$_`"" }) -join ' '

# qlogg.cmd from a folder named $FolderName passes the arguments unchanged to the runtime beside
# it, ahead of JAVA_HOME.
function Test-CmdArguments([string] $Bundle, [string] $FolderName) {
  $tree = New-ProbeTree $Bundle (Join-Path $work "cmd\$FolderName")
  $lines = Invoke-Probe $tree $cmdLine @{ JAVA_HOME = $jdk }
  Assert-Probe $lines $tree (Join-Path $tree 'runtime') $cmdArguments
  Write-Output "PASS: qlogg.cmd in '$FolderName' passed $($cmdArguments.Count) arguments to its runtime"
}

# Without a runtime folder qlogg.cmd takes JAVA_HOME, else javaw.exe on PATH, else exits 1.
function Test-CmdJavaOrder([string] $Bundle) {
  $tree = New-ProbeTree $Bundle (Join-Path $work 'cmd\no runtime') -NoRuntime
  $system = Join-Path $env:SystemRoot 'System32'
  $empty = New-Item -ItemType Directory -Path (Join-Path $work 'cmd\empty') -Force
  $lines = Invoke-Probe $tree '"a b.txt"' @{ JAVA_HOME = $jdk; PATH = $system }
  Assert-Probe $lines $tree $jdk @('a b.txt')
  $lines = Invoke-Probe $tree '"a b.txt"' @{ JAVA_HOME = $empty.FullName; PATH = "$jdk\bin;$system" }
  Assert-Probe $lines $tree $jdk @('a b.txt')
  $result = Invoke-Cmd "`"$(Join-Path $tree 'qlogg.cmd')`"" @{ JAVA_HOME = $null; PATH = $system }
  if ($result.ExitCode -ne 1) { throw "qlogg.cmd without Java exited $($result.ExitCode)" }
  if (-not $result.Stderr.Contains('QLogg needs Java 21')) { throw "qlogg.cmd without Java wrote: $($result.Stderr)" }
  Write-Output 'PASS: qlogg.cmd took JAVA_HOME, then PATH, and exited 1 without Java'
}

# qlogg.cmd -v prints the bundle's version to this console as its first line.
function Test-CmdVersion([string] $Bundle) {
  $result = Invoke-Cmd "`"$(Join-Path $Bundle 'qlogg.cmd')`" -v" @{}
  $want = "qlogg $version"
  $first = ($result.Stdout -split "`r?`n")[0]
  if ($first -cne $want) { throw "qlogg.cmd -v printed [$($result.Stdout)] [$($result.Stderr)], expected $want" }
  Write-Output "PASS: qlogg.cmd -v printed $want"
}

# Returns LauncherProbe's lines from $Out, written after qlogg.cmd returns.
function Wait-Probe([string] $Out) {
  $deadline = (Get-Date).AddSeconds(60)
  while (-not (Test-Path -LiteralPath $Out) -and (Get-Date) -lt $deadline) { Start-Sleep -Milliseconds 100 }
  if (-not (Test-Path -LiteralPath $Out)) { throw "LauncherProbe wrote no $Out within 60 s" }
  Get-Content -LiteralPath $Out -Encoding utf8
}

# A folder holding mark.cmd, which writes marker.txt beside itself when run. An argument
# `a&mark` runs it if any cmd.exe acts on the &.
function New-MarkFolder([string] $Folder) {
  New-Item -ItemType Directory -Path $Folder -Force | Out-Null
  Set-Content -LiteralPath (Join-Path $Folder 'mark.cmd') -Value "@>`"%~dp0marker.txt`" echo ran`r`n" -NoNewline
  $Folder
}

# Fails when $Folder holds marker.txt or l.txt, which a cmd.exe acting on & | > creates.
function Assert-NoMarker([string] $Folder, [string] $Case) {
  # A command after & runs once the script returns; give it time.
  Start-Sleep -Seconds 2
  foreach ($name in @('marker.txt', 'l.txt')) {
    if (Test-Path -LiteralPath (Join-Path $Folder $name)) { throw "$Case ran a command: $name exists" }
  }
}

# Explorer passes a dropped path without quotes unless it holds a space, through ShellExecute,
# which starts cmd.exe /c ""<qlogg.cmd>" <arguments>". qlogg.cmd must pass each name intact,
# and no command after an & may run.
function Test-CmdExplorer([string] $Bundle) {
  $folder = New-MarkFolder (Join-Path $work 'cmd\explorer')
  $tree = New-ProbeTree $Bundle $folder
  $out = Join-Path $work "probe-$([guid]::NewGuid()).txt"
  $env:QLOGG_PROBE_OUT = $out
  # Defined, so a cmd.exe that expands g%h%.txt changes the name.
  $env:h = 'expanded'
  try {
    $e = [char] 0xe9
    $text = "$folder\a&mark c^d.txt e!f.txt g%h%.txt (p).txt caf$e.txt `"x y&mark.txt`""
    $launch = Start-Process -FilePath (Join-Path $tree 'qlogg.cmd') -ArgumentList $text -WorkingDirectory $folder -WindowStyle Hidden -PassThru
    if (-not $launch.WaitForExit(60000)) { throw "qlogg.cmd through ShellExecute did not exit within 60 s" }
    $lines = Wait-Probe $out
  } finally {
    Remove-Item Env:QLOGG_PROBE_OUT, Env:h -ErrorAction SilentlyContinue
  }
  Assert-NoMarker $folder 'qlogg.cmd through ShellExecute'
  Assert-Probe $lines $tree (Join-Path $tree 'runtime') @("$folder\a&mark", 'c^d.txt', 'e!f.txt', 'g%h%.txt', '(p).txt', "caf$e.txt", 'x y&mark.txt')
  Write-Output 'PASS: qlogg.cmd through ShellExecute passed unquoted & ^ ! % names intact and ran no command'
}

# Runs $Text as a line typed at a command prompt in $Folder: cmd.exe without /c reads it from
# standard input. Returns LauncherProbe's lines.
function Invoke-Prompt([string] $Folder, [string] $Text) {
  $out = Join-Path $work "probe-$([guid]::NewGuid()).txt"
  $info = New-Object System.Diagnostics.ProcessStartInfo $env:ComSpec
  # Without /q: under /q, echo on has no effect and qlogg.cmd refuses to run.
  $info.Arguments = '/d'
  $info.UseShellExecute = $false
  $info.WorkingDirectory = $Folder
  $info.RedirectStandardInput = $true
  $info.RedirectStandardOutput = $true
  $info.RedirectStandardError = $true
  $info.Environment['QLOGG_PROBE_OUT'] = $out
  $process = [System.Diagnostics.Process]::Start($info)
  $stdout = $process.StandardOutput.ReadToEndAsync()
  $stderr = $process.StandardError.ReadToEndAsync()
  $process.StandardInput.Write("$Text`r`nexit`r`n")
  $process.StandardInput.Close()
  if (-not $process.WaitForExit(60000)) {
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    throw "cmd.exe reading $Text did not exit within 60 s"
  }
  if (-not ($stdout.Wait(60000) -and $stderr.Wait(60000))) { throw "cmd.exe reading $Text started a Java that has not ended within 60 s" }
  if ($stderr.Result) { Write-Output "prompt stderr: $($stderr.Result)" }
  Wait-Probe $out
}

# At a command prompt, escaped and quoted arguments reach Java as cmd.exe passed them to
# qlogg.cmd, and nothing in them runs.
function Test-CmdPrompt([string] $Bundle) {
  $folder = New-MarkFolder (Join-Path $work 'cmd\prompt & 100%')
  $tree = New-ProbeTree $Bundle $folder
  $script = "`"$(Join-Path $tree 'qlogg.cmd')`""
  $lines = Invoke-Prompt $folder "$script a^&mark c^^d.txt e!f.txt g%q9z%.txt i^|mark k^>l.txt `"x&y.txt`" (p).txt"
  Assert-NoMarker $folder 'qlogg.cmd at a prompt'
  Assert-Probe $lines $tree (Join-Path $tree 'runtime') @('a&mark', 'c^d.txt', 'e!f.txt', 'g%q9z%.txt', 'i|mark', 'k>l.txt', 'x&y.txt', '(p).txt')
  $lines = Invoke-Prompt $folder "$script $cmdLine"
  Assert-Probe $lines $tree (Join-Path $tree 'runtime') $cmdArguments
  Write-Output 'PASS: qlogg.cmd at a prompt passed escaped & ^ | > and quoted arguments intact and ran no command'
}

$work = Join-Path $PWD 'bundle-run'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
$archive = Get-Item "dist\qlogg-*-$env:TARGET.zip"
$version = $archive.Name.Substring(6, $archive.Name.Length - 6 - "-$env:TARGET.zip".Length)
Expand-Archive -Path $archive.FullName -DestinationPath $work
$bundle = (Resolve-Path (Join-Path $work 'qlogg')).Path

$arch = & (Join-Path $bundle 'runtime\bin\java.exe') -XshowSettings:properties -version 2>&1 |
  ForEach-Object { "$_" } |
  Where-Object { $_ -match '^\s*os\.arch = ' } |
  ForEach-Object { ($_ -split '=', 2)[1].Trim() }
Write-Output "runtime os.arch=$arch, expected $env:EXPECTED_ARCH"
if ($arch -ne $env:EXPECTED_ARCH) { throw "runtime os.arch is $arch" }
$modules = & (Join-Path $bundle 'runtime\bin\java.exe') --list-modules
if (-not ($modules -match '^jdk\.charsets@')) { throw 'runtime lacks jdk.charsets' }
if (Test-Path (Join-Path $bundle 'runtime\bin\keytool.exe')) { throw 'runtime has keytool.exe' }

$log = Join-Path $work 'app.log'
Set-Content -Path $log -Value 'mock line'
Test-Launcher (Join-Path $bundle 'qlogg.exe') (Join-Path $bundle 'runtime\bin\javaw.exe') 'bundle'
Test-Launcher (Join-Path $bundle 'qlogg.cmd') (Join-Path $bundle 'runtime\bin\javaw.exe') 'bundle-cmd'

$jdk = (Resolve-Path $env:JAVA_HOME).Path
$probeClasses = Join-Path $work 'probe-classes'
& (Join-Path $jdk 'bin\javac.exe') -d $probeClasses 'qlogg-dist\src\test\java\io\github\ajdailey\qlogg\dist\LauncherProbe.java'
if ($LASTEXITCODE -ne 0) { throw "javac exited $LASTEXITCODE" }
$probeJar = Join-Path $work 'probe.jar'
& (Join-Path $jdk 'bin\jar.exe') --create --file $probeJar --main-class io.github.ajdailey.qlogg.dist.LauncherProbe -C $probeClasses .
if ($LASTEXITCODE -ne 0) { throw "jar exited $LASTEXITCODE" }
Test-CmdArguments $bundle 'with space'
Test-CmdArguments $bundle 'amp & pct 100%'
Test-CmdJavaOrder $bundle
Test-CmdVersion $bundle
Test-CmdExplorer $bundle
Test-CmdPrompt $bundle

$universal = Get-ChildItem dist -Filter 'qlogg-*.zip' |
  Where-Object { $_.Name -notmatch '-(linux|macos|windows)-' } |
  Select-Object -First 1
if (-not $universal) { throw 'no universal zip in dist' }
$universalDir = Join-Path $work 'universal'
Expand-Archive -Path $universal.FullName -DestinationPath $universalDir
Test-Launcher (Join-Path $universalDir 'qlogg\qlogg.exe') (Join-Path $jdk 'bin\javaw.exe') 'universal'
Test-Launcher (Join-Path $universalDir 'qlogg\qlogg.cmd') (Join-Path $jdk 'bin\javaw.exe') 'universal-cmd'
