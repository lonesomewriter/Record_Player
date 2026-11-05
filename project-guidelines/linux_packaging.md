# Expectations
* APKs, .debs, or any form of executable or build should go under "/project-builds"
	- If "/project-builds" doesn't exist, create it. This is a strict folder structure you MUST follow.
		- If the build is a test/dev build: "/project-builds/dev-builds/(build-name)"
		- If the build is a release build: "/project-builds/release-builds/(build-name)""
	- A "build summary" should be logged in "/project-builds/logs"
* Builds MUST have properly bundled all proper dependencies/components required for it to run. Always check the valadity of said build(s) for any missing components, scripts, packages, or dependencies that otherwise prevent it from running on a machine.
* Always ask the user which type the build is(release, or dev), then send the build to it's respective folder
* Builds MUST be checked against all known issues / issues listed(see section: "Known Errors for Builds")
* Check the naming convention of the build type. If it defers from what it's packaging expects, it WILL NOT WORK.
* You are to NEVER clean old builds unless the user expresses EXPLICIT PERMISSION TO DO SO.
* Every path listed in a ".desktop" file MUST be correct and match the EXACT PATH of the application
* The naming convention for the Build(s) folder(s) is as follows:
    - "(Release Name Here)-(Platform Type)-(Version)"


# Linux packaging rules for AI agents

These rules apply to every Linux package this project builds. The shared
rules come first; each format then lists the steps agents most often skip
or get wrong. Fill in the project values once and reuse them everywhere.

However, if the core component(s) of the linux package flag any part of these outlined packaging rules, inform the user and ask for confirmation on which takes priority(the rule in this guideline, or the package's flag), and then abandon the flagged rule.

## Project values (fill in per project)

- App ID (reverse-DNS): `<io.github.owner.app>`
- Package name: `<pkg-name>` (lowercase; use the same name in every format where allowed)
- Main binary name: `<binary>`
- Command on PATH: `<command>`
- Desktop file name: `<app-id>.desktop`
- Icon name: `<icon-name>` (prefer the app ID)
- Window class (`StartupWMClass`): `<wm-class>`
- Runtime dependencies: `<list, with the package name for each format>`

---

## Shared rules (all formats)

### Names and versions
- Version numbers live only in the format's version field, never in the
  package name, the binary name or install paths.
- Release/revision numbers (`Release:`, `pkgrel`, Debian revision) start at 1,
  increase when only the packaging changes, and reset to 1 when the upstream
  version changes (unless the format manages this automatically).
- Names are exact. Dashes and underscores are not interchangeable. Check every
  reference character by character, especially binary names vs package names.
- RPM, Arch and Alpine versions cannot contain a dash. Use each format's own
  pre-release syntax (see each section) instead of `1.0-beta`, and confirm the
  new version sorts after the previous one.

### Builds
- Package release builds only, never debug builds. Confirm the build output
  matches what a release build looks like for the toolchain before packaging.
- Ship the whole runtime bundle the binary loads relative to itself.
- Match the target's architecture and C library. A glibc binary will not run
  on a musl system (Alpine); it fails with "No such file or directory" even
  though the file exists.
- Build prebuilt/bundled binaries on the oldest distro release you support.
  glibc is backward compatible but not forward compatible, so binaries built
  on a newer system fail on older ones with "version `GLIBC_x.y' not found".

### Launching
- Users must be able to start the app both from the menu and by typing a
  command. If the app installs outside PATH, the package provides a link or
  wrapper on PATH.
- The desktop file's `Exec=` must resolve after install: an absolute path to a
  file the package ships, or a command the package puts on PATH. GLib/GTK-based
  menus (GNOME and others) refuse to load entries whose `Exec` program can't
  be found, so the app silently disappears from the menu.
- `TryExec=`, if used, follows the same rule; entries whose `TryExec` is
  missing are hidden.
- Every `Icon=` name must match an icon file the package ships, in
  `share/icons/hicolor/<size>/apps/` (PNG) or `.../scalable/apps/` (SVG).
- `Categories=` has exactly one main category; `StartupWMClass=` matches the
  window class the app really sets.
- Desktop files must pass `desktop-file-validate` with no errors.
- Scripts use LF line endings and a shebang for an interpreter that is always
  present or declared as a dependency.

### Dependencies
- Declare every library or runtime the app loads that isn't bundled. Check the
  binary and bundled libraries with `ldd`, and remember plugins that load
  libraries at run time with `dlopen` (e.g. video playback, keyrings, tray
  icons); `ldd` does not show those.
- Bundled private libraries must not be advertised to the system as provided
  by the package, and must not create dependencies on themselves.

### Install and remove scripts
- Install/remove scripts must be idempotent and must handle install, upgrade
  and removal as different cases. Cleanup meant for full removal must never
  run during an upgrade.
- Never touch users' home directories or delete user data from package
  scripts. Uninstalling keeps user data unless the user chooses a purge.
- Never use `sudo`, network access or interactive prompts in build or install
  scripts. Packages are often installed unattended, and output or input there
  is lost.

### Renames and moves
- When a binary, directory, desktop file or icon is renamed or moved, update
  every reference in the same change (symlinks/wrappers, `Exec=`, `TryExec=`,
  `Icon=`, `StartupWMClass=`, manifests, specs, scripts). Search the repo for
  the old name before finishing.

### What agents must not do to make a build pass
- Do not disable checks, linters, tests, checksum verification, signature
  verification or the unpackaged-files check.
- Do not loosen sandbox permissions or confinement to silence an error.
- Do not replace precise file lists with broad wildcards that sweep in files
  that shouldn't ship.
- Do not use a format's "no check" or "force" options to get past an error.
- If one of these really is needed, stop, explain why, and get approval.

### Verification (required before reporting done)
1. Inspect the built package's file list and metadata (commands per format below).
2. Confirm the binary is executable, PATH links resolve inside the package,
   and `Exec=`/`Icon=` point to shipped files.
3. Run the format's linter.
4. Install it in a clean container or VM of the target distro, then launch the
   app from the terminal command and confirm it starts.
5. Upgrade from the previous version, then uninstall, and confirm user data
   survives both.
6. Report any check you couldn't run instead of assuming it passed.

---

## Debian / Ubuntu (.deb)

Commonly skipped or wrong:
- `Package:` contains the version or `-linux`. Version goes in `Version:` only;
  the built file is named `<pkg-name>_<version>_<arch>.deb`.
- No `/usr/bin` link when the app lives in `/opt`.
- Maintainer scripts ignore their first argument. `postrm` can receive
  `remove`, `purge`, `upgrade`, `failed-upgrade`, `abort-install`,
  `abort-upgrade` and `disappear`. Delete config only on `purge`, never on
  `upgrade`, and exit with an error on unknown arguments.
- `Depends:` missing run-time libraries; `Architecture:` not matching the binary.
- Files left owned by the build user instead of root. When every file should be
  root-owned, build with `dpkg-deb --root-owner-group`; don't use it if some
  files need another owner.
- Building with `--nocheck` to skip control-file checks.

Checks:
- `dpkg-deb -c pkg.deb` (file list), `dpkg-deb -f pkg.deb` (metadata)
- `lintian pkg.deb`
- Clean install: `apt install ./pkg.deb` in a Debian/Ubuntu container

---

## Fedora / RHEL / openSUSE (.rpm)

Commonly skipped or wrong:
- `Version:` containing a dash (not allowed). Use tilde for pre-releases so they
  sort before the final release (`1.0.0~rc1`), and caret for snapshots after a
  release (`1.0.0^20261103git1a2b3c4`). Note that RHEL 7's rpm does not
  support caret.
- `Release:` handled wrong. On Fedora, prefer `Release: %autorelease`.
  If set by hand, it is a positive integer with `%{?dist}` appended, starts at
  1, increases for every packaging-only change and resets to 1 on a new version.
- `Epoch:` added casually. Once introduced it can never be removed or lowered;
  add it only to fix version ordering.
- `%files` incomplete. An "Installed (but unpackaged) file(s) found" error means
  a file must be listed or not installed. Never fix it with
  `%define _unpackaged_files_terminate_build 0`.
- Directories the package creates (e.g. `/opt/<app>`) not owned in `%files`
  (list the directory or use `%dir`), leaving orphans on uninstall.
- Bundled libraries generating automatic `Provides`/`Requires`. Stop RPM from
  scanning the private directory, then declare real system dependencies by hand:
  `%global __provides_exclude_from ^/opt/<app>/.*$`
  `%global __requires_exclude_from ^/opt/<app>/.*$`
  Put filter macros before `%description`. If the main binary itself requires
  a bundled library, filter that name with `__requires_exclude` instead of
  excluding the binary from scanning.
- Prebuilt binaries breaking the debuginfo step. Use
  `%global debug_package %{nil}` only for prebuilt binaries, with a comment why.
- Scriptlets ignoring `$1`, which is the number of copies of the package left
  installed after the action. In `%pre`/`%post`, `$1` is 1 on first install and
  greater than 1 on upgrade (test with `-gt 1`, not `== 2`). In
  `%preun`/`%postun`, `$1` is 0 only on full removal, so removal cleanup goes
  inside `if [ "$1" -eq 0 ]`. On upgrade the new package's `%post` runs before
  the old package's `%preun`/`%postun`.
- `BuildRequires:` and `Requires:` mixed up.
- License files not marked `%license`; missing or malformed `%changelog`
  (or not using `%autochangelog` where `%autorelease` is used).
- Desktop file not installed with `desktop-file-install` or not validated in `%check`.

Checks:
- `rpm -qlp pkg.rpm` (files), `rpm -qip pkg.rpm` (info),
  `rpm -qp --requires pkg.rpm` and `rpm -qp --provides pkg.rpm`
  (no bundled private libraries listed)
- `rpmlint` on the `.spec` and the built `.rpm`
- Clean install: `dnf install ./pkg.rpm` in a Fedora container; `rpm -V <name>` after

---

## Alpine Linux (.apk, APKBUILD)

Commonly skipped or wrong:
- Shipping a glibc binary. Alpine uses musl; prebuilt glibc binaries (Flutter,
  Electron, many vendor builds) fail with "No such file or directory". Build
  natively on Alpine. `gcompat` only lets some already-built glibc binaries
  run; it can't be used to build against glibc and doesn't guarantee
  compatibility. If you rely on it, flag it and test thoroughly.
- `pkgver` with dashes or invented suffixes. Use apk syntax: a number followed
  optionally by `_alpha`, `_beta`, `_pre` or `_rc` (sort before the release)
  or `_cvs`, `_svn`, `_git`, `_hg` or `_p` (sort after), each optionally
  followed by a number. Examples: `1.0.0_rc1`, `1.0.0_git20261103`, `1.0.0_p1`.
- Checksums not regenerated after any source change. Run `abuild checksum`
  instead of editing or removing `sha512sums`.
- `options="!check"` added to skip failing tests. Only allowed with a comment
  explaining why the package has no usable tests.
- Installing to the real system instead of `"$pkgdir"` in `package()`.
- Missing `license=` (SPDX identifier) or wrong `arch=`.
- Install scripts written but not listed in `install=`. Valid names are
  `$pkgname.pre-install`, `.post-install`, `.pre-upgrade`, `.post-upgrade`,
  `.pre-deinstall` and `.post-deinstall`. Upgrade and removal are separate
  scripts; never put removal logic in the upgrade path. A failing
  `pre-upgrade` marks the package as broken.
- Docs and dev files not split into `-doc`/`-dev` `subpackages=`.
- Packages left unsigned. Sign with an `abuild-keygen` key and distribute the
  public key. `apk add --allow-untrusted` is for local testing only.

Checks:
- `abuild -r` (builds with dependencies installed in a clean way),
  `apkbuild-lint APKBUILD`
- `tar -tzf pkg.apk` (file list; Alpine still uses the v2 package format even
  with apk-tools v3), `apk verify pkg.apk` (integrity and signature)
- Clean install in an `alpine` container: `apk add ./pkg.apk`

---

## Arch Linux (PKGBUILD / .pkg.tar.zst)

Commonly skipped or wrong:
- `pkgver` containing hyphens, colons, slashes or whitespace (not allowed).
  Replace upstream hyphens with underscores; use `${pkgver//_/-}` where the
  original form is needed in `source=`.
- `pkgrel` not incremented for PKGBUILD changes, or not reset to 1 on a new
  version. `epoch` added casually; only use it when version ordering breaks.
- Checksums not updated after source changes. Use `updpkgsums`; never set
  them to `SKIP` for downloaded release files (only VCS sources use `SKIP`).
- `.SRCINFO` not regenerated for AUR packages:
  `makepkg --printsrcinfo > .SRCINFO` after every PKGBUILD change.
- Installing outside `"$pkgdir"`; using `sudo` in the PKGBUILD.
- `depends` vs `makedepends` mixed up.
- `license=()` not using SPDX identifiers, or license file not installed.
  Licenses like MIT and BSD need their text installed with
  `install -Dm644 LICENSE -t "$pkgdir/usr/share/licenses/$pkgname/"`.
- `.install` scripts ignoring the difference between `post_upgrade` and
  `post_remove`.

Checks:
- `namcap PKGBUILD` and `namcap pkg.pkg.tar.zst`
- `pacman -Qlp pkg.pkg.tar.zst` (files), `pacman -Qip pkg.pkg.tar.zst` (info)
- Clean build: `makepkg -s` in an `archlinux` container (or `extra-x86_64-build`
  from devtools), then `pacman -U pkg.pkg.tar.zst`

---

## Flatpak

Commonly skipped or wrong:
- App ID not used consistently. The manifest `id` and file name, desktop file
  (`<app-id>.desktop`), `Icon=<app-id>`, icon file names and metainfo file
  (`<app-id>.metainfo.xml`, whose `<id>` must match) all use the same ID.
- Missing or invalid AppStream metainfo in `share/metainfo/`. Required for
  software centers and Flathub.
- Installing to `/usr` or `/opt`. Everything goes under `/app`; `command:` must
  match a binary in `/app/bin`.
- Builds that need network access. Builds run offline; declare every
  dependency as a source with a checksum (`sha256`). Toolchains that fetch
  packages at build time (Flutter, npm, Cargo, pip) need generated offline
  sources.
- Over-broad `finish-args` added to silence errors. Avoid `--filesystem=host`
  and `--filesystem=home` (use portals, XDG directories or specific paths,
  read-only with `:ro` where possible), and never use `--socket=session-bus` or
  `--socket=system-bus` unless the app is a development tool. Find the D-Bus
  names actually needed with `flatpak run --log-session-bus <app-id>` and add
  only those with `--talk-name`. Comment each permission.
- Wayland and X11 sockets set up wrong. Use `--socket=wayland` plus
  `--socket=fallback-x11` rather than plain `--socket=x11`.
- EOL runtime versions; runtime and SDK versions not matching.

Checks:
- `flatpak-builder --user --install --force-clean build <manifest>`
- `flatpak run --command=flatpak-builder-lint org.flatpak.Builder manifest <manifest>`
- Build with `--repo=repo`, then
  `flatpak run --command=flatpak-builder-lint org.flatpak.Builder repo repo`
- `appstreamcli validate <metainfo>`, `desktop-file-validate <desktop file>`
- `flatpak run <app-id>`

---

## Snap

Commonly skipped or wrong:
- `confinement: classic` or `devmode` used to get around errors. Use `strict`
  and add the specific `plugs` the app needs. Classic needs Snap Store
  approval, and devmode snaps can only be released to beta and edge.
- `grade: devel` left in a release. Devel-grade snaps can't be released to the
  stable or candidate channels; use `grade: stable` for releases.
- `apps.<name>.command` not matching the binary's path, relative to the snap root.
- GUI apps without the matching extension, causing missing themes, fonts or
  libraries. For Flutter on current bases (core22, core24), use the `flutter`
  plugin with `extensions: [gnome]`; the older `flutter-*` extensions only
  support core18.
- Desktop file and icon missing or misnamed. Put them in `snap/gui/`, named
  after the app (`snap/gui/<app>.desktop`, `snap/gui/<app>.png`), and use
  `Icon=${SNAP}/meta/gui/<app>.png` in the desktop file.
- `base:` not set, or set to an EOL core.
- App writing to its install directory; it is read-only. Use `$SNAP_USER_DATA`
  or `$SNAP_USER_COMMON`.

Checks:
- `snapcraft` (build), `unsquashfs -l pkg.snap` (files)
- `snap install --dangerous ./pkg.snap`, then `snap run <name>`
- `snap connections <name>` to confirm the plugs are connected

---

## AppImage

Commonly skipped or wrong:
- AppDir root missing the required pieces: an executable `AppRun`, one
  `.desktop` file and its icon (matching the `Icon=` name, without extension).
- `Exec=` in the AppDir desktop file using an absolute host path instead of
  the binary name.
- Bundling glibc, `libGL`/graphics driver libraries or other libraries that
  must come from the host. Follow the AppImage excludelist: bundle app
  libraries, leave system-level ones to the host.
- Building on a new or rolling distro, so it fails on older ones. Build on the
  oldest still-supported LTS release you target, and test on the systems you
  claim to support.
- Lost executable bit on the final file or on `AppRun`.
- Using the old runtime, which needs FUSE 2 (`libfuse2`, renamed `libfuse2t64`
  on Ubuntu 24.04+) on the host. Prefer the current static runtime, which
  uses FUSE 3 and doesn't need `libfuse2`. Either way, document
  `--appimage-extract-and-run` as the fallback when FUSE isn't available.
- AppImage writing settings next to itself; use standard XDG directories.

Checks:
- `./App.AppImage --appimage-extract` and inspect `squashfs-root/`
- `desktop-file-validate squashfs-root/*.desktop`
- Run on a clean VM of the oldest supported distro. Most Docker containers
  don't allow FUSE, so in containers use `--appimage-extract-and-run`
  (or `APPIMAGE_EXTRACT_AND_RUN=1`).