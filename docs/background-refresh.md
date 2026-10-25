# Ordinary background usage refresh

After explicit sign-in or a manual read of restored credentials, the app enrolls
ordinary WorkManager periodic work for that **continuing local credential lifetime**.
The target interval and initial delay are **15 minutes**, not an execution deadline.
Connected network and battery-not-low constraints cooperate with Android; charging,
device idle, unmetered network and battery-optimization exemptions are not required.
Doze, batching, restricted-app state, vendor policy and scheduler availability can
all delay or prevent a particular opportunity. The app does not keep a process,
foreground service, polling timer or exact refresh alarm alive to enforce punctuality.

## Ownership and admission

- One unique KEEP request per opaque lifetime; repeated explicit activation retains
  the request ID/period rather than postponing it or making duplicates.
- Work input contains only that canonical local UUID. It contains no token, account
  name, provider identity, raw response or credential-slot selector. WorkManager's
  no-backup database is the only enrollment persistence; no parallel registry is added.
- A cold worker obtains the existing `NativeConnection.get(applicationContext)`
  singleton and waits for protected restoration. Only an exact enrolled continuing
  lifetime can admit I/O. Ordinary construction/launch/restoration stays dormant.
- Removal, replacement cleanup and terminal reauthorization cancel enrolled work.
  A late stale worker can cancel only its own unique name, never a successor.
  Logout fences old requests/history immediately even before asynchronous scheduler
  cancellation settles. Rotation keeps the same lifetime. Activity STOP, Cancel and
  clean process-owner shutdown do not pretend to remove the account/enrollment.
- Foreground/manual/background overlap joins the same owner flight and recorder
  ticket. No second transport, session, history store or visible observer exists.
  Worker stop/cancellation releases only its interest; another consumer and accepted
  token rotation/durable storage settlement retain their existing authority.
- If both latest endpoints succeeded less than 15 monotonic minutes ago and no
  cycle is running, periodic-only admission skips redundant requests. This cache and
  Retry-After boundary are process-local, not a cross-reboot clock inference.
- Expected errors/rate limits/storage failures finish the current opportunity and
  wait for the next periodic opportunity. There is no WorkManager `Result.retry()`
  loop. Existing A2 bounded endpoint attempts/deadlines remain in force. A scheduler
  `Result.success()` is not an assertion that endpoints or history succeeded; issue
  194's independent endpoint outcomes and durable recording receipt remain authoritative.

## Process loss, reboot and force-stop

WorkManager persists ordinary work and uses Android JobScheduler on supported APIs.
A killed process can be recreated for eligible enrolled work, and boot rescheduling
can restore eligible work after reboot/unlock. Neither is an exact start guarantee.
Protected credential/history restoration still gates every cold admission; missing,
corrupt, key-lost, deleted or replaced lifetimes cannot authorize network access.

Android force-stop suppresses background execution until the user permits the app
to run again (normally by opening it). This app does not bypass force-stop or vendor
restrictions. The pinned library detects/reconciles force-stop when initialized.
On API 26–29 its `ForceStopRunnable` uses a long-horizon **exact-alarm sentinel for
bookkeeping**; API 30+ uses historical process exit reasons. That sentinel is not
periodic refresh scheduling or enforcement, and no exact-alarm permission is added.
A literal claim that no dependency ever calls an exact-alarm API would be false.

## Released dependency and manifest boundary

The pinned library is [WorkManager 2.12.0, stable September 23, 2026](https://developer.android.com/jetpack/androidx/releases/work#2.12.0),
not a snapshot or upstream-main build. [Google Maven metadata](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/maven-metadata.xml)
and the [published module](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.module)
identify the release. The [runtime AAR](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0.aar)
and [versioned source archive](https://dl.google.com/dl/android/maven2/androidx/work/work-runtime/2.12.0/work-runtime-2.12.0-sources.jar)
are authoritative for this contract. Runtime contains CoroutineWorker already;
no empty `work-runtime-ktx`, RxJava, multiprocess or Room compiler dependency is added.
`work-testing:2.12.0` belongs only to the instrumentation classpath. The OS-dispatch
fixture also declares the already selected `room-runtime:2.7.0` on that classpath:
it accesses WorkDatabase, whose RoomDatabase superclass is not exported by
WorkManager's API dependency variant. This does not add a production dependency.
The released AAR requires min SDK 24, compile SDK 35 and AGP 8.6.0; this app keeps
its existing min 26 / compile 37 / AGP 9.4.1 toolchain.

Source requests exactly INTERNET, ACCESS_NETWORK_STATE, RECEIVE_BOOT_COMPLETED and
WAKE_LOCK; effective/APK/installed sets additionally retain the existing AndroidX
signature-only dynamic-receiver permission. Exactly two main-manifest removal markers
remove upstream FOREGROUND_SERVICE and SystemForegroundService. Other destructive
or protected merge directives and app variant overlays remain forbidden.

The effective component allowlist includes the following released library components;
all run in the default application process, with no external/isolated service:

| Component | Security / purpose |
| --- | --- |
| `androidx.work.impl.background.systemjob.SystemJobService` | Exported only under `android.permission.BIND_JOB_SERVICE`; not direct-boot-aware; ordinary OS job entry. Enabled by the pinned true resource and scheduling. |
| `androidx.work.impl.utils.ForceStopRunnable$BroadcastReceiver` | Nonexported, not direct-boot-aware; force-stop sentinel bookkeeping. |
| `androidx.work.impl.background.systemalarm.RescheduleReceiver` | Nonexported, not direct-boot-aware; BOOT_COMPLETED only. Manifest-disabled by default, dynamically enabled for unfinished work. |
| `androidx.work.impl.diagnostics.DiagnosticsReceiver` | Exported only under `android.permission.DUMP`; not direct-boot-aware; diagnostics action only. |
| `androidx.room.MultiInstanceInvalidationService` | Room 2.7.0 transitive service; nonexported and direct-boot-aware, default process. WorkManager does not enable multi-instance invalidation; this dormant component is retained rather than adding another removal exception. |
| `androidx.startup.InitializationProvider` | Nonexported `${applicationId}.androidx-startup`; exactly WorkManagerInitializer plus existing ProcessLifecycle/EmojiCompat/ProfileInstaller and OkHttp 5.5.0 `okhttp3.internal.platform.PlatformInitializer` metadata, each valued `androidx.startup`. Initializing the scheduler is not activating an account. |
| Existing `androidx.profileinstaller.ProfileInstallReceiver` | Exported only under DUMP, not direct-boot-aware; four existing ProfileInstaller actions. |

BIND_JOB_SERVICE and DUMP are component caller protections, **not app requested
permissions**. SystemForegroundService is absent. There is no separate process,
foreground-service type, direct-boot worker, exact refresh alarm or battery exemption.
Cleartext-off, backup/extraction exclusions, protected credentials, identity isolation
and source/effective/APK/installed permission gates remain enforced. Native installed
checks additionally inspect effective component flags/processes/caller permissions.

Strict dependency verification retains all reviewed prior hashes and adds only the
resolved scheduler graph delta in `gradle/verification-metadata.xml`, including
published variant filename-to-URL mappings; the integrity contract binds its semantic
digest. Runtime attribution includes the actual selected WorkManager, Room,
framework-SQLite, Lifecycle and futures/tracing adapters, with full source-backed
headers/licenses in the candidate catalog/corpus. A fresh release-runtime inventory
and actual APK, not this dependency list, determine the complete shipping SBOM/notices.
No standalone SQLite native engine or test-only library is asserted to ship.

## Evidence boundaries

- JVM admission tests use actual owner flights, synthetic transport/storage and
  virtual time. They are not Android scheduler evidence.
- Native TestDriver controls network/battery and initial/period-delay eligibility;
  it is neither real OS timing nor phone battery evidence.
- The minimized native OS case runs the actual registered ordinary JobScheduler job
  through SystemJobService/WorkManager/CoroutineWorker, with real isolated Keystore
  and SQLite. For bounded hosted execution it disables only that work ID's Greedy
  delayed tracker, ages its test enqueue timestamp, and uses a forced OS dispatch.
  It verifies held owner admission while the Activity is CREATED, RUNNING state,
  endpoint counts, durable completion and fresh-holder local-history reread. It
  does not directly call `doWork()` as purported OS execution, change the configured
  15-minute interval/constraints, or establish natural 15-minute cadence.
- Direct worker-builder cases verify result mapping, cold exact-lifetime rejection,
  cancellation, removal/reauth and bounded errors; they are labeled separately.
- These authored contracts require exact-head hosted compilation, lint, JVM/native
  execution, unchanged whole-handwritten >=90% INSTRUCTION union and independent
  review. Source/quick tooling/compilation alone establish none of those results.
- Parent issue 193 retains a **physical-phone minimized/background timing and battery
  check**, including OS/vendor delay behavior. Neither emulator success nor choosing
  WorkManager waives that acceptance. Fresh-holder restoration is not literal
  process-death/reboot/force-stop execution evidence.
