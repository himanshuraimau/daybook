# Daybook mobile

The native Kotlin/XML Views app uses Android's built-in UI components and no third-party app libraries. It stores account-separated expenses and pending operations in app-private SQLite, encrypts the session token with Android Keystore, and schedules network sync and daily reminders with Android system services.

Open `mobile` in Android Studio, let Gradle sync, then run `app` on an Android device or emulator. For a build with account access, set `DAYBOOK_API_BASE_URL=https://your-api-origin` in the environment or in an untracked `~/.gradle/gradle.properties`, then run `./gradlew assembleDebug`. The value must be an HTTPS origin. A build without it shows a setup message on the account screen; no URL or credential is committed to Git. Sign-up and login need no email service. Run `./gradlew testDebugUnitTest` for local Robolectric checks of account UI, durable storage, account isolation, migration, and sync retries; these checks do not cover Keystore or real-device scheduling.

The app ID is `com.daybook.mobile`. It targets Android 16 (API 36) and supports Android 8.0 (API 26) and newer. Reminders use inexact local alarms and may arrive around the chosen time. Android 13+ asks for notification permission when reminders are enabled.

## Fonts and third-party assets

The UI uses **Nunito** (variable font, SIL Open Font License 1.1, © The Nunito Project Authors), bundled as `app/src/main/res/font/nunito_variable.ttf`. The icons are original vector drawables.

## Troubleshooting the emulator on very new Linux

On a machine with glibc 2.34 or newer (seen on Ubuntu with glibc 2.43) the Android emulator can crash at startup: its bundled `libandroid-webrtc.so` exports a `pthread_create` wrapper that calls itself forever. A small preload library that forwards to the real function fixes it. Build it once outside the repository:

```c
// pthread_shim.c
#define _GNU_SOURCE
#include <dlfcn.h>
#include <pthread.h>
int pthread_create(pthread_t *t, const pthread_attr_t *a, void *(*f)(void *), void *arg) {
    static int (*real)(pthread_t *, const pthread_attr_t *, void *(*)(void *), void *);
    if (!real) real = dlvsym(RTLD_NEXT, "pthread_create", "GLIBC_2.34");
    return real(t, a, f, arg);
}
```

```bash
gcc -shared -fPIC -O2 -o pthread_shim.so pthread_shim.c -ldl
LD_PRELOAD=$PWD/pthread_shim.so ~/Android/Sdk/emulator/emulator -avd <name> -gpu swiftshader_indirect
```

The emulator also needs hardware virtualisation: `/dev/kvm` must exist (enable VT-x in the BIOS, `sudo modprobe kvm_intel`, and be in the `kvm` group or have an ACL on `/dev/kvm`). To run Android Studio's emulator, start Studio with the same `LD_PRELOAD`.
