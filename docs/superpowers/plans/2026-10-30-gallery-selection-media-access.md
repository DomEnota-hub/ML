# Gallery multiple selection and one-time media-management access

User explicitly requests implementation, minimal verification, previous authorization
for release/upload persists. Existing deletion destination semantics retained.

- Long press selects a gallery thumbnail; taps toggle more; select all uses current
  source/album/search results; back cancels selection before leaving album.
- Persistent top selection toolbar: cancel, count, select all/deselect all, red delete.
- Selection is pruned on deleted/filtered rows. Cancellation of deletion keeps selection.
- Root captures selected IDs/URIs as a saveable request, so result handling survives
  rotation and a provider notification removing deleted rows before consent returns.
- One batched MediaStore delete request for selected phone items (chunks of <=2000
  for platform limits). App-history items use existing trash; completed work is tracked
  for retries. Mixed selections without special access must get local consent for app
  trash plus Android consent for phone items; this fallback is a platform limitation.
- Optional first-entry explanation on Android12+ offers ACTION_REQUEST_MANAGE_MEDIA;
  record user choice once, never imply app can grant it. Settings entry remains for retry.
  Granted access means one app confirmation and no interactive MediaStore consent.
- This is media access, not arbitrary/all-files access or access to SAF cloud providers.
- Verify only selection flow, batched request and permission policy, then arm64 release.


## Completed / published

- Five distinct focused cases passed: MediaDeletionPolicyTest4 (confirmation policy,
  mixed-selection consent, URI batches and dedup, 11000-item saved-state round trip
  under128KiB); GalleryMultipleSelectionTest1 (long press, second item, one ordered
  batch callback, select all and cancel, no accidental viewer open).
- Initial UI test could not resolve ComponentActivity in the release manifest;
  switched only its harness to Robolectric.buildActivity + createEmptyComposeRule.
  The corrected single UI case passed; the four passing policy cases were not repeated.
- Final command /tmp/release-1.7.101-package.log: BUILD SUCCESSFUL in3m6s,
  61 tasks (15 executed,46 up-to-date). No full suite or emulator.
- APK version1.7.101/code163, arm64, 103650367bytes.
- SHA256 c42532b58839cca63ffb16f19e14f22a156c3c4a28045389f68473d09fcef9ac
- zipalign-P16 and apksigner verification passed; certificate matches1.7.100.
- APK /workspace/releases/MediaLoader_1.7.101/MediaLoader_1.7.101.apk
- Drive https://drive.google.com/file/d/1AsBD-yD19B3IGrh7YhaZuJwNfNqsJG55/view?usp=drivesdk
