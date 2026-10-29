# Grid viewer and player controls

Implement approved items 1–6 only. New Downloader/Gallery/Music modes are deferred.

- Public Downloads and Personal grids open a photo/video sequence matching visible grid order and filters, across groups. Exclude audio, unfinished, hidden and unavailable files; never cross privacy boundary. Keep underlying navigation entry and scroll state. Stop video when leaving it. Explicit previous/next buttons preserve horizontal video seeking.
- Open lock represents available controls; closed lock represents locked controls. No dark background; keep 48dp touch target at right center.
- Volume can traverse 0–100% in one drag, preserving Android limits.
- Vertical gestures start in outer 15% strips. Seek starts in lower half of full video viewport in both screen modes. Origin stays fixed throughout drag, including outside its zone; threshold and diagonal rejection remain.
- Individual file menus receive consistent rounded icons including favorite, folder, open, share, private move/restore, source, rename, music search/recognition, retry/cancel/delete. Delete icon and label use error color.
