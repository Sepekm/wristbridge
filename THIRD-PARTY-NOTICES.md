# Third-party notices

Wristbridge itself is MIT licensed; see [LICENSE](LICENSE).

The built APK also contains compiled code from the libraries below. All of them
are licensed under the **Apache License, Version 2.0**, whose section 4(a)
requires that a copy of the licence accompanies redistribution. The full text is
at <https://www.apache.org/licenses/LICENSE-2.0>.

No modifications have been made to any of them, and none of the artifacts used
here ships a `NOTICE` file whose contents would need reproducing under section
4(d).

## AndroidX and Jetpack Compose

Copyright The Android Open Source Project. Licensed under Apache-2.0.

```
androidx.activity:activity, activity-compose, activity-ktx
androidx.annotation:annotation, annotation-experimental, annotation-jvm
androidx.arch.core:core-common, core-runtime
androidx.autofill:autofill
androidx.collection:collection, collection-jvm, collection-ktx
androidx.compose.animation:animation, animation-core (+ -android variants)
androidx.compose.foundation:foundation, foundation-layout (+ -android variants)
androidx.compose.material3:material3 (+ -android)
androidx.compose.material:material-icons-core, material-ripple (+ -android)
androidx.compose.runtime:runtime, runtime-saveable (+ -android variants)
androidx.compose.ui:ui, ui-geometry, ui-graphics, ui-text, ui-unit, ui-util,
                     ui-tooling-preview (+ -android variants)
androidx.concurrent:concurrent-futures
androidx.core:core, core-ktx
androidx.customview:customview-poolingcontainer
androidx.emoji2:emoji2
androidx.graphics:graphics-path
androidx.interpolator:interpolator
androidx.lifecycle:lifecycle-common, -common-java8, -common-jvm,
                   -livedata-core, -process, -runtime, -runtime-compose,
                   -runtime-ktx, -service, -viewmodel, -viewmodel-ktx,
                   -viewmodel-savedstate (+ -android variants)
androidx.profileinstaller:profileinstaller
androidx.savedstate:savedstate, savedstate-ktx
androidx.startup:startup-runtime
androidx.tracing:tracing
androidx.versionedparcelable:versionedparcelable
```

## Kotlin

Copyright JetBrains s.r.o. and Kotlin Programming Language contributors.
Licensed under Apache-2.0.

```
org.jetbrains.kotlin:kotlin-stdlib, kotlin-stdlib-common
org.jetbrains.kotlinx:kotlinx-coroutines-android, -core, -core-jvm
org.jetbrains:annotations
```

## Guava ListenableFuture

Copyright The Guava Authors. Licensed under Apache-2.0.

```
com.google.guava:listenablefuture
```

## Test-only

JUnit 4 (`junit:junit`) is used by the unit tests and is licensed under the
Eclipse Public License 1.0. It is not part of the shipped APK.

## Regenerating this list

```bash
./gradlew :app:dependencies --configuration releaseRuntimeClasspath
```
