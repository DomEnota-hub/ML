# MediaLoader 1.7.41 (103) — Instagram profile feed response

- The Instagram profile scanner records the HTTP status and first 600 characters of an API error response, and the status and redirect destination of its web feed request. This exposes the server's reason for the current feed failure on the user's device.
- RUTUBE account status uses «Подключен».

Profile feed access remains subject to an on-device test; the previous log reported an access failure for the web route and HTTP 400 for the fallback API.
