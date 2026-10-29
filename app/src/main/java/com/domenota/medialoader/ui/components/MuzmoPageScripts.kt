package com.domenota.medialoader.ui.components

/** DOM probes are deliberately scoped to Muzmo; they never run on other provider pages. */
internal object MuzmoPageScripts {
    // Search items navigate to /info?id=N. The page's download arrow is not an MP3 link.
    // Heuristic parsing is logged and requires verification on an Android device.
    val SEARCH = """
        (function() {
          function absolute(raw) { try { return new URL(raw, location.href).href; } catch (_) { return ''; } }
          function clock(raw, secondsAllowed) {
            var text = String(raw || '').trim();
            var iso = text.match(/\bPT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?\b/i);
            if (iso && (iso[1] || iso[2] || iso[3])) {
              var total = (parseInt(iso[1] || '0', 10) * 3600) +
                (parseInt(iso[2] || '0', 10) * 60) + parseInt(iso[3] || '0', 10);
              if (total > 0 && total < 86400) {
                return Math.floor(total / 60) + ':' + String(total % 60).padStart(2, '0');
              }
            }
            var time = text.match(/(?:^|[^\d])(\d{1,2}:\d{2}(?::\d{2})?)(?!\d)/);
            if (time) return time[1];
            if (secondsAllowed && /^\d{1,5}$/.test(text)) {
              var seconds = parseInt(text, 10);
              if (seconds > 0 && seconds < 86400)
                return Math.floor(seconds / 60) + ':' + String(seconds % 60).padStart(2, '0');
            }
            return '';
          }
          function durationIn(node) {
            var selectors = '[class*="duration"], [class*="time"], [class*="length"], time, ' +
              '[itemprop="duration"], [data-duration], [data-time], [data-length]';
            var candidates = [node].concat(Array.prototype.slice.call(node.querySelectorAll(selectors), 0, 24));
            for (var k = 0; k < candidates.length; k++) {
              var el = candidates[k];
              if (!el || typeof el.getAttribute !== 'function') continue;
              var attrs = ['data-duration', 'data-time', 'data-length', 'datetime', 'content', 'aria-label', 'title'];
              for (var a = 0; a < attrs.length; a++) {
                var value = clock(el.getAttribute(attrs[a]), attrs[a].indexOf('data-') === 0);
                if (value) return value;
              }
              var visible = clock(el.innerText || el.textContent || '', false);
              if (visible) return visible;
            }
            return clock(node.innerText || node.textContent || '', false);
          }
          var anchors = document.querySelectorAll('a[href*="/info?id="]');
          var output = [], seen = {};
          for (var i = 0; i < anchors.length; i++) {
            var anchor = anchors[i], url = absolute(anchor.getAttribute('href'));
            if (!/^https:\/\/rmr\.muzmo\.cc\/info\?/.test(url) || seen[url]) continue;
            var id = new URL(url).searchParams.get('id') || '';
            if (!/^[0-9]+$/.test(id)) continue;
            var node = anchor, chosen = anchor;
            for (var j = 0; j < 6 && node.parentElement && node.parentElement !== document.body; j++) {
              var parent = node.parentElement, value = (parent.innerText || '').trim();
              if (parent.querySelectorAll('a[href*="/info?id="]').length > 1 ||
                  value.length > 300) break;
              node = parent;
              if (value.length > 1) chosen = node;
            }
            var visible = (chosen.innerText || anchor.getAttribute('title') || '').trim();
            var lines = visible.split(/\n+/).map(function(s) { return s.trim(); })
              .filter(function(s) {
                return s && !/^(?:\d{1,2}:\d{2}|\d+\s*kbps|скачать|скачать оригинал)$/i.test(s);
              });
            var artist = '', title = '', duration = '';
            var singer = chosen.querySelector('[class*="artist"],[class*="singer"],[itemprop="byArtist"]');
            var song = chosen.querySelector('[class*="title"],[class*="track-name"],[itemprop="name"]');
            if (singer) artist = (singer.innerText || '').trim();
            if (song) title = (song.innerText || '').trim();
            if (!artist || !title) {
              if (lines.length >= 2) {
                artist = artist || lines[0];
                title = title || lines[1];
              } else if (lines.length === 1) {
                var parts = lines[0].match(/^(.+?)\s+[-–—]\s+(.+)$/);
                if (parts) { artist = artist || parts[1]; title = title || parts[2]; }
              }
            }
            // Muzmo's title and download icon both link to the same /info page.
            // The clock lives in a sibling .song-time cell of their .item-song row.
            var songRow = typeof anchor.closest === 'function' ? anchor.closest('.item-song') : null;
            var timeCell = songRow && songRow.querySelector('.song-time');
            duration = clock(timeCell && (timeCell.innerText || timeCell.textContent), false);
            if (!duration) {
              var durationNode = chosen;
              for (var d = 0; d < 8 && durationNode.parentElement && durationNode.parentElement !== document.body; d++) {
                var outer = durationNode.parentElement;
                if (outer.querySelectorAll('a[href*="/info?id="]').length !== 1 ||
                    (outer.textContent || '').length > 2000) break;
                durationNode = outer;
              }
              duration = durationIn(durationNode);
            }
            if (!artist || !title || artist.length > 120 || title.length > 160) continue;
            seen[url] = true;
            output.push({artist: artist, title: title, infoUrl: url, duration: duration});
          }
          var plain = ((typeof document.querySelector === 'function' && document.querySelector('.search-empty, .empty-results, .not-found')) || document.body);
          var emptyText = (plain && plain.innerText || '').slice(0, 5000);
          var explicitEmpty = output.length === 0 &&
            /(?:ничего не найдено|нет результатов|по вашему запросу ничего|no results found)/i.test(emptyText);
          return JSON.stringify({
            tracks: output, links: anchors.length,
            readyState: document.readyState, explicitEmpty: explicitEmpty
          });
        })();
    """.trimIndent()

    // Bounded structural probe for the user's extended journal. It records no URLs,
    // song names, page HTML or attribute values; only ancestor boundaries and clock text.
    val SEARCH_DIAGNOSTIC = """
        (function() {
          var anchor = document.querySelector('a[href*="/info?id="]');
          if (!anchor) return JSON.stringify({anchors: 0});
          var chain = [], node = anchor;
          for (var depth = 0; node && node !== document.body && depth < 10; depth++, node = node.parentElement) {
            var text = node.innerText || '';
            var clocks = text.match(/(?:^|[^\d])\d{1,2}:\d{2}(?::\d{2})?(?!\d)/g) || [];
            var infoLinks = node.querySelectorAll('a[href*="/info?id="]');
            var distinct = {};
            for (var i = 0; i < infoLinks.length; i++) {
              var href = infoLinks[i].getAttribute('href') || '';
              var id = href.match(/[?&]id=(\d+)/);
              if (id) distinct[id[1]] = true;
            }
            chain.push({
              depth: depth,
              tag: String(node.tagName || '').slice(0, 20),
              className: String(node.className || '').slice(0, 100),
              infoLinks: infoLinks.length,
              distinctInfoIds: Object.keys(distinct).length,
              clocks: clocks.slice(0, 4).map(function(s) { return s.trim(); }),
              textLength: text.length
            });
          }
          return JSON.stringify({chain: chain, readyState: document.readyState});
        })();
    """.trimIndent()

    // Do not invent a /get/music/ filename from the numeric ID: read the real link.
    val DETAIL = """
        (function() {
          var anchors = document.querySelectorAll('a[href*="/get/music/"]');
          for (var i = 0; i < anchors.length; i++) {
            try {
              var link = new URL(anchors[i].getAttribute('href'), location.href);
              if (link.protocol === 'https:' && link.hostname === 'rmr.muzmo.cc' &&
                  /^\/get\/music\/[0-9]{8}\/[^/]+\.mp3$/i.test(link.pathname)) {
                return JSON.stringify({downloadUrl: link.href, readyState: document.readyState});
              }
            } catch (_) {}
          }
          return JSON.stringify({downloadUrl: '', readyState: document.readyState});
        })();
    """.trimIndent()
}
