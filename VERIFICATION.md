# MealSip — verification notes

Status legend: ✅ done · ⏳ pending · ⛔ blocked (reason given)

## Before the first push

| Check | Status | Notes |
|---|---|---|
| Domain unit tests (40) compiled and run with kotlinc 2.2.0 on JVM | ✅ | 40/40 passed (Gradle run in CI pending) |

## Build and signing (CI)

| Check | Status | Notes |
|---|---|---|
| Focused unit tests (`testDebugUnitTest`) | ⏳ pending | First CI run |
| Room schema v1 exported and committed | ⏳ pending | |
| Release lint (`lintRelease`) | ⏳ pending | |
| Signed non-minified APK + AAB | ⏳ pending | |
| `apksigner verify --print-certs`, no `CN=Android Debug` | ⏳ pending | |
| AAB `jarsigner` integrity + expected signer | ⏳ pending | |
| Permission allowlist (APK, AAB, merged manifest) | ⏳ pending | |
| Native libraries / 16 KB | ⏳ pending | Expected: no `.so` files |
| R8 + resource shrinking pass, mapping preserved | ⏳ pending | Enabled only after the non-minified pass is green |
| Release artifact size | ⏳ pending | Filled from the CI release report |

Upload certificate SHA-256:
`DB:B6:16:6E:AD:C9:1C:E9:0E:E5:29:01:2B:FF:F9:94:F0:7C:A5:D7:98:B8:C3:E1:EE:40:B7:E3:01:DB:14:C8`

## Device checks (adb install of the signed release APK + logcat)

Device / Android version: ⏳ not recorded yet

| Check | Status |
|---|---|
| Offline launch | ⏳ pending |
| All four period blocks, two columns on a tablet / landscape | ⏳ pending |
| Customization (rename, icon, reorder, archive, add) | ⏳ pending |
| Quick logging, duplicate-tap protection, Undo | ⏳ pending |
| Manual entry, historical edit and move between dates | ⏳ pending |
| Reminders, permission denial, settings link | ⏳ pending |
| Reboot recovery | ⏳ pending |
| Process recreation (draft kept) | ⏳ pending |
| Accessibility (TalkBack order and labels, font scale 200%) | ⏳ pending |
| Rotation (draft kept, layout) | ⏳ pending |
| Clear all data | ⏳ pending |
| 16 KB runtime environment | ⏳ only required if native libraries appear |
