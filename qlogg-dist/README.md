# qlogg-dist

Builds the universal jar and zip, the six per-target bundles, the Windows
installers, the Linux packages and the launchers. Packaging is
`src/build/dist.xml`, run by maven-antrun-plugin at `package` after the
dependency plugin stages the jars and launch4j builds `qlogg.exe`. Archives go to the top-level `dist/`, which no build step deletes.

`qlogg.exe`, the archives, installers and packages need an x86-64 Linux build
host; the `archive-host` profile (`os.name` Linux, `os.arch` amd64) turns them on. On
any other host the module builds `target/qlogg.jar` only, and `SmokeTest`
runs against it.

---

## Universal jar

`target/qlogg.jar` holds the bootstrap classes at the root, the application
jars under `META-INF/qlogg/lib/`, the six SWT fragments unmodified under
`META-INF/qlogg/swt/<target>.jar`, a SHA-256 for every native,
`index.properties` (layout in `qlogg-bootstrap/README.md`), and `LICENSE`,
`NOTICE` and `THIRD-PARTY.txt` under `META-INF/`. The maven-shade
plugin cannot merge the fragments, which share class names.

`qlogg-<version>.zip` holds `qlogg/lib/qlogg.jar`, `qlogg/bin/qlogg`,
`qlogg/qlogg.exe`, `qlogg/qlogg.cmd`, `qlogg/register-associations.cmd`,
`LICENSE`, `NOTICE` and `THIRD-PARTY.txt` (every bundled third-party component and its license text).

## Bundles

Each bundle carries the same universal jar plus a runtime for its target, so
every target runs the jar that was tested.

| Target | Archive | Layout |
|---|---|---|
| Linux | `-portable.tar.gz` | `qlogg/bin/qlogg`, `qlogg/lib/qlogg.jar`, `qlogg/lib/swt/`, `qlogg/runtime/`, `qlogg/portable.txt` |
| Windows | `-portable.zip` | `qlogg/qlogg.exe`, `qlogg/qlogg.cmd`, `qlogg/register-associations.cmd`, `qlogg/lib/qlogg.jar`, `qlogg/lib/swt/`, `qlogg/runtime/`, `qlogg/portable.txt` |
| macOS | `.zip` | `QLogg.app/Contents/{Info.plist, MacOS/QLogg, app/QLogg.cfg, app/lib/qlogg.jar, runtime/, Resources/QLogg.icns, Resources/swt/}` |

`lib/swt/` holds the target's natives, `about.html` and `about_files/`,
unzipped from the same fragment the jar carries, so the bootstrap verifies
them against the jar's digests and never writes there. The macOS bundle has no
`lib/swt/`: rcodesign re-signs every Mach-O file in the bundle, which changes
the jnilib bytes, so the bootstrap extracts the natives from the jar into the
user cache on first start, with Eclipse's signatures intact. The SWT notices
are in `Resources/swt/`.

`portable.txt` (from `src/dist/`, CRLF in the Windows zip) or `qlogg.conf`
selects portable mode; where either goes is in [Settings
directory](../qlogg-app/docs/settings.md#settings-directory). Only the
archives hold the marker: `posix-bundle` adds it to the tree for `tar` and
deletes it before the packages are built, and the Windows zip adds it from
outside the tree, which the setup lists whole. No archive holds `qlogg.conf`,
which QLogg writes at its first save, so unpacking a newer archive over a
portable folder keeps the settings.

The runtime is linked by the build JDK's `jlink` from the target JDK's jmods
(Temurin 21.0.13.1+2, and 21.0.12.1+1 for windows-aarch64, which has no
21.0.13.1+2 build; downloaded and verified by SHA-256) with
`java.base,java.logging,java.management,java.naming,jdk.charsets,jdk.unsupported,jdk.zipfs`,
`--strip-java-debug-attributes`, `--no-header-files` and `--no-man-pages`.
`jdk.charsets` carries the Encoding menu's CJK charsets, which `java.base`
lacks on Windows and macOS. `java.management` lets `LauncherProbe` read the JVM
options `qlogg.cmd` passes to the bundle's runtime. `jdeps` reports
`java.desktop` too, used only by `SWT_AWT`, so it is left out. `--strip-debug` is not used: it runs the host
`objcopy` on target binaries and fails across targets.

The `runtime` macro deletes `keytool` (`keytool.exe` on Windows) from
`runtime/bin/` on every target, before the macOS bundle is signed. QLogg does
not run it.

The `windows-bundle` macro deletes `ucrtbase.dll` and every `api-ms-win-*.dll`
from `runtime/bin/` on both Windows targets: Windows 10 and later load the
system Universal C Runtime even when an app-local copy exists. The bundle keeps
`msvcp140.dll`, `vcruntime140.dll` and, on windows-x86_64, `vcruntime140_1.dll`.

jlink on Linux writes the `legal/<module>/` notices as symlinks into
`legal/java.base/`. The build replaces them with copies before signing,
because Ant's `zip` stores a link as a regular file and the macOS seal must
match what unzips.

## Installers and packages

Each is built from its target's bundle tree after the bundle archive, by the
`windows-installer` and `linux-packages` macros. `InstallerArt.java`, run with
jsvg at build time, renders the hicolor PNGs (16 to 512 px) and the NSIS
bitmaps from `src/icons/qlogg.svg` into `target/installer/art`; the images
hold no text, so host fonts do not change them. The NSIS bitmaps are
`welcome-<scale>.bmp` (164 x 314 at 100%) and `header-<scale>.bmp` (150 x 57
at 100%) at 100, 125, 150, 175, 200, 250 and 300%, each dimension `(size *
scale + 50) / 100`, plus `art.nsh`, which defines the scale list, the 100%
widths and the background colors for `qlogg.nsi`.

| Output | Tool | Contents |
|---|---|---|
| `qlogg-<version>-windows-<arch>-setup.exe` | `makensis` 3.09 from Ubuntu 24.04's `nsis` and `nsis-common` packages, unpacked with `dpkg-deb`; `src/windows/qlogg.nsi` | The bundle tree, installed per machine, per user or portable |
| `qlogg-<version>-linux-<arch>.deb`, `.rpm` | `nfpm` 2.48.0; `src/linux/nfpm.yaml` | `/opt/qlogg` (the bundle tree), `/usr/bin/qlogg` linking to `/opt/qlogg/bin/qlogg`, `/usr/share/applications/qlogg.desktop`, `/usr/share/icons/hicolor/*/apps/qlogg.png` and `scalable/apps/qlogg.svg` |

### Windows setup

`qlogg.nsi` builds an `amd64-unicode` installer for both Windows targets; on
arm64 it runs under x64 emulation and installs the arm64 runtime.

Setup and its uninstaller run unelevated (`RequestExecutionLevel user`,
manifest `asInvoker`); per-user and portable installs never raise UAC. When
setup is not elevated, Next on the type page with All users selected starts
setup again through `ExecShell` with the `runas` verb, which raises UAC, and
exits. The new instance gets `/MACHINE /ELEVATED`, plus `/DESKTOP`,
`/NOEXPLORERMENU` and `/D=<folder>` when given or chosen, and `/ELEVATED`
skips the welcome, license and type pages: it opens on the components page
with Back disabled. A declined prompt keeps the type page and says so.
`ExecShell` is stock NSIS, so setup needs no UAC plugin. An all-users
uninstaller started unelevated starts `uninstall.exe` again the same way.
Silent `/MACHINE` from an unelevated caller, install or uninstall, exits 5
and writes the reason to standard error (the inherited handle, else the
calling console). Without elevation the default choice is an existing
per-user install, then an existing all-users one (not for `/S`), then the
current user; elevated, an existing all-users install, then a per-user one,
then all users (the current user for `/S`).

| Choice | Folder | Writes |
|---|---|---|
| All users (`/MACHINE`) | `$PROGRAMFILES64\QLogg` | `uninstall.exe`; `HKLM` Installed apps entry `...\Uninstall\QLogg`, DisplayName "QLogg" (`DisplayIcon` `qlogg.exe`, publisher, version, size), Start menu `QLogg.lnk` for all users, `QLogg.File` ProgID listed in `.log` and `.txt` `OpenWithProgids`, `Applications\qlogg.exe`, and the Explorer menu verb |
| Current user (`/USER`) | `$LOCALAPPDATA\Programs\QLogg` | The same under `HKCU` and the user's Start menu, except the Installed apps entry: `...\Uninstall\QLogg.User`, DisplayName "QLogg (current user)" (the `UserEntryName` string) |
| Portable (`/PORTABLE`) | `$DOCUMENTS\QLogg` by default | `portable.txt`, the archives' marker, unless one exists; nothing else |

- The license page has a Next button and asks for no acceptance; the line
  above the text reads "Press Page Down to see the rest of the license."
- The finish page has Run QLogg, checked, and Create a desktop shortcut,
  unchecked and hidden for portable; `/DESKTOP` selects the shortcut in a
  silent install. The shortcut goes on the common desktop for all users and on
  the user's desktop for the current user. Setup never removes one; the
  uninstaller does, retrying each shortcut for up to 10 s while another
  process holds it open. Run QLogg executes `explorer.exe <folder>\qlogg.exe`:
  Explorer hands the file to the running shell, which starts it with the
  signed-in user's unelevated token, so an all-users install started as
  administrator does not run QLogg elevated. A silent install starts nothing.
- An existing install sets the default folder and the default choice. A
  regular install in another folder of the same scope is uninstalled first.
  Each scope reads only its own key (`UNINSTKEY`, `USER_UNINSTKEY`), and so
  does the uninstaller, which takes its scope from whether `HKLM` `QLogg`
  names its folder. Windows merges two Installed apps entries with the same
  key name, DisplayName and version into one, which then runs only the
  all-users uninstaller. A per-user install also reads the `HKCU` `QLogg` key
  that earlier versions wrote, and deletes it.
- A per-user install needs `$LOCALAPPDATA`, which NSIS reads from the shell
  and which is empty when the profile variables name another user's profile
  (measured on the CI standard-user run). The type page refuses For me only
  with a message; silent setup exits 3 and writes the reason to standard
  error. `/D` does not lift the refusal.
- When `lib\qlogg.jar` exists, setup deletes `lib\` and `runtime\` before
  copying, so an upgrade leaves no file of the older version. Settings,
  `logs\`, `portable.txt` and `qlogg.conf` in a portable folder stay.
- The directory page refuses a regular install into a folder holding
  `portable.txt` or `qlogg.conf`, which would run portable, and a portable
  copy into a folder holding `uninstall.exe`, under `$PROGRAMFILES64`,
  `$PROGRAMFILES32` or `$WINDIR`, or one it cannot write. Silent setup exits 2 instead.
- Open With covers `.log` and `.txt` and never a script type: a ProgID in the
  `OpenWithProgids` of `.cmd` or `.bat` stops ShellExecute running batch files.
- The components page holds one choice, "Add to Explorer right-click menu"
  (the `ExplorerMenu` string), checked; portable skips the page, and
  `/NOEXPLORERMENU` clears it in a silent install. It writes the static verb
  `Software\Classes\*\shell\QLogg.Open` (`MUIVerb` "Open in QLogg", `Icon`
  `"<folder>\qlogg.exe",0`, `command` `"<folder>\qlogg.exe" "%1"`). Every
  regular install deletes the key before the section runs, so clearing the
  choice on an upgrade removes it, and the uninstaller always deletes it.
  Either then deletes `*\shell` and `*` when they hold no value or subkey
  (`DeleteRegKey /ifempty`). A verb
  under `*` is no type's default verb, so double-click and ShellExecute's
  `open` keep each type's handler. Explorer starts `qlogg.exe` once per
  selected file, and each launch hands its file to the running instance.
- `NsisFiles.java` writes `files.nsh`: a `File` per bundle file and a
  `Delete` and `RMDir` per file and folder for the uninstaller, sorted by
  name. The uninstaller removes exactly those, `uninstall.exe`, the shortcuts
  and the registry keys; the settings under `%APPDATA%\QLogg` stay.
- The file properties carry the version, `QLogg` as company and the
  copyright. The installer and uninstaller use `qlogg.ico`.
- Bitmaps: setup is DPI-aware (`ManifestDPIAware`), so the welcome and finish
  image control (109 x 193 dialog units) and the header image control (1046)
  grow with the display scale, and MUI stretches its bitmap to fit
  (`LoadImage` at the control size), which blurs a 100% bitmap.
  `SetWelcomeArt` and `SetHeaderArt` (`SCALED_ART`), called from the welcome
  and finish show functions and from `HeaderArtGuiInit` (the GUI init of setup
  and the uninstaller), replace it with the scale whose bitmap width is nearest
  the control's client width, a tie taking the larger, loaded at its own size.
  They set `SS_CENTERIMAGE`, so the control keeps its size and centers the
  bitmap, clipping a larger one, and set the control's background to the
  bitmap's, so a smaller one leaves a margin of the same color. Each extracts
  only the chosen bitmap to `$PLUGINSDIR`. The scales add 145 KB to setup.
- Signing: `qlogg.exe` is in the bundle tree before the macro runs, so a
  signing step goes between `exe` and the bundles. `-DSIGN_UNINSTALLER=<cmd>`
  and `-DSIGN_INSTALLER=<cmd>` turn on `!uninstfinalize` and `!finalize`,
  which run the command on the file; dist.xml passes neither.

### Linux packages

The package version is the project version with `-` replaced by `~`
(`0.2.0-rc1` as `0.2.0~rc1`, release `1`), so a release candidate sorts before
its release. The `.deb` depends on `libgtk-3-0t64 | libgtk-3-0`, the `.rpm`
on `gtk3`. The desktop entry sets `StartupWMClass=QLogg`, the main window's
`WM_CLASS` under GTK 3 (measured with `xprop`, and checked by CI), so the
GNOME dock shows the QLogg icon. Its `MimeType` lists `text/plain` and
`text/x-log` for Open With. The package scripts are empty: the icon cache and
desktop database update through the distributions' file triggers.

## Reproducible output

Every archive entry, and every entry of `qlogg.jar`, is dated
`project.build.outputTimestamp` (parent POM; the release commit sets it to the
release date):

- `jar` and `zip` take `modificationtime` from `archive.time`, the instant's
  UTC wall time. Ant writes DOS entry times in the host time zone, so every
  host then writes the same fields.
- The `-portable.tar.gz` bundles are written by GNU `tar` with entries sorted
  by name, one mtime, owner and group 0, and modes set by `chmod` beforehand,
  then compressed by Ant's `gzip`. Ant's `tar` has no `modificationtime`.
- `qlogg.jar` is byte-identical across build hosts under one JDK: `dist.xml`
  rewrites the native digests to LF and sorts the `index.properties` lists by
  name, case-sensitive.
- The installers and packages store file times. Before `nfpm` and `makensis`
  run, the `date-tree` macro dates the bundle tree and the installer bitmaps
  `project.build.outputTimestamp`; `nfpm.yaml` sets that time and umask 022
  for the files outside the tree. `NsisFiles` sorts the installer's file
  list, and `nfpm` sorts its own.
- launch4j's `ld` stamps `qlogg.exe` with the link time. `PeTimestamp.java`
  rewrites the COFF time stamp and the PE checksum to the values `ld` writes
  with `SOURCE_DATE_EPOCH` set, which cannot reach `ld` inside the Maven JVM.

The jlink runtimes and the ad-hoc rcodesign signature hold no build time.
jlink runs under `LC_ALL=C.UTF-8` and `-J-Xshare:off`: under a non-UTF-8 locale,
or with another CDS archive in the build JDK, it writes a different
`lib/modules`. The CDS archive decides whether the classes jlink generates
carry a `SourceFile` attribute, and the Temurin container image's
`classes.jsa` differs from the release archive's. `.github/scripts/check-reproducible.sh` builds twice,
the second time under another time zone, umask and locale, and compares the SHA-256 of every archive,
`qlogg.exe` and module jar. The version is part of the output: two builds
compare equal only with the same `-Drevision`, so the `release` job passes the
tag's.

## Release artifacts

`package -Psbom org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom` writes
`dist/qlogg-<version>-sbom.cdx.json`, a CycloneDX aggregate SBOM of the modules
and their compile and runtime dependencies (`qlogg-bench` excluded). The goal
runs once, in the parent, after every module has packaged, and reads the module
jars from the reactor. Do not bind it to `package`: the parent's `package` runs
before the modules build, so the goal requests their jars from Maven Central. The
`sbom` profile here adds the six SWT fragments as dependencies so the SBOM
lists them; `stage-lib` excludes `org.eclipse.platform`, so the archives are
unchanged. In CI, `.github/scripts/sbom-components.sh` adds the Temurin
releases `dist.xml` downloads, launch4j's header and NSIS.

The archive and SBOM names carry the version `-Drevision` sets: the tag's
version for a `v*` tag, `<revision>+<short commit>` for any other CI build.
A pushed `v*` tag ends in a draft GitHub Release holding the seven archives, the
two installers, the four packages, `SHA256SUMS` and the SBOM, a pre-release when the tag has a suffix
(`v0.2.0-rc1`); the maintainer reviews and publishes it. A green
push to `main` replaces the `continuous` pre-release with the same set. Steps and
recovery are in the root `README.md` under "Continuous integration".

## Launchers

| Launcher | Behavior |
|---|---|
| `bin/qlogg` (POSIX sh) | Java from `<home>/runtime`, else `$JAVA_HOME`, else `PATH`; `--enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=50 -Xms64m -XX:GCTimeRatio=1`, plus `-XstartOnFirstThread` on Darwin. Follows `$0` symlinks with plain `readlink`. `--gtk4` before `--` sets `SWT_GTK4=1` and is not passed to Java. Runs only `uname` and `readlink` besides Java |
| `qlogg.exe` (launch4j 3.50, `gui`) | Runs `lib\qlogg.jar` with a Java 21+ found in `runtime`, `%JAVA_HOME%`, then `%PATH%`; 64-bit only; `--enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=50 -Xms64m -XX:GCTimeRatio=1`. The launch4j header is a 32-bit x86 program that starts `javaw.exe` as a child process; on Windows arm64 it runs under x86 emulation. CI's `bundle windows-aarch64` job (`windows-11-arm`) checks that it starts `runtime\bin\javaw.exe` with PE machine `ARM64` and opens the main window (`.github/scripts/check-bundle.ps1`). The header reads its command line in the ANSI code page with best-fit mapping and starts Java with `CreateProcessA`: a character outside the code page arrives as `?` or as an ASCII look-alike (U+FF41 as `a`, U+0131 as `i`, U+FF02 as `"`, U+FF0A as `*`), quoted or not (measured, below) |
| `qlogg.cmd` (cmd.exe) | Runs `lib\qlogg.jar` with `runtime\bin\javaw.exe`, else `%JAVA_HOME%\bin\javaw.exe`, else `javaw.exe` on `PATH`, with no version or 64-bit check; `--enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=50 -Xms64m -XX:GCTimeRatio=1 -Dqlogg.launcher=cmd`, with the arguments in `QLOGG_CMDLINE`. Started as `cmd.exe /c ""<qlogg.cmd>" ..."` (Explorer, a shortcut, PowerShell, another program), it takes the arguments from `%cmdcmdline%` as the caller wrote them, before that cmd.exe's parse, and ends that cmd.exe with `exit`, so a command after an unquoted `&` never runs. From a command prompt or a batch file it reads `%*` back from an echoed `rem` line through a file in `%TEMP%`; where that fails, as under `cmd /q`, it runs nothing and prints `qlogg.cmd could not read its arguments through "<file>".` with exit 1. A name outside the ANSI code page arrives intact this way under console code page 65001 (measured on CI). Either way the script sets `QLOGG_CMDLINE` to the argument text through delayed expansion, which follows parsing, and starts Java with `-Dqlogg.launcher=cmd` and no arguments; the bootstrap splits the variable by the C runtime rules (`qlogg-bootstrap`). Java reads the environment in UTF-16, so a name outside the ANSI code page arrives intact and the Java launcher expands no wildcard. The calling cmd.exe acts on an unquoted `\|`, `<` or `>` before the script runs; no file name holds one. Every line starts with `@`: a calling batch keeps its echo state, and the prompt path restores echo on after its read-back. `start "" /b` returns at once and gives Java the console's output: `-v`, `-h` and errors print when it is redirected or piped, and not in an interactive console, as with `qlogg.exe` (measured on Windows 11 26H2). Once its window opens QLogg closes both handles (`CmdStreams`), so a caller reading a pipe reaches its end while QLogg runs. A double-clicked `qlogg.cmd` leaves no console open (same host). Without Java it prints a message, pauses and exits 1. Neither launcher starts from an install path holding `;`, which splits the `-jar` class path. It does not read `qlogg.l4j.ini` |
| `register-associations.cmd` (cmd.exe) | `HKCU` only: the `QLogg.File` ProgID in the `OpenWithProgids` of `.log` and `.txt`, `Applications\qlogg.exe` and the `QLogg.Open` verb, all for the `qlogg.exe` beside it; `/u` removes them, then `*\shell` and `*` when `reg query` lists nothing under them (`reg` has no `/ifempty`). The path expands after cmd splits the line, so a folder name holding `&`, `(` or `^` stays literal |
| `QLogg.app/Contents/MacOS/QLogg` | The `jpackageapplauncher` binary from the target JDK's `jdk.jpackage` module. It reads `Contents/app/QLogg.cfg` and starts `Contents/runtime` with `-XstartOnFirstThread`, `--enable-native-access=ALL-UNNAMED`, `-XX:MaxRAMPercentage=50`, `-Xms64m` and `-XX:GCTimeRatio=1`; the layout copies jpackage's |

### Names outside the ANSI code page

Measured on `windows-2025` and `windows-11-arm`, ANSI code page 1252, with a
CJK name and a fullwidth `a` (U+FF41) beside the decoys `zz.txt` and `a.txt`. Explorer's drop and ShellExecute pass the long name,
unquoted unless it holds a space.

| Launch | `qlogg.exe` hands Java | QLogg | `qlogg.cmd` |
|---|---|---|---|
| Drop or ShellExecute, CJK | `??.txt`, unquoted | The Java launcher expands `??.txt` to every matching file; QLogg opens no file named at that launch and shows a message naming the pattern | Opens the name |
| Quoted, CJK | `"??.txt"` | Not opened; the message names it | Opens the name |
| Any form, U+FF41 | `a.txt` | Opens the decoy `a.txt` | Opens the name |

QLogg cannot recognize a look-alike, since `javaw` never receives the
original name. Through `java -jar` the Java launcher converts without
look-alikes, so U+FF41 arrives as `?`.

### Signing

`rcodesign` 0.29.0 ad-hoc signs each `QLogg.app` on Linux with
`src/macos/qlogg.entitlements` (`allow-jit`,
`allow-unsigned-executable-memory`, `disable-library-validation`), which the
JVM needs under the hardened runtime. rcodesign cannot sign a shell script as
the main executable, so the macOS launcher is the jpackage binary.
The bundles are not notarized.

### CI bundle check

The CI `bundle` job runs each archive on its own runner through
`.github/scripts/check-bundle.sh` (Linux, macOS) or `check-bundle.ps1`
(Windows). A Linux or Windows archive must hold `portable.txt` and no
`qlogg.conf`. For the target's bundle and then the universal zip, with
setup-java's JDK as `JAVA_HOME`:

| Check | Linux, macOS | Windows |
|---|---|---|
| Runtime | `os.arch`, `jdk.charsets`, no `bin/keytool`; `codesign --verify --deep --strict` on a macOS bundle | The same, with no `keytool.exe`, plus the `javaw.exe` child's image (the bundle's `runtime\bin\javaw.exe`, or the JDK's for the universal zip) and PE machine |
| Log read | `-d 1` on stderr | `-l`, `logs\qlogg.log`: `javaw` output is not captured |
| Started | `Main window open`, no `Unhandled failure` or `UI task failed` | The same, through `qlogg.exe` and `qlogg.cmd` |
| Handoff | A second `bin/qlogg` or `QLogg` exits 0; the primary logs `Handoff of 1 files taken` | The same, the second launch through `javaw.exe`, because neither launcher reports the child's status |
| Native access | None | The child's command line holds `--enable-native-access=ALL-UNNAMED`. Two more handoffs through `java.exe`: with the flag, no `restricted method` warning on stderr; without it, the warning, at the foreground grant's first link |
| `qlogg.cmd` | None | In the bundle, over `LauncherProbe`: from folders named `with space` and `amp & pct 100%`, the folder's `runtime`, its `lib\qlogg.jar`, `--enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=50 -Xms64m -XX:GCTimeRatio=1`, `-Dqlogg.launcher=cmd` and 11 quoted arguments holding a space, `&`, `^`, `%`, `!`, `(`, `)`, `'` or nothing; through ShellExecute, as Explorer drops them, unquoted `<folder>\a&mark`, `c^d.txt`, `e!f.txt`, `g%h%.txt` with `h` defined, `(p).txt`, a name holding U+00E9 and a quoted `"x y&mark.txt"`; at a command prompt (`cmd /d` reading standard input), `a^&mark`, `c^^d.txt`, `e!f.txt`, `g%q9z%.txt`, `i^\|mark`, `k^>l.txt`, `"x&y.txt"`, `(p).txt` and the 11 quoted arguments. Each name arrives unchanged and `mark.cmd`, which writes `marker.txt` when run, never runs; without `runtime`, `JAVA_HOME` before `PATH`, and exit 1 without either; `-v` prints the version |
| Names outside the ANSI code page | None | A CJK name beside a decoy `zz.txt` through `qlogg.exe` and `qlogg.cmd`, unquoted and quoted, by ShellExecute and from a console batch file under code page 65001, with `-l -d 2`: `qlogg.cmd` logs `Command-line file: <the name>`; `qlogg.exe` logs the expansion message unquoted and `Not opened: <folder>\??.txt.` quoted; no log names `zz.txt`. With an instance running, an unquoted `qlogg.exe` launch opens its own window with the message and hands nothing over |
| `call qlogg.cmd` | None | Batch files with echo on and with echo off call `qlogg.cmd` with output captured through a pipe: echo keeps its state after the call, and the pipe ends while QLogg runs |

A failed handoff opens its own window and does not exit, so each script bounds
the second launch at 60 s.

The same job then checks the installers and packages on that runner:

| Script | Checks |
|---|---|
| `check-linux-packages.sh` | Installs the `.deb` with `apt`: version, `/usr/bin/qlogg` link, no `qlogg.conf` or `portable.txt`, `desktop-file-validate`, `StartupWMClass=QLogg`, every icon. Runs `check-bundle.sh` with `QLOGG_HOME=/opt/qlogg` through `/usr/bin/qlogg`, which also requires a main window with `WM_CLASS` `"QLogg", "QLogg"`. Removes the package and checks the files are gone. Then, in `fedora:44` pinned by digest: `dnf install` of the `.rpm`, `rpm -V`, `qlogg -v`, no `qlogg.conf` or `portable.txt`, `dnf remove` |
| `installer-screenshots.ps1` | Walks the setup's pages by window message (all-users install with the desktop shortcut ticked, its uninstaller, a portable install, then `/MACHINE /ELEVATED /NOEXPLORERMENU`, which must open on the components page with Back disabled and write no verb) and saves each with `PrintWindow` to `installer-screenshots\`, uploaded as `installer-screenshots-<target>`. After each install an `explorer.exe` must have started `qlogg.exe`, and QLogg must have the shell's token elevation (0 for an unelevated shell; the hosted runners' shell is elevated); the all-users walk must write the common desktop shortcut and the `HKLM` `QLogg.Open` verb and its uninstaller remove both, and the portable walk writes no shortcut. Every page must show a bitmap control, each with `SS_CENTERIMAGE`; the log gives each control's client size and the window DPI |
| `check-installer.ps1` | 7-Zip extracts `welcome-<scale>.bmp` and `header-<scale>.bmp` from the setup: one of each per scale, with the sizes `InstallerArt` renders. The setup and each installed `uninstall.exe` request `asInvoker`. For `/MACHINE` and then `/USER`: the Installed apps entry, the shortcut target, no desktop shortcut, `QLogg.File` in both `OpenWithProgids` with the default handler unchanged, no `qlogg.conf` or `portable.txt`, ShellExecute runs a `.cmd`, no QLogg process from the folder 5 s after setup exits; `check-bundle.ps1` with `QLOGG_HOME` set to the installed folder; an install over it with `/DESKTOP` removes a stale runtime file, adds no `qlogg.conf`, and writes the shortcut to that scope's desktop only; a silent uninstall removes the folder, shortcuts and keys, keeps `%APPDATA%\QLogg`, and a `.cmd` still runs. Each entry has its scope's key and DisplayName and no other scope's key. Then both scopes side by side, the per-user install over a seeded `HKCU` `QLogg` key: that key is deleted, `HKLM` `QLogg` and `HKCU` `QLogg.User` hold their names and folders, and each uninstaller removes only its own folder and key. A standard local user then runs `/S /USER` with the caller's profile variables, which must exit 3 with its message and write no entry or `\Programs\QLogg`; `/S /USER` with its own, which must run at medium integrity unelevated and install, `/S /MACHINE`, which must exit 5 with its message on standard error and write nothing to Program Files, and the uninstaller. Then `/PORTABLE /DESKTOP`: a one-line `portable.txt` and no `qlogg.conf`, no registry entry, no Start menu or desktop shortcut, no QLogg started, QLogg writes its `-l` log in the folder, and an install over it keeps `portable.txt`, `qlogg.conf` and `logs\`. Exit 2 for `/USER` into the portable folder, into a folder holding only `qlogg.conf`, and for `/PORTABLE` under Program Files. Each regular install and uninstall checks the `QLogg.Open` key, its `Icon` and `command`, and that the default verb of `.log` and `.txt` (`AssocQueryString` `ASSOCSTR_COMMAND`) is no QLogg command and, after an uninstall, the one read before any install; while installed it may also be `OpenWith.exe`: a type with no user choice whose `OpenWithProgids` gains `QLogg.File` resolves to the Windows app picker (measured for `.log` on `windows-2025`). `/NOEXPLORERMENU` over a per-user install removes the key, keeps Open With, and leaves both default verbs as they read with the key. Then `register-associations.cmd` from the portable zip, unpacked into `Q & Logg (zip)`: its keys under `HKCU` only, default verbs unchanged, a `.cmd` still runs, Open in QLogg in the context menu of a `.log`, the verb on a two-file selection (`FolderItems.InvokeVerbEx`) hands both files to the running QLogg, and `/u` removes every key. No uninstall, `/NOEXPLORERMENU` install or `/u` leaves a `Software\Classes\*` or `*\shell` key absent before the first install |

## Icons

`src/icons/qlogg.svg` is the OS-facing icon (window, taskbar, Dock, `.exe`,
`.icns`); `qlogg-light.svg` is for in-app use with the light palette.
`tools/icons.sh` renders `qlogg.ico`, `QLogg.icns` and the PNGs in
`qlogg-ui-swt` from them; the build uses the committed outputs. The package
icons and installer bitmaps are rendered at build time
([Installers and packages](#installers-and-packages)).

## Testing

`SmokeTest` (tag `swt`) runs at `integration-test` against the built jar and,
on linux-x86_64, the extracted bundle. Each run ends by destroying the
process's descendants, then the process, so the macOS relaunched child does not
outlive the build:

| Case | Checks |
|---|---|
| `java -jar qlogg.jar -v` | Prints the manifest version as its first line |
| Jar content | LF native digests and `natives.<target>` lists sorted by name, so a Windows build writes the same jar |
| Jar notices | `META-INF/` holds `LICENSE`, `NOTICE` and `THIRD-PARTY.txt` |
| `java -jar qlogg.jar` | Opens a 900 x 600 window at 50,40 and writes no `logs` folder |
| Linux bundle with `-l`, `PATH` holding only `uname` and `readlink` | Reports its own `runtime/` as `java.home`, opens the same window, and after SIGTERM leaves one `qlogg-<pid>.log` holding the shutdown hook's line and no other file in `logs` |
| Linux bundle `bin/qlogg --gtk4`, stub `runtime/bin/java` | Exports `SWT_GTK4=1`, drops `--gtk4` before `--` and passes one after it as a file name |
| Linux bundle runtime | Has `jdk.charsets` and no `keytool`, and supports every charset the Encoding menu lists (`CharsetProbe`) |

`LauncherProbe` is not a test: `check-bundle.ps1` runs it as `lib\qlogg.jar`
under `qlogg.cmd`, and it writes `java.home`, the class path, the JVM options
and the arguments, as the bundle's `CmdArguments` reads them, to the file
`QLOGG_PROBE_OUT` names.
