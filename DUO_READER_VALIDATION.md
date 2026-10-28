# Surface Duo reader implementation and validation

Source: `https://github.com/simpleedev/komikku_duo`, starting at
`1656575e8fdaaef010c652f9758130c2a01d8890` on `master`.
The initial implementation was pushed as `3792eba` on `feat/duo-reader-layout`;
the chapter-handoff follow-up is on `fix/duo-chapter-handoff`. The supplied workspace was initially empty;
the fork was fetched and checked out before editing. There were no existing local changes.

## Behavior

- **Unpaired page position** appears beside page layout in both the reader sheet and
  Settings → Reader. It uses the existing preference store; no database migration is needed.
- **Automatic** places a portrait page on the right for RTL and on the left for LTR,
  reversing this when double-page inversion is enabled. **Right** and **Left** are
  physical positions and ignore parity, inversion, and interface language. **Center**
  retains the full viewport. Chapter openings have a specific layout: page 1 is
  isolated, and a portrait opening always occupies the physical left display.
  The unpaired-page preference continues to apply to other isolated portraits.
- Alignment applies only to horizontal, side-by-side double-page reading. The
  existing Automatic layout decision remains in charge; a hinge never enables it.
  Single-page, split-page, vertical, and webtoon modes retain their existing behavior.
- A genuine landscape or square image is not reduced into an unpaired portrait slot.
  Unknown dimensions fall back to the existing full viewport and decoder behavior.
- `WindowInfoTracker` observes separating vertical folding features while the reader
  is STARTED. Bounds are converted from window coordinates to pager coordinates,
  including viewport insets. The full hinge bounds define the display regions,
  so asymmetric regions and zero-width separating folds are supported.
- Size, inset, spanning, and folding changes update the layout. Leaving a spanned
  window clears the gap; activity recreation creates a new observer. Horizontal
  folding features are ignored rather than reused as stale vertical offsets.
  Retained unpaired holders are recreated when changing single/double layout even
  when their `(page, null)` identity is unchanged.
- Without a hinge, single portraits use adjacent half-width viewports with no gap.
  Existing paired-page bitmap merging remains in place on these devices.
- With a hinge, paired portraits use separate existing image views in the visible
  left/right regions, using original compressed images and tiled decoding. Each
  page supports its own zoom and pan; navigation-to-pan targets the last touched
  page. The physical hinge replaces the optional synthetic center margin here.
- Wide spreads and Center-positioned pages retain one continuous image view. Its
  virtual viewport excludes the hinge width. Two clipped draw passes place every
  image coordinate on visible displays; all touch pointers are mapped around the
  gap without altering the pager's original tap/swipe events. This is a display
  transformation, not an image split or change in page identity.

## Status banners and page counters

When a separating vertical hinge is reported, the top syncing, updating, restoring,
indexing, downloaded-only, and incognito banners center their content within the
physical left display. Their background still spans the window. The existing
status-bar insets, stacking, progress text, and animations are retained. Unspanned
windows retain the full-width centered banner.

Horizontal side-by-side reading shows a separate `page / total` counter at the
bottom center of each occupied display. Physical order follows the actual images,
including RTL and double-page inversion. Each counter uses its actual page index
and chapter total, rather than deriving a second index from the progress string.
An opening beside chapter details has only a left counter; an isolated portrait
has only the counter for its chosen side. Blank/loading/terminal transition panes
have no stale page counter. A continuous image spanning both displays shows the
same source page number on both sides. Image metadata updates the counters when
an initially unknown image is classified or a pair is rebuilt. Single-page,
split-page, vertical, and scrolling readers retain the existing single counter.

The banner and counter rows resolve measured hinge coordinates relative to their
own window position, using physical left/right placement even in an RTL interface.
They wrap their content height rather than allocating a full-screen overlay.

## Chapter transition spreads

In side-by-side mode, **Finished / Next** and **Previous / Current** details use the
physical right region, independently of reading direction and unpaired-page settings.
The left region shows the next chapter's first page beside Finished / Next, or the
current chapter's first page beside Previous / Current. It uses the same measured
hinge regions as normal pages, with adjacent halves on devices without a hinge.

The opening is an actual reader page, included only once in the navigation list.
Continuing after this spread starts at pages 2–3 rather than repeating page 1.
Opening a chapter directly also isolates page 1 on the left, with Previous / Current
on the right when that transition is present. Later pages use the existing pairing,
wide-page isolation, and explicit shift behavior. Direct landscape or square openings
retain their full viewport; when beside transition text, the complete image fits
inside the left region without splitting or reclassification.

The transition uses the normal page holder, including image loading, zoom/pan,
retry controls, animations, and resource cleanup. Selecting the opening follows
the normal progress and active-chapter callbacks; preloading an offscreen holder
does not. Transition text is never counted as an extra manga page. Single-page
chapters therefore complete normally, while multi-page chapters stay at page 1.
Slider jumps and layout changes can locate the opening through the transition's
second item. Previous-chapter preloading still permits backward navigation.

While the destination page list is unavailable, the left page remains blank and
chapter loading/error/retry controls stay on the right. Once that list arrives,
page 1 uses the normal image loading/error/retry controls in the left region.
At the final chapter, **No more chapters** appears on the left; the redundant inline
end message is suppressed on the right. Single-page, vertical, and webtoon transitions
retain the original inline layout and messages.

When the final portrait page occupies only one display, the empty opposite display
shows **Finished / Next** details, including the existing no-next-chapter message
when appropriate. This uses the final page's current holder; it adds no page, does
not advance to the next chapter, and leaves the counter only beneath the image.
It applies to either physical side, including explicit unpaired-page preferences.
Interior pages, pairs, continuous images, and non-side-by-side modes are unaffected.
The details pane uses the normal reader background independently of the image's
automatic background, keeping its text readable on either display.

Chapter activation changes the adapter boundary from `Next(A, B)` to `Prev(B, A)`.
Those represent the same boundary. The adapter now retains the transition frame,
its original labels, and its decoded opening image through that change. If a page
list arrives while the transition is visible, the opening is attached to that
existing frame once. Unchanged opening objects are never disposed and recreated
by an adapter update. First/final boundaries and unrelated chapters remain distinct.

## Swipe navigation and settings layout

**Swipe navigation** defaults to enabled, with separate saved choices for paged
and scrolling readers. The toggle appears below tap zones in the in-reader
Reading mode tab and in each viewer's group under Settings → Reader. Turning it
off blocks touch-driven page turns or list scrolling while retaining image
pan/zoom, taps, volume keys, and programmatic navigation.

If tap zones are disabled and volume-key navigation is also disabled, swipe is
automatically enabled and its saved toggle restored to on. This applies when
turning off the last control, attempting to turn off swipe with no alternative,
and loading previously saved preferences with all three controls off. Long press
opens page actions and does not count as an alternative page-turn control. A
fallback in one viewer does not overwrite the other viewer's swipe preference.

Main settings now use **50/50 panes**, removing the old 450 dp category-pane cap.
On a separating vertical fold, panes follow the measured visible regions and
exclude the entire hinge. Window-coordinate offsets account for system-bar
padding; unspanning clears the gap. Category/detail order follows the interface
language. The reader's tabbed settings dialog stays entirely within the details
display on a spanned device. Sheet width respects the available pane width, so
the standard 460 dp limit cannot force content through the hinge.

## Manga details and chapter list

The large manga screen now shares the same hinge-aware **50/50 layout** as
settings. Cover art, metadata, actions, and description occupy the start display;
suggestions and the chapter list occupy the end display. The old 450 dp details
cap is removed for this screen. A separating vertical hinge also enables this
two-pane layout when the app is otherwise using its compact interface.

Chapter selection actions align with the chapter display and exclude the hinge
instead of occupying a hard-coded half of the full window. The chapter
filter/sort/display sheet stays within that display. The shared layout follows
interface direction, measured window offsets, and changes in spanning. Phones
without a separating hinge retain the compact manga screen.

## Reference and resource handling

The reference was [TachiyomiJ2K](https://github.com/Jays2Kings/tachiyomiJ2K), especially
`ReaderActivity.kt`, `PagerConfig.kt`, `PagerPageHolder.kt` (`supportHingeIfThere`),
and `ImageUtil.kt` (`padSingleImage`, hinge-aware merging).

This implementation uses [AndroidX WindowManager 1.5.1](https://developer.android.com/jetpack/androidx/releases/window)
but deliberately avoids J2K's
bitmap padding and JPEG recompression. Detection lives in `PagerViewer` so its
lifetime and coordinate system follow the actual reader viewport. There is no
first-page bitmap padding. The existing pairing algorithm was extracted for
regression tests; a separate chapter-spread builder now reserves page 1 and joins
it to an adjacent chapter transition, then pairs the remaining pages.

Metadata inspection and existing image processing run on the IO dispatcher.
Metadata decoders are recycled. The new positioning path allocates no padded or
merged full-resolution bitmaps and creates no new disk cache entries. Original
file streams are scoped with `use`; returned buffered sources follow the existing
image-view ownership path. Page loading is serialized and canceled on detach;
the two image views are recycled on detach. The existing merge fallback now probes
through a peek so early landscape/animation isolation cannot consume the displayed
source, and releases its input bitmaps and native decoders. Existing animated GIF
and WebP handling still uses Coil/PhotoView; animation is never converted into a
static bitmap for alignment. Memory use on a physical Duo has not been measured.

## Changed files

| File | Purpose |
| --- | --- |
| `app/build.gradle.kts` | WindowManager dependency |
| `gradle/androidx.versions.toml` | WindowManager version |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/ReaderPreferences.kt` | Persisted preference and option labels |
| `app/src/main/java/eu/kanade/presentation/reader/settings/ReadingModePage.kt` | In-reader option |
| `app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsReaderScreen.kt` | Main settings option |
| `i18n-kmk/src/commonMain/moko-resources/base/strings.xml` | Eight Komikku strings; no translation edits |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerConfig.kt` | Observe placement preference |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewer.kt` | Lifecycle observation, viewport conversion, navigation targeting |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerPageHolder.kt` | Image classification, region layout, hinged pair views, resource handling |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderPageImageView.kt` | Image viewport, gap rendering, pointer mapping |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderSpreadLayout.kt` | Testable placement and hinge geometry |
| `app/src/main/java/eu/kanade/presentation/reader/ChapterTransition.kt` | Optional inline fallback message for the split transition layout |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ReaderTransitionView.kt` | Pass through the optional inline message flag |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerTransitionHolder.kt` | Right-side chapter details, normal opening-page holder on the left, end message |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderTransitionSpread.kt` | Testable transition regions and opening selection |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/PagerViewerAdapter.kt` | Build chapter spreads, locate opening pages, refresh holders on layout changes |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderChapterSpreads.kt` | Reserve each opening once; transition page selection and extra-page counting |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderPagePairs.kt` | Extracted pairing function |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/setting/SwipeNavigation.kt` | Shared fallback policy and saved-toggle restoration |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/ViewerConfig.kt` | Observe effective swipe preference and changes |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/Pager.kt` | Block swipe interception without blocking image gestures or taps |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonConfig.kt` | Observe scrolling-reader swipe choice |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonViewer.kt` | Apply scrolling-reader swipe changes |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonRecyclerView.kt` | Block touch scrolling while retaining zoom pan and taps |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/webtoon/WebtoonFrame.kt` | Avoid marking disabled touch scrolling as an active scroll |
| `app/src/main/java/eu/kanade/presentation/reader/settings/SwipeNavigationItem.kt` | In-reader toggle, explanation, and settings fallback observer |
| `app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsScreen.kt` | Equal settings panes with measured hinge regions |
| `app/src/main/java/eu/kanade/presentation/util/HingePaneBounds.kt` | Pure pane-coordinate calculations |
| `app/src/main/java/eu/kanade/presentation/util/HingeAwareLayout.kt` | Shared fold observation, equal panes, chapter action-bar alignment, and safe settings sheet placement |
| `app/src/main/java/eu/kanade/presentation/manga/MangaScreen.kt` | Equal manga details/chapter panes and hinge-safe chapter selection actions |
| `app/src/main/java/eu/kanade/presentation/manga/ChapterSettingsDialog.kt` | Keep chapter filter/sort/display settings within one display |
| `app/src/main/java/eu/kanade/presentation/reader/settings/ReaderSettingsDialog.kt` | Enable hinge-safe placement of reader settings |
| `app/src/main/java/eu/kanade/presentation/components/TabbedDialog.kt` | Forward optional hinge-safe placement |
| `app/src/main/java/eu/kanade/presentation/components/AdaptiveSheet.kt` | Place the requested settings sheet within one display |
| `presentation-core/src/main/java/tachiyomi/presentation/core/components/AdaptiveSheet.kt` | Respect incoming pane-width constraints |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderSpreadLayoutTest.kt` | Placement, pairing, and geometry regressions |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/UnpairedPagePreferenceTest.kt` | Defaults, serialization, recreation, unknown-value fallback |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderTransitionSpreadTest.kt` | Transition regions, opening selection, blank fallback, end message, unchanged page state |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderChapterSpreadsTest.kt` | Opening deduplication, direct opening, chapter activation identity, loading, short chapters, shifts, RTL, slider/progress helpers |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/setting/SwipeNavigationTest.kt` | Navigation combinations, live fallback, saved defaults and independent viewer preferences |
| `app/src/test/java/eu/kanade/presentation/util/HingePaneBoundsTest.kt` | Equal halves, odd widths, measured gaps, insets, zero-width folds, unspanning and invalid bounds |
| `app/src/main/java/eu/kanade/presentation/components/Banners.kt` | Center top status content within the physical left display |
| `app/src/main/java/eu/kanade/presentation/reader/ReaderPageIndicator.kt` | Independent counters centered in measured display regions |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/ReaderActivity.kt` | Connect the reader overlay to displayed spread counters |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderPageNumbers.kt` | Pure physical page-number and chapter-total mapping |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderPageNumbersTest.kt` | Direction/inversion, real indices, chapter openings, blank panes, isolated and continuous pages |
| `app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderChapterTailDetails.kt` | Choose the empty final-page display and its following chapter |
| `app/src/test/java/eu/kanade/tachiyomi/ui/reader/viewer/pager/ReaderChapterTailDetailsTest.kt` | Empty-pane filling, terminal chapters, activation, excluded layouts, and one-page progress |
| `DUO_READER_VALIDATION.md` | Delivery and manual validation notes |

## Automated verification

Environment: installed Android Studio JDK 21.0.9, Gradle wrapper 9.3.1, Android SDK 36,
Windows PowerShell. Set `JAVA_HOME` and `ANDROID_HOME` to the installed locations.

```powershell
.\gradlew.bat spotlessApply
.\gradlew.bat spotlessCheck
.\gradlew.bat assembleDebug :app:testDebugUnitTest --tests '*ReaderSpreadLayoutTest' --tests '*UnpairedPagePreferenceTest' --tests '*ReaderTransitionSpreadTest' --tests '*ReaderChapterSpreadsTest' --tests '*SwipeNavigationTest' --tests '*HingePaneBoundsTest' --tests '*ReaderPageNumbersTest' --tests '*ReaderChapterTailDetailsTest' :app:lintDebug
```

| Check | Actual result |
| --- | --- |
| `spotlessApply` | Passed |
| `spotlessCheck` | Passed |
| `assembleDebug` | Passed; ARM64 and universal APKs produced |
| Focused `testDebugUnitTest` | 87 passed, 0 failures/errors/skips (including 15 swipe-navigation, 7 pane-geometry, 7 page-counter, and 10 chapter-handoff cases) |
| `:app:lintDebug` | Completed; not a clean report (see below) |

Android lint reports **7 errors, 123 warnings, and 1 hint**. The seven error locations
are unchanged source:
calendar locale reads (3), library/reader composable Flow calls (3), and a Discord
format string (1). The repository already sets `lint.abortOnError = false`, so
Gradle reports success despite these errors; this is not a claim that lint is clean.
Existing reader touch-view constructor/accessibility warnings remain in the report.
The unchanged AGP/Kotlin toolchain also emits Kotlin metadata compatibility
messages during D8/lint work. Build success and executed tests are confirmed;
device validation remains necessary.

The focused suite covers RTL pairing; RTL/LTR Automatic; explicit Left, Right, and
Center under both directions and inversion states; a shifted unpaired first page;
natural chapter tails; portraits isolated by wide pages; re-pairing after shifting;
landscape/square/invalid dimensions; disabling side-by-side alignment; asymmetric
hinges; no hinge; zero-width folds; viewport origin changes; simulated rotation,
unspanning, and resizing; and touch coordinate mapping. Preference tests use the
real Android preference adapter with mocked SharedPreferences, including wrapper
recreation and enum serialization. They do not emulate process death or disk I/O.
Transition cases cover both transition types, the first and final
chapters, loading/error/empty destinations, an available destination after loading,
wide-page pairing preservation, asymmetric and removed hinges, single-page fallback,
and unchanged page identities, flags, and requested positions.
Chapter-spread cases cover exactly one occurrence of every page, continuation at
pages 2–3, direct opening isolation, equivalent Next/Prev identities after chapter
activation, destination loading, one-page and even-length chapters, wide images,
initial and explicit later shifts, reversed RTL order, chapter adjacency, unchanged
vertical pairing, physical-left opening placement, and slider/progress helpers.
They also distinguish the first and final transitions of a single chapter when
reusing holders and restoring selection, and find the opening after chapter activation.
Page-counter cases cover RTL/LTR and inversion, nonconsecutive page identities,
the opening chapter's own total, both transition directions, loading/end transitions,
explicit and automatic isolated-page positions, continuous images, and missing totals.
Chapter-handoff cases cover stable Next/Prev boundaries across repeated activation,
attaching an opening to an existing loading transition, distinct reversed/start/end
boundaries, details in the empty final-page display, terminal chapters, retained
previous-chapter details, excluded layouts, unknown/loading chapters, and one-page
identity/progress preservation.

Geometry tests simulate fold/rotation input; they are not Android lifecycle,
native image-decoder, gesture instrumentation, visual, or physical-device tests.
No APK was installed on the connected Pixel 7 Pro or Galaxy S24 Ultra.

Reports/logs: `app/build/reports/tests/testDebugUnitTest/index.html`,
`app/build/reports/lint-results-debug.html`, `build/duo-spotless-apply.log`,
`build/duo-spotless-check.log`, and `build/duo-build-validation.log`.
The transition follow-up logs are `build/duo-transition-spotless-apply.log`,
`build/duo-transition-spotless-check.log`, and `build/duo-transition-build-validation.log`.
The opening-pagination follow-up logs are `build/duo-opening-spotless-apply.log`,
`build/duo-opening-spotless-check.log`, and `build/duo-opening-build-validation.log`.
The navigation/settings follow-up logs are `build/duo-navigation-spotless-apply.log`,
`build/duo-navigation-spotless-check.log`, and `build/duo-navigation-build-validation.log`.
The manga screen follow-up logs are `build/duo-manga-spotless-apply.log`,
`build/duo-manga-spotless-check.log`, and `build/duo-manga-build-validation.log`.
The banner/counter follow-up logs are `build/duo-indicators-spotless-apply.log`,
`build/duo-indicators-spotless-check.log`, and `build/duo-indicators-build-validation.log`.
The chapter-handoff follow-up logs are `build/duo-handoff-spotless-apply.log`,
`build/duo-handoff-spotless-check.log`, and `build/duo-handoff-build-validation.log`.

## APK and installation

The Duo 2 uses the ARM64 artifact:
`app/build/outputs/apk/debug/app-arm64-v8a-debug.apk`.
The universal APK is `app/build/outputs/apk/debug/app-universal-debug.apk`.
The requested named copy is delivered to
`C:\Users\johnw\Dropbox\appdev\komikku_duo.apk`, with identical content to the ARM64 artifact.

ARM64 APK SHA-256:
`71a0bd37aee16f1386d21542f5e3eaa876a460178e745f52d3a04d58cc8b0295`.
`apksigner verify --print-certs` passed. The local debug signing certificate's
SHA-1 is `50f87a68e0496a85d6644d54426406866dab3598`.

The existing debug variant is **`app.komikku.dev`**, signed with the local Android
debug certificate. The existing release identity is **`app.komikku`**; release
signing uses private CI secrets. No package name, signing configuration, version
code, database schema, backup schema, sync provider, authentication code, library
data, history, or progress-persistence code was changed.

Install the ARM64 debug APK as a separate test app, using Android's package installer
or `adb -s <Duo-serial> install <absolute-path-to-apk>`. It has separate local data
and preferences. Do not uninstall the official app or clear its data. If a previous
debug installation has a different signer, preserve that debug app's data and use
its original signing key rather than uninstalling automatically. An update to the
official package requires the matching official signing certificate; a locally
signed release cannot safely replace it.

### Google Drive constraint

**This debug checkout does not contain `app/src/main/assets/client_secrets.json`.**
The source reads that asset for Google Drive OAuth; CI normally supplies it from
`GOOGLE_CLIENT_SECRETS_JSON`. The APK can be used for local reader testing, but
Google Drive login/synchronization cannot be promised for this APK without valid
credentials and the corresponding signing/OAuth configuration. Follow the
repository's `CONTRIBUTING.md` for provisioning; keep credentials out of Git.
Both packages retain the existing `eu.kanade.google.oauth` callback scheme, so an
OAuth chooser can appear when both are installed.

An independently registered OAuth application must not be assumed to access the
official app's existing sync data: Drive's
[application data folder is specific to its application](https://developers.google.com/workspace/drive/api/guides/appdata).
No Drive sign-in, upload, restoration, synchronization, credential copying, or
existing-app data change was performed. Keep using the official app for production
sync until a credentialed build is validated. The reader changes themselves leave
all existing sync/backup/library logic intact, but end-to-end sync was not tested.

## Manual Surface Duo 2 acceptance checklist

Use a separate test app and local sample manga so production data is not involved.
`build/duo-layout-samples.zip` contains four synthetic CBZ chapters: three portraits,
a single opening portrait, portraits surrounding a wide spread, and a square image.
The red edge markers and numbered grid help reveal cropping or hidden pixels.
Extract these CBZ files into `local/Duo layout validation/` under the test app's
selected storage folder, then refresh Local source. They contain no library data.

1. Span the app across both screens in book posture. Choose horizontal RTL reading
   and Double pages. Confirm page 1 starts on the left. Advance to pages 2–3 and
   confirm `[page 3] | hinge | [page 2]`, fitting each visible region without hidden
   content, including different image sizes.
2. Read a chapter with an even portrait count to its unpaired last page. **Automatic**
   and **Right** must put that tail on the right. **Left** moves it to the other display;
   **Center** uses the continuous full viewport around the gap.
3. Open a chapter directly and test a one-page chapter. Page 1 must stay on the left
   under RTL, LTR, inversion, and every unpaired preference. Confirm first-page
   progress and one-page chapter completion. Shift farther into a chapter and confirm
   no missing or repeated pages.
4. Test a genuine landscape spread, a square image, and a portrait adjacent to a
   landscape spread. Confirm no accidental half-width landscape rendering, missing
   pixels, wrong page count, duplicate page, or unreadable/consumed source.
5. Toggle border cropping, each scale/fit option, rotation-to-fit, zoom start,
   double-tap zoom, pinch, pan, navigate-to-pan, tap zones, swipes, and page transitions.
   On hinged pairs, zoom each page and verify navigation pans the last touched page.
6. Test still JPEG, PNG, WebP, and animated GIF/WebP, retrying an image failure and
   moving rapidly between chapters. Check animations, loading/error overlays, image
   quality, and memory stability with large pages.
7. Rotate, fold/unfold, unspan/re-span, resize/multiwindow, change fullscreen/cutout
   options, background/resume, and recreate the activity. Check fresh hinge coordinates,
   no stale gap, the selected page, chapter transitions, numbering, and saved progress.
   Rotation to a horizontal hinge must clear vertical hinge offsets.
8. Choose Single page, split-page, vertical, and webtoon modes. Confirm no new half-width
   alignment and unchanged navigation. Test Automatic layout in portrait and landscape.
9. On a tablet or unspanned device, repeat Automatic/Left/Right/Center. Confirm no
   artificial hinge gap. Restart the app and verify the placement preference persists.
10. Only after valid OAuth/signing configuration is available, validate Drive login
    and progress/library/category synchronization on test data/account. Confirm the
    established official app remains installed with its original data. Do not enable
    a newly credentialed build against the production sync state as an unreviewed test.
11. Check chapter transitions in side-by-side mode: details always on the right;
    next/current first page on the left; blank left while the page list is unavailable;
    chapter loading/retry on the right; image loading/retry on the left; and
    **No more chapters** on the left at the end. Continue and confirm page 1 does not
    repeat: the next spread starts at pages 2–3. Test backward navigation, the page
    slider, a one-page chapter, image retry, and delayed chapter loading. Repeat with
    a landscape/animated opening, inversion, LTR, rotation, unspanning, and single-page
    mode. Loading adjacent chapters or changing the active chapter must keep the same
    displayed page. Reopening a chapter at saved page 1 should return to its left opening.
12. In each paged direction and in scrolling modes, disable Swipe navigation while
    taps are enabled. Confirm drags do not change pages, while tapping, zooming,
    panning, long press, menus, sliders, retry buttons, and volume keys still work.
    Repeat with taps disabled and volume keys enabled. Disable the last remaining
    control and confirm swipe and its displayed toggle turn back on. Restart the
    reader and check saved choices, including independent paged/scrolling settings.
13. Span main settings across the Duo. Confirm category and detail text stays on
    its own display and wraps before the hinge. Test Reader, Appearance, search,
    long labels, large fonts, RTL interface layout, rotation, and unspanning. Open
    the reader's settings dialog and check all tabs and dismiss controls within
    the details display, including pinch/zoom settings and the new swipe toggle.
14. Open a manga in a spanned window. Confirm cover/details on one display and
    suggestions/chapters on the other, with neither column entering the hinge.
    Scroll both columns, select chapters and check the selection-action bar, open
    filter/sort/display settings, start/resume reading, and test downloads. Repeat
    with long metadata, large fonts, RTL interface layout, rotation, resizing,
    unspanning, and compact-interface preferences. Recheck ordinary phone layout.

15. While spanned, trigger syncing/updating/indexing and enable downloaded-only or
    incognito banners. Check that each label and spinner are centered at the top of
    the physical left display, with readable wrapping, correct status-bar spacing,
    and full-width backgrounds. Change interface language direction, font size,
    rotation, spanning, and window size; the text must remain visible.
16. Enable reader page numbers. Check each counter beneath its actual image in
    RTL/LTR and inversion modes, chapter openings/transitions, one-page chapters,
    isolated tails, explicit unpaired positions, continuous wide/square images,
    shifting, slider jumps, image loading/reclassification, and chapter activation.
    Blank and final-message panes must have no counter. Check live settings changes,
    fold/unfold, rotation, and the original single counter in other reader modes.

17. Reproduce the reported chapter handoff: a 24-page chapter's last portrait on
    the right must show Finished / Next on the left. Advance once: the next opening
    must appear on the left, with stable Finished / Next labels on the right while
    the active chapter updates. Continue at pages 2–3 without repeating page 1.
    Test slow page-list loading, retries, delayed preloading, direct openings,
    backward navigation, a one-page chapter, no following chapter, and explicit
    left/right/center placement. Check for image flashes or changing labels during
    activation and confirm counters, zoom, progress, and wide images still work.

Physical hinge reporting, real rendering/gestures, peak memory, and end-to-end Google
Drive behavior remain unverified until these device tests are completed.
