# Fixes from device log100118 and editor screenshots

Evidence: Xiaomi Android16,11k+media files. Main-thread stall samples repeatedly hit settings editorItems.toUi() formatting, publicSessionRows.publicRow() queue lookup, root alias copying and gallery layout. Startup/late frame gaps are recorded separately; no Java crash cause was present in this export.

Changes:
- Index public queue metadata by ID/URI once instead of scanning the library per queue entry. Preserve first matching row (including duplicate references), raw session IDs and privacy/blocked-identity checks. Empty queues avoid index construction. Phone aliases are projected independently of queue changes.
- Settings editor rows are formatted on Dispatchers.Default and published once per input identity. A source-identity guard hides old private rows immediately when the input changes; settings hidden rows and accessible download projections are remembered.
- Video trim grips claim down/movement directly, avoiding LazyRow touch-slop competition. Both yellow grips have a black vertical mark. Stable source scale, reversible bounds and release collapse are preserved.
- During trim the source-boundary decoder owns preview; no per-pointer ExoPlayer seeks or playlist rebuilds. Final playlist/seek is installed on release. Player failures now log full exception causes/errorCodeName. The specific device MediaCodec failure is NOT proven resolved without another device run; no codec/export implementation or software decoder substitute was introduced.
- Editor waveform cache misses decode in an IO child job through the existing single shared decode gate, with cancellation; no wait for WorkManager scheduling/polling. The timeline shows loading/failure instead of an unexplained blank region. No invented waveform data. Bulk idle-only preparation remains.

Verification scope: selected trim gestures (including <touch-slop movement), queue ordering/linear traversal/privacy, waveform cache/rendering and source-decoder lifetime; new settings projection test checks immediate privacy changes. No full suite or native export matrix repeated. Device gesture feel/waveform codec support/performance and MediaCodec fix acceptance remain NOT_DEVICE_TESTED until the next APK run.

QA signing: subsequent deliveries must re-sign downloaded CI APKs with the same persistent PRIVATE local QA key outside the repository. GitHub secret-write was rejected403 by integration permissions, so automatic secret provisioning is unavailable. No keys/key bytes/base64 are stored here or in logs/PR. This QA certificate is distinct from official DomEnota signing. Transition from older ephemeral CI certificates requires preserving local data before reinstalling; do not silently recommend deleting user data.

Selected20tests PASS (4m54s including compile); earlier duplicate-reference ordering regression failed, then was corrected with first-ordinal preservation. No failed tests remain in this selected run. Final delivery CI compiles the exact committed source, including the release-only seek path.
