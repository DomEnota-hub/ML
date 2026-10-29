# MediaLoader 1.7.42 (104) — Instagram rate limit and source icons

- Instagram API responses with HTTP 400 and `spam: true` now report a rate limit. The profile scan stops rather than treating this as an expired session.
- Settings no longer launches an Instagram account probe on every resume. The explicit «Проверить» action remains available.
- VK community download uses a blue Groups icon. Instagram profile download uses a pink PhotoCamera icon.

The user's 1.7.41 log shows HTTP 429 on profile metadata, HTTP 302 on the web feed request, and HTTP 400 `spam: true` on the fallback feed. Automatic profile scan remains blocked by Instagram and requires a different collection route.
