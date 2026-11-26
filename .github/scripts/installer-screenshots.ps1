# Walks the $env:TARGET setup interactively and saves each page as a PNG in
# installer-screenshots\: a per-machine install, its uninstaller, then a
# portable install into a temporary folder. Pages are driven by window
# messages (the Next button's WM_COMMAND, BM_CLICK on a radio button,
# WM_SETTEXT on the folder box) and captured with PrintWindow, so no window
# needs the focus. Run with Windows PowerShell from the repository root on a
# runner with a desktop, with no QLogg installed.
$ErrorActionPreference = 'Stop'

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;
using System.Text;

public static class SetupUi {
  [StructLayout(LayoutKind.Sequential)]
  public struct Rect { public int Left, Top, Right, Bottom; }
  public delegate bool EnumProc(IntPtr hwnd, IntPtr data);

  [DllImport("user32.dll")] static extern IntPtr GetDlgItem(IntPtr hwnd, int id);
  [DllImport("user32.dll")] static extern int GetDlgCtrlID(IntPtr hwnd);
  [DllImport("user32.dll")] static extern bool IsWindowEnabled(IntPtr hwnd);
  [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
  [DllImport("user32.dll")] public static extern bool IsWindow(IntPtr hwnd);
  [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd, out Rect rect);
  [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr hwnd, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] static extern bool EnumChildWindows(IntPtr hwnd, EnumProc proc, IntPtr data);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int max);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  static extern IntPtr SendMessage(IntPtr hwnd, uint msg, IntPtr w, IntPtr l);
  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  static extern IntPtr SendMessage(IntPtr hwnd, uint msg, IntPtr w, string l);

  [DllImport("user32.dll", CharSet = CharSet.Unicode)]
  static extern int GetClassName(IntPtr hwnd, StringBuilder name, int max);

  // The visible dialog whose caption starts with prefix: a page may append
  // to the caption.
  public static IntPtr Find(string prefix) {
    IntPtr found = IntPtr.Zero;
    EnumWindows(delegate(IntPtr hwnd, IntPtr data) {
      StringBuilder cls = new StringBuilder(64);
      GetClassName(hwnd, cls, cls.Capacity);
      if (IsWindowVisible(hwnd) && cls.ToString() == "#32770" && TextOf(hwnd).StartsWith(prefix)) {
        found = hwnd;
        return false;
      }
      return true;
    }, IntPtr.Zero);
    return found;
  }

  [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc proc, IntPtr data);

  // The titles of the visible top-level windows, one per line.
  public static string TopLevel() {
    StringBuilder all = new StringBuilder();
    EnumWindows(delegate(IntPtr hwnd, IntPtr data) {
      if (IsWindowVisible(hwnd)) { all.Append(TextOf(hwnd)).Append('\n'); }
      return true;
    }, IntPtr.Zero);
    return all.ToString();
  }

  public static bool NextEnabled(IntPtr hwnd) {
    IntPtr next = GetDlgItem(hwnd, 1);
    return next != IntPtr.Zero && IsWindowEnabled(next);
  }

  [DllImport("user32.dll")]
  static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr w, IntPtr l);

  // Posted, so a page that opens a message box does not block the caller.
  public static void Next(IntPtr hwnd) { PostMessage(hwnd, 0x0111, (IntPtr) 1, IntPtr.Zero); }

  static string TextOf(IntPtr hwnd) {
    StringBuilder text = new StringBuilder(1024);
    GetWindowText(hwnd, text, text.Capacity);
    return text.ToString();
  }

  // The visible text of every child window, one per line: changes with the page.
  public static string Texts(IntPtr hwnd) {
    StringBuilder all = new StringBuilder();
    EnumChildWindows(hwnd, delegate(IntPtr child, IntPtr data) {
      if (IsWindowVisible(child)) { all.Append(TextOf(child)).Append('\n'); }
      return true;
    }, IntPtr.Zero);
    return all.ToString();
  }

  // Sends BM_CLICK to the visible child whose text is exactly text.
  public static bool Click(IntPtr hwnd, string text) {
    IntPtr found = IntPtr.Zero;
    EnumChildWindows(hwnd, delegate(IntPtr child, IntPtr data) {
      if (IsWindowVisible(child) && TextOf(child) == text) { found = child; return false; }
      return true;
    }, IntPtr.Zero);
    if (found == IntPtr.Zero) { return false; }
    SendMessage(found, 0x00F5, IntPtr.Zero, IntPtr.Zero);
    return true;
  }

  // Sets the text of the visible child with control id id.
  public static bool SetText(IntPtr hwnd, int id, string text) {
    IntPtr found = IntPtr.Zero;
    EnumChildWindows(hwnd, delegate(IntPtr child, IntPtr data) {
      if (IsWindowVisible(child) && GetDlgCtrlID(child) == id) { found = child; return false; }
      return true;
    }, IntPtr.Zero);
    if (found == IntPtr.Zero) { return false; }
    SendMessage(found, 0x000C, IntPtr.Zero, text);
    return true;
  }

  public static void Capture(IntPtr hwnd, string path) {
    Rect rect;
    GetWindowRect(hwnd, out rect);
    using (Bitmap image = new Bitmap(rect.Right - rect.Left, rect.Bottom - rect.Top)) {
      using (Graphics g = Graphics.FromImage(image)) {
        IntPtr hdc = g.GetHdc();
        PrintWindow(hwnd, hdc, 2);
        g.ReleaseHdc(hdc);
      }
      image.Save(path, ImageFormat.Png);
    }
  }
}
'@

$policy = Get-ItemProperty 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Policies\System'
$elevated = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
Write-Host "UAC: EnableLUA=$($policy.EnableLUA) ConsentPromptBehaviorAdmin=$($policy.ConsentPromptBehaviorAdmin) elevated=$elevated"
$out = Join-Path $PWD 'installer-screenshots'
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Path $out | Out-Null
$setup = (Get-Item "dist\qlogg-*-$env:TARGET-setup.exe").FullName
$script:n = 0

# Returns the dialog whose caption starts with $Title, waiting up to 60 s.
function Wait-Window([string] $Title) {
  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline) {
    $hwnd = [SetupUi]::Find($Title)
    if ($hwnd -ne [IntPtr]::Zero) { return $hwnd }
    Start-Sleep -Milliseconds 200
  }
  Write-Host "visible top-level windows:`n$([SetupUi]::TopLevel())"
  throw "no window titled $Title within 60 s"
}

# Waits up to 180 s for a page other than $Previous whose text has not changed
# for 1.5 s with Next enabled, and returns its text; $null once the window
# $Hwnd is gone. Pages may change the window's caption, so the window is
# tracked by handle.
function Wait-Page([IntPtr] $Hwnd, [string] $Previous) {
  $deadline = (Get-Date).AddSeconds(180)
  $last = $null
  $since = Get-Date
  while ((Get-Date) -lt $deadline) {
    if (-not [SetupUi]::IsWindow($Hwnd)) { return $null }
    $text = [SetupUi]::Texts($Hwnd)
    if ($text -cne $last) {
      $last = $text
      $since = Get-Date
    } elseif ($text -cne $Previous -and [SetupUi]::NextEnabled($Hwnd) -and ((Get-Date) - $since).TotalMilliseconds -ge 1500) {
      return $text
    }
    Start-Sleep -Milliseconds 200
  }
  throw "the window showed no new settled page within 180 s; last text:`n$last"
}

function Save-Window([IntPtr] $Hwnd, [string] $Name) {
  $script:n++
  $file = Join-Path $out ('{0:D2}-{1}.png' -f $script:n, $Name)
  [SetupUi]::Capture($Hwnd, $file)
  Write-Host "saved $file"
}

# Saves each page of the window $Title and presses Next until the window
# closes. $OnPage runs on each page and returns $true when it changed the
# page, which is then saved again before Next.
function Invoke-Walk([string] $Title, [string] $Prefix, [scriptblock] $OnPage) {
  $hwnd = Wait-Window $Title
  $previous = ''
  for ($page = 1; $page -le 12; $page++) {
    $text = Wait-Page $hwnd $previous
    if ($null -eq $text) { return }
    Write-Host "page $page`: $(($text -split "`n" | Where-Object { $_ }) -join ' | ')"
    Save-Window $hwnd ('{0}-{1}' -f $Prefix, $page)
    if ($OnPage -and (& $OnPage $hwnd)) {
      Start-Sleep -Milliseconds 800
      Save-Window $hwnd ('{0}-{1}-set' -f $Prefix, $page)
    }
    $previous = [SetupUi]::Texts($hwnd)
    [SetupUi]::Next($hwnd)
  }
  throw "$Title still open after 12 pages"
}

Write-Host "starting $setup"
Start-Process -FilePath $setup | Out-Null
Invoke-Walk 'QLogg Setup' 'setup' $null

# The uninstaller runs from a copy in %TEMP%; its window is found by title.
Start-Process -FilePath (Join-Path $env:ProgramFiles 'QLogg\uninstall.exe') | Out-Null
Invoke-Walk 'QLogg Uninstall' 'uninstall' $null

# The portable walk picks Portable on the type page and a temporary folder on
# the folder page (control 1019).
$portable = Join-Path $env:RUNNER_TEMP 'QLogg Portable'
Write-Host "starting $setup"
Start-Process -FilePath $setup | Out-Null
Invoke-Walk 'QLogg Setup' 'portable' {
  param($hwnd)
  if ([SetupUi]::Click($hwnd, '&Portable')) { return $true }
  if ([SetupUi]::SetText($hwnd, 1019, $portable)) { return $true }
  $false
}
if (-not (Test-Path (Join-Path $portable 'qlogg.conf'))) { throw "the portable install wrote no qlogg.conf in $portable" }
Remove-Item -Recurse -Force $portable
Write-Output "PASS: saved $script:n installer pages in $out"
