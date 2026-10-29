# Личное

User-approved: files moved into a Personal section remain playable/viewable in this
app and disappear from public gallery storage. First entry chooses NONE, PIN or
BIOMETRIC. Protection can later change in private settings. Implement and release
with hidden source links for music providers and more visible light filename field.

## Design decisions

- Files reside in Android app-internal private-media storage; no claim of a custom
encrypted vault. PIN/OS authentication controls in-app access on top of Android sandbox.
- Own six-digit PIN: salted PBKDF2 verifier, persistent retry throttling. Biometrics
uses real OS prompt with secure device credential fallback. Mode changes require
reauthentication. No saved authorization across process recreation.
- Lock on app background or leaving private navigation; clear private playback and
hide private routes/items immediately. Private screen/editor use FLAG_SECURE.
- Private items excluded from public history, folders/favorites lists, home, trash
and ordinary playback queues. Public history clearing preserves private DB rows.
- Copy and verify before deleting public source. Persist pending public-source URI
before deletion; failed public cleanup is visible and retryable in Personal.
- Returning to downloads is the explicit publication action. Private external sharing,
external player/floating window are unavailable until returned; derived edits stay private.
- Internal files disappear on app uninstall/clear data; existing external/cloud copies
are outside the move's scope. Setup explains data lifetime and PIN recovery limitation.

## Existing implementation

HistoryDatabase version11; add version12 isPrivate/privateSourceUri fields. Existing
viewer/editor support file URIs. Data operations serialize with music postprocessing.
No extra dependency required: platform auth, Java crypto and existing Room/Compose.
