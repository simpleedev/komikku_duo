# WebDAV synchronization: behavior, safety, and validation

This fix is on `fix/webdav-change-detection`. The shared file remains the existing,
uncompressed `backup.proto`. No remote data reset, database migration, or new
dependency is required.

## Confirmed causes in the checked-out source

Startup (`App.onCreate`), foreground resume (`App.onStart`), chapter events,
settings/library manual actions, periodic work, and sync-before-library-update
all reach `SyncDataJob` / `SyncManager`.

Previously, `WebDavSyncService.pullSyncData` stripped the quotes from ETags before
reusing them in conditional requests. A server may reject or disregard those
invalid validators and return the complete protobuf again. Every successful read
was merged and uploaded, including identical contents. A 304, a 404, and even a
protobuf decode failure could all become the same `(null, etag)` path, which
uploaded the local backup. In particular, a valid 304 did not mean “do nothing”.
Upload precondition failures were reported but not thrown, so callers could go
on restoring and acknowledging a failed operation.

The manager's first no-change test used object identity (`===`), which a newly
constructed merged backup cannot satisfy. Its later manga filter compared manga
versions, chapter versions, and category order numbers. `MangaRestorer` compared
chapter contents while deliberately ignoring versions, and skipped matching
contents without aligning their versions. Thus a remote chapter with identical
reading state and a different version remained different forever. Category order
numbers can also differ on devices because restoration resolves categories by
name and retains existing local category order. These mismatches can enqueue a
restore on every launch even when the remote user data has not changed.

The exact banner trigger was `BackupRestoreJob.doWork` calling
`BackupRestoreStatus.start`, observed by `MainActivity` and `AppStateBanners`.
Sync launched this separate worker and recorded its timestamp before restoration
had completed. Another sync could therefore run against an incomplete restore.
The banner reflected an actual restore worker; hiding it alone would not fix this.

This was not an unconditional replacement of the entire SQLite database. It was
a full selected logical backup transfer/merge followed by a conditional record
restore using the general backup restorer. A valid 304 avoided the download body
but still led to a PUT. The persistent version/category mismatch explains how
that conditional record restore can repeat forever. Device logs are needed to
distinguish which mismatch occurred on a particular installation.

The preference problems described in
[Komikku issue #1948](https://github.com/komikku-app/komikku/issues/1948)
are also present here: duplicate application preference keys were dropped, source
preference conflicts always chose remote, and restore settings were hard-coded
rather than derived from sync options. The new WebDAV path bypasses those merge
functions. The corresponding [TachiyomiSY manager](https://github.com/jobobby04/TachiyomiSY/blob/master/app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncManager.kt)
contains the inherited asynchronous restore architecture; changing providers
alone does not address it.

## Correction for the first fix build: restore stuck at 0%

The first Duo fix exposed a bug in the existing suspending transaction bridge.
The manager opens an outer transaction, `restoreSync` nests another, and the
individual restorers/repositories enter further transactions. On a nested call,
`TransactionContext.withTransaction` passed only the transaction dispatcher to
`runBlocking`, dropping the inherited transaction element and parent Job.
Subsequent database calls could acquire another executor thread and wait on the
write lock held by their own outer transaction. The restore banner had already
started, so it stayed at 0%, and the running sync prevented library refresh.
The missing parent Job could also stop cancellation reaching nested work.

The bridge now carries the full inherited coroutine context through `runBlocking`.
Nested work stays on the same transaction thread, cancellation reaches it, and
failures unwind the SQL transaction. A new test using the real Android database
handler and generated SQLDelight transacter failed against the previous bridge
and passes after this correction. Its driver models thread confinement, an
exclusive write lock and rollback; it does not use physical Android SQLCipher.

Sync-before-library-update work is now tagged as sync work too. If that explicitly
chained sync fails, its existing error indication remains and its output records
`sync_failed=true`, while permitting the dependent library refresh to continue
against local data. Standalone manual/automatic sync still reports failed work.
Cancellation still cancels the worker/chain. Restore diagnostics include stage
start and duration for categories, searches/feeds, extension stores, library,
and settings, without record titles or preference values.

For an already stuck installation, force-stop Komikku in Android app settings,
install the corrected APK over the existing build, and reopen it. Do not clear
storage or uninstall. The stuck transaction must be released by ending the old
process; hiding the banner does not release it.

## How the new path works

1. A shared coroutine coordinator covers sync and file restoration. Immediate
   triggers share one WorkManager unique-work name, so startup/resume/manual
   events cannot create separate competing immediate jobs. The periodic worker
   shares the same execution lock. Busy requests coalesce instead of waiting in
   a redundant sync queue. Cancellation and failures release the lock.
2. Capture the selected local backup in a SQL transaction. Compute SHA-256 of a
   canonical representation, including actual preference values and record
   contents. Collection iteration order, device-local fetch timestamps, and
   modification timestamps are not treated as evidence of a user edit. Category
   IDs and category-valued preferences are compared by category name.
3. Load an atomic, app-private checkpoint containing the previously acknowledged
   local snapshot, remote snapshot, and exact strong ETag. It is scoped to URL,
   folder, account, and selected sync options. Legacy unscoped `sync_etag` values
   are not trusted. Missing/corrupt checkpoints cause a fresh read and safe merge.
4. A conditional GET with a cached, quoted strong ETag can return 304 without
   downloading the backup body. Weak/missing/malformed ETags cause a full GET and
   content comparison. No decision depends exclusively on a wall-clock time.
5. If neither side changed, stop without merging, uploading, restoring, or
   rewriting preferences. Update the successful-check time without a success
   notification. If needed, merge records by source/URL and chapters by URL;
   combine history, trackers, source mappings, extension stores, searches, and
   feeds rather than dropping shared entries.
6. Compare each field against both acknowledged snapshots. Independent changes
   to different chapters or fields are retained. A same-field incompatible edit
   fails before replacing either side. Newly synthesized records advance their
   version beyond their ancestors for compatibility with legacy clients.
7. Upload only if merged remote contents changed. Use exact `If-Match` for an
   existing file and `If-None-Match: *` for creation. A 412 causes a fresh read and
   merge, at most three attempts. Authentication, network, decoding, and server
   errors never become “missing backup”. Responses always close.
8. Restore only when selected local contents differ. Recheck the local revision
   inside the database transaction, so edits made while awaiting the network
   cannot be replaced by the earlier snapshot. Apply SQL changes and capture the
   resulting local snapshot within the same outer transaction. Errors propagate
   instead of being treated as a successful best-effort import. Align chapter
   versions even when reading state matches; apply merged series flags, notes,
   bookmarks, and favorite changes without importing device scheduling state.
9. Save the checkpoint only after upload and any required local application
   succeed. Settings-only and empty-library syncs work. Restore toggles govern
   import too. Connection, sync-control, and internal app-state settings do not
   migrate between devices. Existing server connection entries are retained for
   compatibility but never imported.

While checking or uploading, the status is Syncing. Restoring begins only after
the local recheck succeeds and actual restoration starts, and ends in `finally`.
The existing one-second banner debounce keeps fast no-op checks unobtrusive.
Localized failures appear in the sync notification. Diagnostic logs contain
trigger, content revisions, decisions, stage durations, failure classes, and
conditional-write retry counts, without preference values, titles, URLs, server
response bodies, passwords, or tokens.

## Data protection and compatible limitations

- Before a meaningful merge/write/restore, keep `local.proto` and `remote.proto`
  in the app's private `no_backup/webdav-sync/<scope>/` directory. Normal recovery
  copies rotate; differing bootstrap snapshots and detected conflicts additionally
  retain content-addressed copies.
  These files can contain private settings and are excluded from Android cloud
  backup. Do not publish them. Clearing app storage/uninstalling removes them.
- A server without strong ETags still supports no-op comparison and download-only
  synchronization. Updating an existing shared file is refused with an explicit
  error because GET-then-unconditional-PUT cannot prevent another device's update
  from being lost. Configure strong ETags and conditional-write support on that
  server. Last-Modified/size alone cannot safely replace an ETag. Servers that
  falsely report validators or ignore HTTP preconditions violate the safety
  contract; client-side metadata cannot make them safe.
- The legacy format has no preference ancestry, no global causal record version,
  and no general deletion tombstones. On bootstrap, retain the existing
  record-version rule; for equal-version chapter progress preserve read/bookmark
  truth and maximum page. For unversioned preference conflicts, preserve the
  current device's value, with the prior remote values retained in recovery.
  Subsequent syncs use the checkpoint for per-field ancestry. An installation
  without a checkpoint cannot reconstruct which historical edit happened first.
- An offline device is never a deletion signal. Missing manga/chapters/categories
  and preference keys are retained by union. Explicit favorite removals remain
  modeled as favorite state. Category deletion/rename, preference removal, or
  hard record deletion is not a reliable cross-device deletion protocol. Existing
  category ordering/display restoration conventions remain; this is not a new
  category ordering conflict resolver.
- Same-field conflicts are preserved and reported, not automatically resolved.
  There is no new conflict-resolution UI. Retain/export both devices' backups
  before deliberately reconciling a conflicting value. Recovery files can be
  extracted from a debuggable build with `adb exec-out run-as app.komikku.dev ...`
  and restored using the existing backup importer; inspect/select import options
  rather than blindly restoring the whole file.
- SQL restore work rolls back on failure/cancellation. SharedPreferences, custom
  metadata preferences, and SQL cannot share one physical transaction. A process
  death or storage failure during settings writes can leave some settings
  applied; the checkpoint stays unacknowledged and recovery copies remain so the
  next sync can replay/reconcile. This is not an all-stores atomic commit.
  Avoid editing settings during an actual restore: SharedPreferences edits do not
  participate in SQL's write lock or its snapshot isolation.
- Downloaded chapter files and download queues remain local. Sync does not
  remove/download those files. Local chapter fetch bookkeeping and database IDs
  are not remote deletion instructions.
- The Duo fix does not require installing this fork on the other devices. Original
  backup protobuf fields remain readable by existing backup importers; the new
  checkpoint is local only. Safety on the unchanged upstream devices remains
  constrained by their own merge/write behavior, as described below.
- A complete selected local snapshot is still constructed to detect preference
  and non-versioned changes reliably. The optimization removes unnecessary
  remote bodies, merges, uploads, and restores, rather than assuming a database
  timestamp captures every settings change.

## Mixed installation: one Duo and two original Komikku devices

The intended deployment is this fork on the Duo, with the other two devices
remaining on the original upstream release. The latest published release checked
on 2026-10-29 is [v1.14.1](https://github.com/komikku-app/komikku/releases/tag/v1.14.1).
Its [merge implementation](https://github.com/komikku-app/komikku/blob/v1.14.1/app/src/main/java/eu/kanade/tachiyomi/data/sync/service/SyncService.kt)
uses the same version-selection and preference-merging rules as the unmodified
`SyncService` bundled in this fork. Master was checked as well. These tests exercise
that real bundled merger, not a second fixed-client simulation.

- The Duo can skip five unchanged opens while both original devices are offline.
  Neither other device needs to understand the Duo's local checkpoint.
- Selected backup bytes and record versions remain in the original format.
  Independent changes to different chapters can pass through the original
  mergers and converge on the Duo. This does not certify every possible conflict
  or the original Android APK's restore/network behavior.
- An original client can strip shared application preference keys and rewrite
  the backup even without user edits. The Duo retains those local values and
  can republish the missing shared keys without restoring its database. That
  original-client churn still changes the remote file and can require downloads
  and repair uploads on the Duo; a local patch cannot stop those remote writes.
- An original client can replace an edited source preference with the remote
  value before uploading. If the edit never reaches WebDAV or the Duo, the Duo
  cannot recover it from synchronization data. Original settings restoration and
  original restore banners are not fixed by installing this APK only on the Duo.
- An original client resolves equal chapter versions by choosing its own whole
  chapter. For example, its offline bookmark edit can erase another device's read
  flag at the same version. Treat a received read/bookmark/page regression that
  has not advanced past the Duo's acknowledged remote chapter version as an
  ambiguous conflict, retaining the Duo's local state and recovery copies.
  Intentional read resets with an advancing version remain supported. This
  protects the Duo from the detectable rewrite; it cannot undo data already
  discarded or restored on the two original devices.
- Concurrency coordination applies within the Duo. Conditional writes protect
  its own uploads against intervening writers; they cannot make an original
  client's unconditional or incorrect conditional upload safe.

End-to-end conflict safety for all three installations cannot be guaranteed by
changing only one of them. The supported Duo improvements and the unchanged
original-client limitations must be evaluated separately.

## Automated verification

Run from the repository with JDK 21 and Android SDK 36 available:

```powershell
./gradlew.bat spotlessApply
./gradlew.bat spotlessCheck
./gradlew.bat assembleDebug
./gradlew.bat :app:testDebugUnitTest --tests 'eu.kanade.tachiyomi.data.sync.*'
```

`WebDavSyncTest` covers no-op/identical data, five process reopen simulations,
upload-only and download-only changes, independent offline edits and convergence,
same-field conflicts, empty libraries, settings, failure/cancellation retry,
fallback metadata, record retention, category ID collisions, and coordination.
`OriginalKomikkuCompatibilityTest` exercises the original merger alongside the
Duo engine, including five unchanged opens, independent chapter changes across
three clients, preference stripping, upstream loss cases, and ambiguous chapter
regressions. `WebDavTransportTest` uses a real loopback HTTP stand-in with no new dependency:
conditional headers, legacy protobuf, 304/404, 412, missing/weak tags, corrupt
responses, server errors, and timeout. `WebDavRestoreTest` verifies exception and
cancellation propagation through the transaction contract before preference
restoration. `WebDavDatabaseTransactionTest` additionally exercises the real
database handler and generated SQLDelight transacter through nested restoration,
cross-dispatcher suspension, failure rollback, cancellation, and a subsequent
successful transaction. `SyncDataJobTest` verifies chained-update failure policy,
failure output and WorkManager tags. Physical Android SQLCipher/process-death behavior and real WebDAV
server compliance also need the device checks below.

Verified on 2026-10-29 with JDK 21 / the installed Android SDK:

- `spotlessApply`: passed.
- `spotlessCheck`: passed.
- `assembleDebug`: passed; universal and ABI-specific debug APKs produced.
- WebDAV and mixed-original-client regression suites: **50 passed, 0 failed**,
  including 7 tests using the unmodified original merger and 6 transaction/worker regressions.
- Complete `:app:testDebugUnitTest`: **147 discovered; 146 passed, 1 skipped, 0 failed**.
  The skipped test is the existing disabled `Tester.stripBackup` test.
- `git diff --check`: passed; only Komikku's base locale strings changed.

The build emitted D8 Kotlin metadata compatibility warnings but produced the
APKs successfully. Automated verification used loopback HTTP and simulated
database storage. The original restore contract test uses a mocked
handler; the new transaction tests use the production handler/transacter and a
simulated transactional driver. Neither verifies physical SQLCipher rollback
after a process kill. Real two-device and process-death validation remains manual.

Subsequent read-only inspection of a Surface Duo 2 confirmed that the installed
APK matched the delivered build. Device logs showed a completed awaited restore,
followed by unchanged WebDAV checks reporting `decision=no-op restore=false`
and successfully completed sync-before-library-update chains. Missing chapter
updates were separately traced to Smart update filtering every library entry
and automatic library updates being disabled. The user confirmed chapter updates
resumed after adjusting those filters. This inspection did not modify device
settings, library records, or WebDAV data, and does not replace the multi-device
and interruption checks below.

## Manual acceptance checks

Use a dedicated test WebDAV folder and preserve/export existing device backups.
Enable the normal automatic startup/resume options; keep sync section selections
consistent between devices. Device A is the modified Duo; devices B and C remain
on original Komikku. Establish a baseline sync on each device before the offline
checks. The original-client limitations above still apply. The debug build is
`app.komikku.dev`, separate from
the release app, so it needs its own test data and WebDAV configuration.

1. Device A: sync successfully. Put B offline. Record the WebDAV file's ETag.
   Close/reopen A five times without editing anything. Expect conditional GETs
   returning 304 (or GET/content equality without tags), no PUT, no restore,
   no settings changes, and no Restoring banner. Logs should say
   `decision=no-op restore=false`.
2. Edit an application setting and a source preference on A. Sync. Expect one
   conditional PUT and no restore for a local-only change. Reopen A and verify
   both settings remain saved and no further PUT is made.
3. Read chapter 1 on A and sync while B is offline. Bring B online and sync;
   verify chapter 1 progress arrives. Read chapter 2 on B, sync B, then A.
   Both devices should show both chapters read. Sync/reopen both again and expect
   no further restoration or upload.
4. Repeat with independent offline changes: bookmark one chapter on A, change a
   different chapter on B, then reconnect/sync each. Also check library additions,
   existing category memberships, series reader flags/notes, and history.
5. Change the same progress field or preference differently on both devices
   after establishing checkpoints. Expect a conflict indication and retained
   local/remote copies rather than an arbitrary winner.
6. Stop the server or induce a timeout. Verify local reading still works, sync
   reports failure, a library refresh requested after the sync has finished can
   still run despite a failed pre-update sync, and a later sync retry succeeds. Trigger resume/manual sync
   repeatedly during a slow request and verify only one operation changes data.
7. Disable source/app/private settings import and sync against a backup containing
   those settings. Verify excluded local settings and connection credentials stay
   unchanged and excluded remote sections are retained.
8. Test a server without ETags: unchanged opens must not restore; a required
   shared-file update must report the safe-write limitation. Test an existing
   legacy `backup.proto`, first-time empty remote, and two concurrent writers.
9. Cancel during a large actual restore and retry. Verify SQL changes either
   commit together or roll back, no checkpoint is falsely acknowledged, and
   subsequent sync is not blocked. Separately test force-stop/process death.

## Build and install

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$env:ANDROID_HOME = 'C:/Users/johnw/AppData/Local/Android/Sdk'
./gradlew.bat spotlessApply
./gradlew.bat spotlessCheck
./gradlew.bat assembleDebug
./gradlew.bat :app:testDebugUnitTest --tests 'eu.kanade.tachiyomi.data.sync.*'
./gradlew.bat installDebug
```

Or install the universal APK to a chosen connected test device:

```powershell
& "$env:ANDROID_HOME/platform-tools/adb.exe" -s DEVICE_SERIAL install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

## Changed files

| File (paths relative to repository) | Change |
|---|---|
| `app/src/main/java/eu/kanade/tachiyomi/App.kt` | Label startup/resume triggers in diagnostics. |
| `app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncCoordinator.kt` | Shared sync/restore execution lock and coalescing. |
| `app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncDataJob.kt` | One immediate-work identity, trigger logs, cancellation/failure handling. |
| `app/src/main/java/eu/kanade/tachiyomi/data/library/LibraryUpdateJob.kt` | Tag chained sync correctly and allow local library refresh after reported sync errors. |
| `data/src/main/java/tachiyomi/data/TransactionContext.kt` | Preserve inherited transaction context and cancellation through nested suspending SQL work. |
| `app/src/main/java/eu/kanade/tachiyomi/data/sync/SyncManager.kt` | Transactional snapshots, awaited WebDAV restore, accurate state and sync options. |
| `app/src/main/java/eu/kanade/tachiyomi/data/sync/service/WebDavSyncService.kt` | Scoped atomic checkpoints/recovery files, orchestration, bounded retries, safe errors. |
| `app/src/main/java/eu/kanade/tachiyomi/data/sync/service/WebDavSyncEngine.kt` | Testable change-detection/transfer/restore decisions and checkpoint boundary. |
| `app/src/main/java/eu/kanade/tachiyomi/data/sync/service/WebDavSyncMerger.kt` | Canonical revisions, per-field ancestry, safe unions and category mapping. |
| `app/src/main/java/eu/kanade/tachiyomi/data/sync/service/WebDavTransport.kt` | Conditional HTTP, reliable metadata fallback, strict decoding/error handling. |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestoreJob.kt` | Coordinate file restore with sync and start/stop status only for actual work. |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/BackupRestorer.kt` | Strict synchronous SQL restore entry point for WebDAV. |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/MangaRestorer.kt` | Apply already-merged state and align chapter versions; retain device bookkeeping. |
| `app/src/main/java/eu/kanade/tachiyomi/data/backup/restore/restorers/PreferenceRestorer.kt` | Optional strict failure propagation during sync. |
| `i18n-kmk/src/commonMain/moko-resources/base/strings.xml` | Localizable WebDAV conflict, failure, and unsafe-server messages. |
| `app/src/test/java/eu/kanade/tachiyomi/data/sync/WebDavSyncTest.kt` | Sync/merge/no-op/data-safety regression tests. |
| `app/src/test/java/eu/kanade/tachiyomi/data/sync/WebDavTransportTest.kt` | HTTP protocol regression tests. |
| `app/src/test/java/eu/kanade/tachiyomi/data/sync/WebDavRestoreTest.kt` | Restore transaction contract/failure/cancellation regression. |
| `app/src/test/java/eu/kanade/tachiyomi/data/sync/WebDavDatabaseTransactionTest.kt` | Real handler/transacter nesting, progress, rollback, cancellation and recovery regressions. |
| `app/src/test/java/eu/kanade/tachiyomi/data/sync/SyncDataJobTest.kt` | Chained library-update failure policy, failure output and work tags. |
| `app/src/test/java/eu/kanade/tachiyomi/data/sync/OriginalKomikkuCompatibilityTest.kt` | Mixed Duo/original-client exchange tests and characterization of original loss cases. |
| `docs/webdav-sync.md` | Diagnosis, guarantees, limitations, validation, build/install commands. |
