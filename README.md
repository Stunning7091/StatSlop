# StatSlop

Battery and system monitor for the POCO F9 Ultra, replacing the stats that went
away with `com.miui.securitycenter`. Personal sideloaded app, no network access.

## Tabs

- **Battery**: level, charge/discharge current (mA, W), time to full or estimated
  time left, temperature, voltage, health, charge cycles, estimated capacity.
  Level and temperature charts over 24 h / 3 / 7 days, average drain %/h and
  drop since unplugged.
- **System**: RAM and storage, per-core CPU clocks, thermal throttling status,
  uptime vs. deep sleep, device info.
- **Apps**: screen-on time, unlocks and per-app screen time (today / 7 days).

## Limits (Android, not this app)

- History only exists from install onward: StatSlop records its own sample every
  15 minutes (kept 30 days). Android does not let normal apps read the system's.
- No per-app *battery* drain: that needs the privileged `BATTERY_STATS`
  permission. Screen time per app is the substitute.
- No CPU load %: `/proc/stat` is blocked for apps since Android 8; clocks are shown instead.

## Build & install

```sh
./install.sh          # installs the existing APK (builds it first if missing)
./install.sh --build  # rebuild, then install
```

The script also grants usage access and exempts StatSlop from battery optimisation
via adb, so nothing has to be found in HyperOS settings.

Toolchain (all under `~/Android`, nothing installed system-wide): SDK in `Sdk/`,
Temurin JDK 21 in `jdk/` (picked up by `install.sh`), Gradle via the wrapper.
To build by hand: `JAVA_HOME=~/Android/jdk ./gradlew assembleRelease`.
