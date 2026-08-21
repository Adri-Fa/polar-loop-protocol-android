# polar-loop-protocol-android

Reverse-engineered BLE/PMD/PFTP protocol layer for the Polar Loop Gen 2:
decoders for its historical data files (activity, sleep, daily summary,
nightly recharge, skin temperature, PPI/HR autosamples), PMD live-streaming
frame decoding, PFTP command construction, and a Health Connect mapping
layer. Extracted from [Polar Loop Debugger](../debugging-polarloop), a
standalone Android app that uses this library directly.

No UI/Activity/Compose dependencies -- pure Kotlin + Android BLE/Health
Connect APIs, usable from any Android app.

**Read-only by design**: no PUT/REMOVE PFTP command builder exists anywhere
in this codebase, and a regression test (`PftpCommandsTest`) enforces that no
`build*` function can ever construct one. This library can read a Polar Loop
Gen 2's data; it cannot write to it.

## Install

Via [JitPack](https://jitpack.io/#Adri-Fa/polar-loop-protocol-android):

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.Adri-Fa:polar-loop-protocol-android:v1.0.2")
}
```

`v1.0.2` is the latest released tag; check the
[JitPack build log](https://jitpack.io/#Adri-Fa/polar-loop-protocol-android) if you pin a
different one to confirm it actually built.

## Testing

Test fixtures are synthetic but structurally valid -- byte-accurate to each
decoder's real wire format, but the values (dates, device ids, health
numbers) are not from any real device or person. Run the suite with:

```
./gradlew test
```
