# Temporary diagnostic: which registration changes the .log default verb.
$ErrorActionPreference = 'Stop'
Add-Type -TypeDefinition @'
using System.Runtime.InteropServices;
using System.Text;
public static class AssocDiag {
  [DllImport("shlwapi.dll", CharSet = CharSet.Unicode)]
  static extern int AssocQueryString(int flags, int str, string assoc, string extra, StringBuilder output, ref int length);
  public static string Get(string extension, int str) {
    int length = 4096;
    StringBuilder output = new StringBuilder(length);
    int hr = AssocQueryString(0, str, extension, null, output, ref length);
    return hr == 0 ? output.ToString() : ("hr=0x" + hr.ToString("x8"));
  }
}
'@
function Show([string] $When) {
  foreach ($ext in @('.log', '.txt', '.xyz')) {
    Write-Output "$When $ext command [$([AssocDiag]::Get($ext, 1))] verb [$([AssocDiag]::Get($ext, 4))] progid [$([AssocDiag]::Get($ext, 20))]"
  }
}
$c = 'HKCU:\Software\Classes'
$exe = 'C:\mock\qlogg.exe'
Show 'baseline'
foreach ($p in @('Registry::HKEY_CLASSES_ROOT\.log', 'Registry::HKEY_CLASSES_ROOT\SystemFileAssociations\.log', 'Registry::HKEY_CLASSES_ROOT\*\shell', 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\.log')) {
  $k = Get-Item -LiteralPath $p -ErrorAction SilentlyContinue
  if ($k) { Write-Output "$p values: $($k.GetValueNames() -join ', ') = $(($k.GetValueNames() | ForEach-Object { $k.GetValue($_) }) -join ' | ') subkeys: $($k.GetSubKeyNames() -join ', ')" } else { Write-Output "$p absent" }
}

New-Item -Force -Path "$c\QLogg.File\shell\open\command" | Out-Null
Set-Item -LiteralPath "$c\QLogg.File\shell\open\command" -Value "`"$exe`" `"%1`""
New-Item -Force -Path "$c\.log\OpenWithProgids" | Out-Null
New-ItemProperty -LiteralPath "$c\.log\OpenWithProgids" -Name 'QLogg.File' -PropertyType None -Value ([byte[]]@()) | Out-Null
Show 'openwithprogids only'
Remove-Item -Recurse -LiteralPath "$c\.log"
Remove-Item -Recurse -LiteralPath "$c\QLogg.File"
Show 'cleared 1'

$k = [Microsoft.Win32.Registry]::CurrentUser.CreateSubKey('Software\Classes\*\shell\QLogg.Open')
$k.SetValue('MUIVerb', 'Open in QLogg')
$k.CreateSubKey('command').SetValue('', "`"$exe`" `"%1`"")
$k.Close()
Show 'verb only'
[Microsoft.Win32.Registry]::CurrentUser.DeleteSubKeyTree('Software\Classes\*\shell\QLogg.Open')
Show 'cleared 2'
