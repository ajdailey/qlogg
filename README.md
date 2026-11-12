<picture>
  <source media="(prefers-color-scheme: dark)" srcset="qlogg-dist/src/icons/qlogg.svg">
  <img src="qlogg-dist/src/icons/qlogg-light.svg" alt="QLogg logo" width="96" height="96">
</picture>

# QLogg

QLogg is a cross-platform viewer for large and growing log files, written in
Java 21 with SWT. It makes no outbound network connections; its only socket is
a loopback endpoint that passes files from a second launch to the running
instance.

QLogg is not an editor or a log collector, and it runs no macros, scripts or
plugins.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/qlogg-dark.png">
  <img src="docs/images/qlogg-light.png" alt="QLogg main window with highlighters, search results in the filtered view and the overview bar" width="880">
</picture>

## Features

| Area | Summary | Guide |
|---|---|---|
| Large files | Any size and line count; content stays on disk while indexing runs in the background, and the first lines show at once | [Opening files](docs/user-guide.md#opening-files) |
| Opening | Command line, File > Open, Recent Files and Recently Closed with Open All, Reopen Closed Tab, Favorites, drop on the window, clipboard text (up to 16 MiB, in memory); reload keeps marks and labels | [Opening files](docs/user-guide.md#opening-files), [Recent files](docs/user-guide.md#sessions-recent-files-and-favorites) |
| Following | Follow a growing file; reopen by name after truncation, replacement, rotation or a retargeted link, with retries; Keep file closed for writers that rename over the file | [Following a growing file](docs/user-guide.md#following-a-growing-file) |
| Encodings | Detection (BOM, UTF-16, UTF-8, ISO-8859-1 fallback) and a forced charset per file | [Opening files](docs/user-guide.md#opening-files) |
| Search | Regex or literal, match case, whole word, inverse, combined expressions, in selection, start and end lines, auto-refresh, history, predefined filters, search with the selection, quick find, search all open files, search the files of a folder without opening them (exclude globs, ten kept result sets with pin, `F4` steps) | [Search](docs/user-guide.md#search-and-the-filtered-view) |
| Filtered view | Matches, marks or both, with source line numbers, kept result tabs, context lines and range filters; an overview bar of matches and marks | [Search](docs/user-guide.md#search-and-the-filtered-view) |
| Highlighting | Highlighter sets (regex or text, line or match, capture groups, opacity, variance), automatic set selection, color labels, match highlight, keyword triggers on appended lines | [Highlighters](docs/user-guide.md#highlighters-and-color-labels) |
| Marks | Marks and mark comments, gutter bullets, Marks List | [Marks](docs/user-guide.md#marks-and-quick-find) |
| Timestamps | Format detection and user formats, time zones, caret and selection times, offsets, Go to Time, Scroll All Tabs and Time Sync groups, time spread on the overview bar | [Timestamps](docs/user-guide.md#timestamps) |
| Viewing | Line numbers, go to line and `+line:N`, Go Back and Go Forward over jumps, block selection, copy with line numbers, save of a selection or the filtered view, text wrap, whitespace and line-end markers, hidden ANSI colors, folding of multi-line entries, font zoom, light, dark and system themes, view color schemes apart from the theme (Light, Dark, nine bundled and user scheme files) | [Moving around](docs/user-guide.md#moving-around), [Selecting](docs/user-guide.md#selecting-copying-and-saving), [Folding](docs/user-guide.md#folding-multi-line-entries), [View options](docs/user-guide.md#view-options), [View colors](docs/user-guide.md#view-colors) |
| Multiple files and windows | Movable, renamable tabs with a dot for new lines or matches, a tab menu, Open, tab list (with the hidden count) and Close buttons beside the tabs, Switch to File, and an option to close the window with its last tab; several main windows, tabs moved between them as they are, Always on Top per window | [Windows](docs/user-guide.md#windows-and-the-running-instance) |
| Sessions and settings | Session restore of tabs, marks, offsets and sync groups; remembered position, encoding and time format per file; remembered window state; Tools > Preferences with a shortcut editor | [Sessions](docs/user-guide.md#sessions-recent-files-and-favorites), [Preferences](docs/user-guide.md#preferences) |
| Portable settings | Settings beside the installation, or on Linux beside `bin/qlogg`, when `qlogg.conf` is there, or in `--settings <dir>` | [Settings directory](#settings-directory) |
| Single instance | A second launch hands its files to the running QLogg over an authenticated loopback connection; any failure opens a window of its own. Ending the process saves settings and the session | [Windows](docs/user-guide.md#windows-and-the-running-instance) |
| Tools and Help | Command palette; scratchpad transforms; path commands and Open in Editor; Keyboard Shortcuts, Show Log with a warning marker, debug log file, Report an Issue, and the user guide in its own browser window (on Linux in portable and `--settings` mode, the system browser); macOS application menu | [Command palette](docs/user-guide.md#moving-around), [Scratchpad](docs/user-guide.md#scratchpad), [Help](docs/user-guide.md#help-menu-and-logs) |

The guide's [Menus](docs/user-guide.md#menus) section lists every menu item,
and Help > Keyboard Shortcuts every key binding.

## Building

Build with JDK 21 and the Maven wrapper:

```sh
xvfb-run -a ./mvnw clean verify
```

`verify` runs Spotless, Checkstyle, SpotBugs with find-sec-bugs, the ArchUnit
rules and every test, and builds the universal jar and the bundle for the build
host. Fix formatting with `./mvnw spotless:apply`.

| Command | Builds |
|---|---|
| `./mvnw clean verify` | Universal zip and the host target's bundle |
| `./mvnw clean verify -Dqlogg.target=<target>` | Universal zip and that target's bundle; compiles the UI against that target's SWT fragment and skips the `swt` tests |
| `./mvnw clean verify -Pall-targets` | Universal zip and all six bundles |

### GUI tests

Tests tagged `swt` open windows.

| Host | `swt` tests |
|---|---|
| Linux | Run when `DISPLAY` is set, skipped otherwise. Surefire sets `GDK_BACKEND=x11`, so under `xvfb-run` the windows go to Xvfb even in a Wayland session |
| Windows | Always run |
| macOS | Always run, with `-XstartOnFirstThread` |

### Archives

Archives are written to `dist/`, which no build step deletes. They are built on
Linux x86_64 only, because packaging runs `tar`, `jlink`, `jmod`, launch4j's
x86-64 Linux linker and `rcodesign`. On any other host `qlogg-dist` builds and
smoke-tests `qlogg.jar` and writes no archive.

The Temurin JDK archives and `rcodesign` are downloaded once into
`~/.m2/repository/.cache/qlogg/` (`-Dqlogg.cache.dir` overrides it) and checked
against pinned SHA-256 digests.

Builds are reproducible: with the same JDK, two builds of one commit write
byte-identical jars, `qlogg.exe` and archives, every entry dated
`project.build.outputTimestamp` from the parent POM.
`.github/scripts/check-reproducible.sh` builds twice and compares SHA-256.

### Container build

A host with Docker (or Podman with compose) and no JDK builds in the image
`docker/Dockerfile` defines: Temurin 21, git, Xvfb and GTK 3. `compose.yaml`
holds the mounts and environment and runs the same `./mvnw`.

```sh
scripts/container.sh               # clean package -Pall-targets: seven archives into dist/
scripts/container.sh check         # xvfb-run ./mvnw clean verify, SWT tests included
scripts/container.sh clean         # ./mvnw clean, and empty dist/
scripts/container.sh shell         # a shell in the build image
scripts/container.sh --rebuild     # rebuild the build image first
scripts/container.sh --out DIR     # write the archives to DIR
scripts/container.sh ./mvnw -B spotless:check
```

- Any other command runs verbatim in the image at the repository root.
- Windows uses `scripts\container.bat` with the same arguments; WSL uses the
  Linux script.
- The output directory is bind-mounted over `dist/`, so `clean` empties it and
  never removes it.
- The Maven repository, the wrapper's Maven and the JDK cache live in the named
  volume `qlogg-maven-repo`.
- On Linux the container runs as the calling user, so files in `dist/` and
  `target/` belong to that user.
- The service is `linux/amd64`, because packaging runs launch4j's x86-64
  linker; an arm64 host runs it under its engine's emulation.

### Continuous integration

`.github/workflows/ci.yml` runs on every push to `main`, every pull request and
every pushed `v*` tag. Actions are pinned by commit SHA. Linux jobs install
Xvfb and GTK 3 from the Ubuntu archive when the runner image lacks them.

| Job | Runners | Does |
|---|---|---|
| `test` | `ubuntu-24.04`, `ubuntu-24.04-arm`, `windows-2025`, `windows-11-arm`, `macos-15-intel`, `macos-15` | `./mvnw -B verify` on each target natively, SWT tests included (Linux under `xvfb-run`) |
| `package` | `ubuntu-24.04` | `./mvnw -B package -Pall-targets,sbom`; uploads the seven archives and the SBOM as `qlogg-archives`, kept 90 days for a tag and 14 otherwise |
| `bundle` | the six `test` runners | Unpacks the target's bundle, checks the runtime's `os.arch`, and starts the bundle's launcher until the main window opens. On Windows it also finds the `javaw.exe` child running `qlogg.jar` and passes only when its image is the bundle's `runtime\bin\javaw.exe` with the target's PE machine type (`ARM64` on `windows-11-arm`) |
| `test-gtk4` | `ubuntu-24.04` | The SWT modules' tests with `SWT_GTK4=1`; a test that hits a known GTK 4 failure skips itself |
| `release` | `ubuntu-24.04` | `v*` tags only, after every `test` and `bundle` job passes: runs `check-reproducible.sh`, writes `SHA256SUMS` over the `package` job's archives, checks the rebuild against it, and creates a draft GitHub Release |

CI keeps the JDK archives and `rcodesign` in `~/.cache/qlogg`, cached by the
`package` and `release` jobs and keyed on `qlogg-dist/src/build/dist.xml`; the
Maven cache holds no JDK.

#### Releases

The draft Release carries the seven archives the `bundle` jobs ran,
`SHA256SUMS` and `qlogg-<version>-sbom.cdx.json`, with GitHub's generated
release notes. Only the `release` job has `contents: write`. To release:

1. Push the tag: `git tag v<version> && git push origin v<version>`.
2. Review the draft on the repository's Releases page.
3. Publish it there, or with `gh release edit v<version> --draft=false`.

To fix a bad draft, delete the draft and the tag
(`gh release delete v<version> --cleanup-tag`) and tag again.

#### Dependencies

- `-Psbom` writes the CycloneDX SBOM to `dist/`: every compile and runtime
  dependency of the shipped modules, plus the six SWT fragments `qlogg.jar`
  carries. It leaves out build-only tools, `qlogg-bench`, the Temurin runtimes
  and launch4j's header.
- `.github/workflows/dependency-check.yml` scans the dependencies for known
  vulnerabilities weekly and on demand, failing on CVSS 7 or higher. It reads
  the NVD API key from the `NVD_API_KEY` repository secret and reviewed false
  positives from `config/dependency-check-suppressions.xml`.
- Dependabot proposes Maven and Actions updates.

## Targets

| Target | SWT fragment | Bundle |
|---|---|---|
| linux-x86_64 | `gtk.linux.x86_64` | `qlogg-<version>-linux-x86_64.tar.gz` |
| linux-aarch64 | `gtk.linux.aarch64` | `qlogg-<version>-linux-aarch64.tar.gz` |
| windows-x86_64 | `win32.win32.x86_64` | `qlogg-<version>-windows-x86_64.zip` |
| windows-aarch64 | `win32.win32.aarch64` | `qlogg-<version>-windows-aarch64.zip` |
| macos-x86_64 | `cocoa.macosx.x86_64` | `qlogg-<version>-macos-x86_64.zip` (`QLogg.app`) |
| macos-aarch64 | `cocoa.macosx.aarch64` | `qlogg-<version>-macos-aarch64.zip` (`QLogg.app`) |

- `qlogg-<version>.zip` holds the universal `lib/qlogg.jar`, which runs on all
  six targets under an installed Java 21, with `bin/qlogg` and `qlogg.exe`.
- Each bundle adds a Java runtime for its target, so it needs no installed
  Java.
- Linux targets need the system GTK 3 libraries. GTK 4 is an experimental
  opt-in on linux-x86_64: start QLogg with `SWT_GTK4=1` (GTK 4 libraries
  required). Open SWT GTK 4 defects affect scrolling and painting in the log
  view.
- The macOS bundles are ad-hoc signed and not notarized, so macOS asks the user
  to approve the first launch.

## Command line

```text
qlogg [options] [[+line:N] file]...
java -jar qlogg.jar [options] [[+line:N] file]...
```

| Option | Effect |
|---|---|
| `-h`, `--help`, `--help-all` | Print usage and exit 0 |
| `-v`, `--version` | Print `qlogg <version>` and exit 0 |
| `-d`, `--debug <level>` | stderr log level: `1` INFO, `2` DEBUG, `3` and above TRACE, or `error`, `warn`, `info`, `debug`, `trace`; WARN when absent. Show Log (INFO) and the `-l` file (DEBUG) take it when finer. It applies to QLogg's own loggers; the Java runtime's stay at INFO |
| `-l`, `--log` | Also write the log at DEBUG to `logs/qlogg.log` in the settings directory, for this run; a process that is not the running instance (`-m`, or a failed handoff) writes `logs/qlogg-<pid>.log` |
| `-m`, `--multi` | Run as an independent instance: no handoff, and it never replaces the saved session |
| `-s`, `--load-session` / `-n`, `--new-session` | Restore the last session's tabs / start without them. Without either, the session is restored when no file is given and `session.loadLast` is on |
| `-f`, `--follow` | Follow the files given |
| `--window-width <px>`, `--window-height <px>` | Client-area size, each independently; default 1024 x 768 |
| `--window-x <px>`, `--window-y <px>` | Window position; ignored on Wayland, where the compositor places windows |
| `--settings <dir>` | Settings directory, created owner-only when missing; holds `qlogg.conf` and QLogg's SWT native cache |
| `+line:N` | Open the next file at line N, once indexing reaches it; a line past the end goes to the last line |
| `--` | End of options |
| `file` | Files to open, a tab each; the last one is shown. Without `-m`, they go to the running instance for the same settings directory, with `-f` and `+line:N` |

Options combine: `-mf`, `--opt=value`, `-d2`. An unknown option or a bad value
prints a message to stderr and exits 1. An SWT start failure, such as missing
GTK libraries, exits 3.

## Settings directory

QLogg keeps its files in the first directory that applies:

| Mode | When | Directory |
|---|---|---|
| Explicit | `--settings <dir>` | `<dir>` |
| Portable | `qlogg.conf` exists in the installation directory, the parent of `lib/` | The installation directory |
| Portable (Linux) | `qlogg.conf` exists beside the `bin/qlogg` script and not in the installation directory | `bin/` |
| Per-user | Otherwise | Linux `$XDG_CONFIG_HOME/qlogg` (default `~/.config/qlogg`), Windows `%APPDATA%\QLogg`, macOS `~/Library/Application Support/QLogg` |

An empty `qlogg.conf` in either place selects portable mode.

| File | Contents |
|---|---|
| `qlogg.conf` | Settings (Java properties, `version=1`); the keys are in [qlogg-app settings](qlogg-app/docs/settings.md#keys) |
| `qlogg_session.conf` | The session: each window's tabs and Always on Top |
| `positions.conf` | Position, encoding override and timestamp choices of the last 500 files |
| `instance.lock`, `instance.endpoint` | Single-instance lock and handoff endpoint |
| `logs/` | Debug log files |
| `colors/` | User color schemes, `<id>.properties` ([format](qlogg-app/docs/settings.md#color-scheme-files)) |
| `cache/` | The user guide's copy; on Windows, the guide browser's data in `cache\webview2` |

- In explicit and portable mode on Linux, `user.home` is the settings
  directory, so GTK's `.swt/trims.prefs` lands there too.
- On Linux and macOS a launch hands its files over only when the settings
  directory belongs to the current user and no one else can write it; otherwise
  every launch opens its own window.

## Layout

| Path | Contents |
|---|---|
| `qlogg-bootstrap/` | Entry point of `qlogg.jar`: Java version check, target selection, SWT natives, nested-jar class loader |
| `qlogg-core/` | Threads, memory budget, file access, encoding, line index, documents and viewport; no SWT, no logging dependency |
| `qlogg-app/` | Command line, key bindings, controllers, view contracts, settings, search history and filters, About facts; no SWT |
| `qlogg-ui-swt/` | SWT views: main window, log views, search and quick find bars, dialogs; the toolbar icon set (`src/icons/*.svg`), rendered to PNG at build time |
| `qlogg-launcher/` | Composition, logging configuration, architecture rules |
| `qlogg-dist/` | Universal jar, launchers, runtimes and archives; icons |
| `qlogg-bench/` | Benchmark harness, JMH probes and comparison scripts; not shipped |
| `config/` | Checkstyle, SpotBugs and find-sec-bugs configuration |
| `docker/`, `compose.yaml`, `scripts/` | Container build |
| `.github/` | CI workflow and the scripts its steps run |
| `tools/icons.sh` | Renders the application icon from `qlogg-dist/src/icons/*.svg` |
| `docs/user-guide.md`, `docs/images/guide/` | The user guide Help > User Guide shows, and its screenshots |
| `tools/screenshots.sh`, `docs/images/` | Writes the light and dark screenshots and the user guide's images under Xvfb from generated mock logs (Linux) |

## Documents

| Document | Covers |
|---|---|
| `<module>/README.md` | The module's packages, contracts and links to its topic documents under `<module>/docs/` |
| `qlogg-core/TESTING.md`, `qlogg-app/TESTING.md`, `qlogg-ui-swt/TESTING.md` | Test strategy and coverage by class |
| `docs/user-guide.md` | The user guide |
| `NOTICE`, `qlogg-dist/src/dist/THIRD-PARTY.txt` | Licenses of shipped components |
