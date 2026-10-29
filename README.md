# StatSlop

Battery and system monitor for Android 12+. It replaces the battery and resource
stats that HyperOS loses when its Security app (`com.miui.securitycenter`) is
removed. No network access.

## Tabs

- **Battery**: level, charge/discharge current (mA, W), time to full or estimated
  time left, temperature, voltage, health, charge cycles, estimated capacity.
  Level and temperature charts over 24 h / 3 / 7 days, average drain %/h and
  drop since unplugged.
- **System**: RAM and storage, per-core CPU clocks, thermal throttling status,
  uptime vs. deep sleep, device info.
- **Apps**: screen-on time, unlocks, per-app screen time and apps that kept a
  background (foreground) service running, the usual deep-sleep blockers (today / 7 days).

## Limits (Android, not this app)

- History only exists from install onward: StatSlop records its own sample every
  15 minutes (kept 30 days). Android does not let normal apps read the system's.
  In deep sleep Android delays samples to save power, so overnight gaps are normal.
- No per-app *battery* drain: that needs the privileged `BATTERY_STATS`
  permission. Screen time per app is the substitute.
- No CPU load %: `/proc/stat` is blocked for apps since Android 8; clocks are shown instead.

## Install

1. Download the latest `StatSlop-<version>.apk` from
   [Releases](https://github.com/Stunning7091/StatSlop/releases) on your phone and open it.
2. Allow installing apps from that source when asked.
3. For per-app screen time: open the Apps tab and tap **Allow usage access**.

Versions are named `Month.Year.Release`, e.g. `10.2026.1` is the first release
of October 2026.

## Build & install from source

```sh
./install.sh          # installs the existing APK (builds it first if missing)
./install.sh --build  # rebuild, then install
```

The script also grants usage access via adb, so nothing has to be found in
HyperOS settings.

Toolchain (all under `~/Android`, nothing installed system-wide): SDK in `Sdk/`,
Temurin JDK 21 in `jdk/` (picked up by `install.sh`), Gradle via the wrapper.
To build by hand: `JAVA_HOME=~/Android/jdk ./gradlew assembleRelease`.

## Resource use

Measured on a test phone (Android 17), release build compiled ahead of time:

- CPU: 0.5–1.5 % of one core while open, 0 % in the background. Only the open tab
  refreshes (every 2 s); the Apps tab does not refresh at all.
- Memory: about 136 MB while open, about 85 MB in the background. It stays flat
  after 180 tab switches, so there is no leak.
- Cold start: about 520 ms. APK: 1.7 MB.
- Background: one short sample job every 15 minutes via the platform JobScheduler,
  which Android batches during deep sleep. No services running, no wake locks,
  no internet permission. The only extra permission is `RECEIVE_BOOT_COMPLETED`,
  which keeps the job scheduled after a reboot.

## License

MIT, see [LICENSE](LICENSE). The tab icons are Material Icons (Apache 2.0).
