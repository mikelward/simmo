# Simmo

Simmo picks the right SIM — or calling app — for every outgoing call on Android,
using per-country rules: *Australian numbers on Telstra, US numbers on T-Mobile,
everything else asks.* It can hand calls off to apps like Google Voice, and helps you
re-enable a disabled eSIM profile when a rule needs it.

- **What it does and why**: [SPEC.md](SPEC.md)
- **Plan**: [TODO.md](TODO.md)
- **Engineering conventions**: [AGENTS.md](AGENTS.md)

## Building

```sh
./gradlew assembleDebug   # build debug APK
./gradlew test            # unit tests
./gradlew lint            # lint
```

Requires JDK 17+ and an Android SDK (`ANDROID_HOME`). Optional per-machine
configuration — enabling Firebase crash reporting and analytics — is in
[SETUP.md](SETUP.md).
