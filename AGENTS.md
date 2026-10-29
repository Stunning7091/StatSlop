# AGENTS.md

Instructions for AI coding agents working in this repository.

## Priority order

When decisions conflict, resolve them in this order:

1. **Functionality** — the app must do what it is supposed to do. Correct behavior is the baseline; nothing below may break it.
2. **Clean** — readable, well-named, self-documenting code that another developer (or you, in six months) understands without a walkthrough.
3. **Lean** — no code, dependency, abstraction, or feature that does not earn its place.
4. **Efficient** — sensible use of CPU, memory, network, and battery; fast startup and responsive UI.

If a tradeoff is unavoidable, the lower-numbered priority wins. Never sacrifice correctness for elegance, or elegance for micro-optimization.

## This project: StatSlop

StatSlop is a small Android battery and system monitor. It was built to replace
the battery and resource stats that HyperOS loses when its Security app
(`com.miui.securitycenter`) is removed, but it runs on any Android 12+ phone.

- Kotlin + Jetpack Compose (Material 3), one Gradle module `app`, package `app.statslop`.
- `minSdk 31`, `targetSdk`/`compileSdk 35`. AGP 8.7, Kotlin 2.0, Gradle wrapper 8.11.
- No backend, no accounts, no network. Everything stays on the phone.

### Where things are

| File | What it does |
|---|---|
| `app/src/main/java/app/statslop/BatteryReader.kt` | Live battery snapshot from the sticky `ACTION_BATTERY_CHANGED` intent and `BatteryManager` |
| `SystemReader.kt` | RAM, storage, per-core CPU clocks (sysfs), thermal status, uptime vs. deep sleep |
| `History.kt` | SQLite history (`SQLiteOpenHelper`, 30 days), `SampleJob` (15-minute `JobService`), drain stats |
| `UsageReader.kt` | Per-app screen time, screen-on time and unlocks via `UsageStatsManager` |
| `MainActivity.kt` | Compose root, three tabs, 2-second refresh of the open tab only |
| `ui/` | One file per tab plus `Components.kt` (cards, meters, `TimeChart`, formatters) |
| `install.sh` | Builds if needed, installs over adb, grants usage access |
| `release.sh` | Builds the signed APK and publishes it as a GitHub release |

### Build and run

```sh
./install.sh --build                                  # build release, install on the USB-connected phone, open it
JAVA_HOME=/path/to/jdk17+ ./gradlew assembleRelease   # build only
```

- Gradle needs a full JDK (with `javac`), not just a JRE. `install.sh` uses `~/Android/jdk` if it exists.
- `install.sh` finds adb via the `ADB` environment variable, falling back to the maintainer's local path.
- The release build is minified with R8. It is signed with the release key from `~/.config/statslop/keystore.properties` (never committed), or with the debug key on machines without it.

### Releasing

1. Set `appVersion` in `app/build.gradle.kts` to `Month.Year.Release`. Release counts the releases within that month and starts at 1, e.g. the second release of November 2026 is `11.2026.2`. `versionCode` is derived from it.
2. Commit, then run `./release.sh "What changed"`. It refuses to run with uncommitted changes, an existing release of that version, or an APK not signed with the release key.
3. Never publish a debug-signed APK, and never replace the release key. Either one stops existing installs from updating, and users would have to uninstall and lose their history.

### Decisions already made

Do not reverse these without asking the maintainer:

- **No `INTERNET` permission.** Nothing may leave the phone.
- **No per-app battery drain.** It would need `BATTERY_STATS` granted over adb plus a relaxed hidden-API policy, which can break with any OS update. Per-app screen time is the deliberate substitute.
- **No WorkManager, Room, DI framework or icon library.** Background sampling is the plain `SampleJob`, and storage is `SQLiteOpenHelper`. They were removed on purpose: 9 fewer manifest components, 3 fewer permissions, about 200 KB smaller APK.
- **No Doze or battery-optimisation exemption.** The sampler must not wake the phone from deep sleep. Drain stats and charts accept gaps of up to 12 h because of this.
- **EmojiCompat startup initializer is removed** in the manifest; the app shows no emoji.
- **History is self-recorded.** Android gives normal apps no access to system battery history, so charts start at install time.
- **Charts:** one series and one y-axis per chart. The series is blue (`#2A78D6` light, `#3987E5` dark), and plugged-in periods are a neutral band.

### Platform quirks

- `BATTERY_PROPERTY_CURRENT_NOW` should be µA, but some kernels report mA. `readCurrentMa` guesses the unit, and the UI takes the direction from the charging status. On the test phone it is µA, and negative means discharging.
- `PowerManager.getThermalHeadroom` returns NaN when polled more than about once a second, so `SystemReader` caches it for 1.5 s.
- CPU load % is not readable by apps (`/proc/stat` is blocked since Android 8), so clocks are shown instead.
- Design capacity comes from the hidden `PowerProfile` class via reflection and is `null` on most devices. The UI hides those rows when it is missing.
- Force-stopping the app cancels its jobs. `SampleJob.schedule()` in `MainActivity.onCreate` puts the job back.
- Not yet verified: whether HyperOS runs the background job reliably when the Security app is missing.

### Verifying a change

There is no automated test suite yet. Until there is, "Definition of done" item 1 means verifying on a real device over adb:

```sh
./install.sh --build
adb logcat -d -b crash | grep app.statslop               # must be empty
adb exec-out screencap -p > shot.png                     # look at every tab you touched
adb shell cmd jobscheduler run -f app.statslop 1         # run the sample job now
adb shell dumpsys meminfo app.statslop | grep "TOTAL PSS"
```

- **Leak check:** switch tabs about 60 times, then compare `TOTAL PSS`. It must level off, not keep growing.
- **Before/after performance:** run `adb shell cmd package compile -m speed -f app.statslop` on both builds first, and alternate the runs. Without that, the numbers aren't comparable.
- **Baseline** (test phone, Android 17, 2026-10-29): cold start ~520 ms, ~136 MB PSS open, ~85 MB in the background, 0.5–1.5 % of one core while open, APK 1.70 MB. If a change moves these numbers, update "Resource use" in `README.md`.

### Repository hygiene

- The repo is public. Never commit personal data: home paths, device serials, emails, IP addresses or keys. `local.properties` is gitignored for this reason.
- Commit with the repository-local git identity that is already configured.

## Core philosophy: simplicity is the goal, not the starting point

> "An idiot admires complexity, a genius admires simplicity." — Terry A. Davis

- Write the simplest solution that fully satisfies the requirement — then stop.
- Every line of code is a liability. If you can delete it and nothing breaks, delete it.
- If a function, config, or abstraction exists "just in case," remove it. Add things when the need is real, not when it is imaginable.
- The whole module should be understandable by one person in one sitting. If it isn't, split it or simplify it.

## Lean rules

- **Dependencies:** every new dependency must be justified in the PR/commit message. One well-understood function does not justify a package — prefer a small local implementation over a heavyweight library.
- **No premature abstraction:** no interfaces, generic layers, factories, or plugin systems until there is a second real use case. YAGNI applies always.
- **No dead surface:** remove unused exports, commented-out code, unused imports, disabled feature flags, and placeholder routes immediately — do not leave them "for later."
- **Features:** implement exactly what is asked. Do not add settings, telemetry, dark-mode toggles, or admin panels nobody requested.
- **Files/config:** keep the repo tree flat where possible. No config for things that have sensible defaults.

## Clean rules

- **Names:** descriptive and boring. `userLoginAttempts`, not `ula` and not `IManageUserAuthenticationService`. If you need a comment to explain the name, rename it.
- **Functions:** one job each. If you need "and" to describe it, split it. Short functions; early returns over nested conditionals.
- **Comments:** explain why, never what. Delete comments that restate the code.
- **State:** minimize mutable and global state. Make data flow obvious; no hidden side effects.
- **Errors:** handle them at the level where recovery is possible. No empty catch blocks, no swallowing failures silently.
- **Consistency:** follow the existing conventions of this codebase over your personal preferences. Match file structure, naming, and formatting of neighboring code.

## Efficient rules

- Default to the straightforward implementation; optimize only with a measured reason (profiler, benchmark, or user-visible latency).
- Choose appropriate data structures upfront (hash map for lookups, not array scan) — that is design, not premature optimization.
- I/O is the usual bottleneck: batch requests, avoid redundant network calls, cache when the same data is fetched repeatedly.
- Startup and critical paths stay lazy: load what's needed now, defer the rest.
- On the UI: don't block the main thread; don't leak listeners, subscriptions, or timers.
- Mobile/web: treat battery and bandwidth as user resources, not as free.

## Resource and memory correctness

Every change must be checked for leaks and resource errors, not just for whether it compiles:

- **Memory leaks:** every allocation has a matching release. In managed languages, this means no accidental retention: remove references, clear caches, and null out large buffers when done.
- **Listeners, subscriptions, timers, observers:** every `on`/`addEventListener`/`subscribe`/`setInterval`/`requestAnimationFrame` has a matching cleanup (`off`, `removeEventListener`, `unsubscribe`, `clearInterval`, `cancelAnimationFrame`). Verify this on every component teardown, route change, and error path.
- **Handles and streams:** files, sockets, database connections, and streams are always closed — including on exceptions (use `finally`, `defer`, `with`, or RAII as the language provides).
- **Error paths leak too:** a leak that only happens when an error is thrown is still a leak. Test the failure branch, not only the happy path.
- **Run the actual checkers** available for the stack before finishing: linters, leak detectors, sanitizers (e.g. Valgrind, AddressSanitizer/LeakSanitizer, heap profilers), or devtools memory profiling. Do not claim "no leaks" from reading the code alone when a tool can verify it.

## Definition of done

Before declaring a task complete, verify all of the following:

1. All existing and new tests pass; new behavior has tests.
2. You actually ran the feature and confirmed it works — execute the app, click the flow, call the endpoint, or run the script that exercises the change. "It should work" is not done; "I ran it and it works" is.
3. Memory and resource check: no leaks, dangling listeners, unclosed handles, or unbounded growth — verified with the tooling named in the section above where available.
4. The build compiles/Type-checks with zero new warnings.
5. `git diff` contains nothing unrelated to the task — no reformatting of untouched files, no drive-by refactors.
6. No dependency was added without justification; none removed silently.
7. A new team member could read the changed code and explain what it does without help.
8. You have looked at every line you wrote once more and deleted whatever you can.

## When requirements are unclear

Implement the smallest interpretation that satisfies the literal request, make assumptions explicit in code comments or commit message, and leave the extension points out. Do not guess at elaborate requirements.
