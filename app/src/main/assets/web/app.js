/*
 * MusicD Share Card — the page.
 *
 * Copyright (c) 2026 Lewis Menzies (Music Duck / MusicD)
 * Released under the MIT License. See the LICENSE file for details.
 *
 * One job: ask the server what is playing, draw the card, and offer it.
 *
 * THE CARD IS DRAWN, NOT FETCHED. sharecard.js renders it into a canvas in
 * whatever browser is showing this page — the phone, the tablet, the FiiO
 * itself. The server sends JSON and a cover; the picture is made here. That is
 * why the same card comes out of an Android WebView and out of Safari across
 * the room, and why the app needs no image encoder of its own.
 *
 * WHAT "COPY" MEANS ON EACH PLATFORM, because the three differ and two of them
 * cannot be papered over:
 *
 *   iOS / iPadOS (Safari)  press and hold the card. The system menu offers
 *                          Copy, Save to Photos and Share, and it is better
 *                          than anything this page could draw. There is no
 *                          Copy BUTTON on iOS because ClipboardItem with an
 *                          image is not available to it; a button that failed
 *                          would be worse than the gesture that works.
 *   Android (this app)     Share. An image reaches the Android clipboard as a
 *                          content:// URI and the app doing the pasting holds
 *                          no grant against our FileProvider, so a copy
 *                          reports success and pastes nothing. Share leads
 *                          everywhere a copy would have.
 *   Desktop browsers       Copy writes a real PNG to the clipboard, and
 *                          Download saves it.
 *
 * Each control is feature-detected and simply not drawn where it cannot work.
 */

(() => {
  "use strict";

  const stage    = document.getElementById("stage");
  const nowEl    = document.getElementById("now");
  const actions  = document.getElementById("actions");
  const hintEl   = document.getElementById("hint");
  const errEl    = document.getElementById("err");
  const zoneSel  = document.getElementById("zone");
  const zoneWrap = document.getElementById("zone-wrap");
  const updateEl = document.getElementById("update");
  const linksEl  = document.getElementById("links");
  const refresh  = document.getElementById("refresh");

  /*
   * Bumped on every load(), so a late redraw cannot land on a card the user
   * has since replaced by picking another room or pressing Refresh. Without it
   * a slow blurb for the previous album repaints over the new card.
   */
  let token = 0;

  /** The card currently on screen, for the action buttons to hand over. */
  let current = null;

  /** Configured webhooks, masked — the page never sees a webhook URL. */
  let webhooks = [];

  /** The PIN, when this page is the one running on the device itself. */
  let setup = { onDevice: false, mayConfigure: false, pin: null };

  /** A source asking to be let in, which outranks any tip the page would show. */
  let noticeText = "";

  const isIOS = /iP(hone|ad|od)/.test(navigator.platform || "") ||
    (navigator.userAgent.includes("Mac") && "ontouchend" in document);

  // --------------------------------------------------------------- helpers

  function show(html) { stage.innerHTML = html; }

  function busy(on) {
    refresh.classList.toggle("spinning", on);
    refresh.disabled = on;
  }

  function spinner(message) {
    show(`<div class="placeholder"><div class="spinner"></div><div>${message}</div></div>`);
  }

  function message(text) {
    show(`<div class="placeholder"><div>${text}</div></div>`);
  }

  function icon(name) {
    const paths = {
      share: '<path d="M4 12v7a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-7"/><path d="M16 6l-4-4-4 4"/><path d="M12 2v14"/>',
      copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
      download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M7 10l5 5 5-5"/><path d="M12 15V3"/>',
      search: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/>',
      send: '<path d="M22 2L11 13"/><path d="M22 2l-7 20-4-9-9-4 20-7z"/>',
      /*
       * Feather's settings icon, verbatim.
       *
       * What was here was a hand-shortened copy of it — 1.6 where the
       * arcs need 1.65, .1 where they need .06 — and those arcs are
       * large-arc sweeps, so rounding them turned the teeth into loops.
       * It drew a flower. Do not retype this path; copy it.
       */
      cog: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-2 2 2 2 0 0 1-2-2v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83 0 2 2 0 0 1 0-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1-2-2 2 2 0 0 1 2-2h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 0-2.83 2 2 0 0 1 2.83 0l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 2-2 2 2 0 0 1 2 2v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 0 2 2 0 0 1 0 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 2 2 2 2 0 0 1-2 2h-.09a1.65 1.65 0 0 0-1.51 1z"/>'
    };
    return `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
      stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name]}</svg>`;
  }

  function button(cls, label, name) {
    const b = document.createElement("button");
    b.className = cls;
    b.innerHTML = icon(name) + "<span>" + label + "</span>";
    return b;
  }

  function blobToDataUrl(blob) {
    return new Promise((resolve, reject) => {
      const r = new FileReader();
      r.onload = () => resolve(String(r.result || ""));
      r.onerror = () => reject(r.error || new Error("could not read the card"));
      r.readAsDataURL(blob);
    });
  }

  /*
   * The webfont has to be IN before the card is drawn.
   *
   * Canvas text does not wait for a font: measureText and fillText both answer
   * immediately with whatever is loaded, so drawing too early lays the whole
   * card out in the fallback face and every line breaks in the wrong place.
   * The failure is silent and only visible next to a card drawn later.
   */
  async function ensureFont() {
    if (!document.fonts || !document.fonts.load) return;
    try {
      await Promise.all([
        document.fonts.load('700 42px Manrope'),
        document.fonts.load('400 28px Manrope'),
        document.fonts.load('700 16px Manrope'),
        document.fonts.load('400 22px Manrope')
      ]);
      await document.fonts.ready;
    } catch (e) {
      // A device with no route to Google Fonts still gets a card, drawn in the
      // system sans. Refusing to draw one would be the worse answer.
    }
  }

  async function getJson(url) {
    const response = await fetch(url, { cache: "no-store" });
    if (!response.ok) {
      let detail = "";
      try { detail = (await response.json()).error || ""; } catch (e) { /* no body */ }
      throw new Error(detail || ("The card server answered " + response.status + "."));
    }
    return response.json();
  }

  // ----------------------------------------------------------------- zones

  async function loadZones(force) {
    let data;
    try {
      data = await getJson("/api/zones" + (force ? "?refresh=1" : ""));
    } catch (e) {
      return;
    }
    const zones = data.zones || [];
    // One room is not a choice. Showing a picker with a single entry invites
    // somebody to look for the setting that is missing.
    if (zones.length < 2) {
      zoneWrap.classList.add("hidden");
      return;
    }
    const chosen = zoneSel.value || data.selected || "";
    zoneSel.innerHTML = "";
    const auto = document.createElement("option");
    auto.value = "";
    auto.textContent = "Whatever’s playing";
    zoneSel.appendChild(auto);
    // Two sources can see the same room and answer differently — Roon playing
    // to a Sonos speaker is exactly that — so the caption has to say which is
    // which whenever more than one source is present.
    const manySources = new Set(zones.map((z) => z.source)).size > 1;
    for (const zone of zones) {
      const option = document.createElement("option");
      option.value = zone.uid;
      option.textContent = manySources && zone.source
        ? zone.name + " (" + zone.source + ")"
        : zone.name;
      zoneSel.appendChild(option);
    }
    // Only restore a room the user actually chose. Re-selecting whatever the
    // server last answered with would quietly pin the picker to one room after
    // the first card, and "Whatever's playing" would stop being the default.
    if (chosen && zones.some((z) => z.uid === chosen)) zoneSel.value = chosen;
    zoneWrap.classList.remove("hidden");
  }

  // ------------------------------------------------------------- the card

  async function load(force) {
    const mine = ++token;
    current = null;
    actions.innerHTML = "";
    linksEl.innerHTML = "";
    linksEl.classList.add("hidden");
    hintEl.textContent = "";
    errEl.textContent = "";
    nowEl.textContent = "";
    noticeText = "";
    busy(true);
    spinner("Looking for what’s playing…");

    try {
      await loadZones(force);
      await loadWebhooks();
      if (mine !== token) return;

      const params = new URLSearchParams();
      if (zoneSel.value) params.set("zone", zoneSel.value);
      if (force) params.set("refresh", "1");
      const playing = await getJson("/api/now-playing?" + params);
      if (mine !== token) return;

      if (!playing.album && !playing.artist) {
        message(playing.reason || "Nothing is playing.");
        var notices = playing.notices || [];
        var noPlayers = !playing.reason || playing.reason.indexOf("No players") === 0;
        // A source asking to be let in is not a failure, and must not be
        // buried under a network troubleshooter — a first Roon run needs one
        // tap in Roon and nothing else.
        hintEl.textContent = notices.length ? notices.join(" ")
          : noPlayers
            ? "The app cannot see any players. Run the check below to find out why."
            : "Start something playing, then press refresh.";
        // A dead end with no next step is what made the first failure so hard
        // to act on: the app knew far more than it was saying.
        if (noPlayers && !notices.length) offerDiagnostics();
        return;
      }
      noticeText = (playing.notices && playing.notices.length)
        ? playing.notices.join(" ") : "";
      if (noticeText) hintEl.textContent = noticeText;

      spinner("Building the card…");
      await ensureFont();
      if (mine !== token) return;

      await draw(mine, playing);
    } catch (e) {
      if (mine !== token) return;
      message("Could not build the card.");
      errEl.textContent = (e && e.message) ? e.message : String(e);
    } finally {
      if (mine === token) busy(false);
    }
  }

  /*
   * Draw once from what is already known, then redraw if the slow lookup adds
   * something.
   *
   * The extras — release year, blurb, Pitchfork score — are a chain of requests
   * to MusicBrainz, Wikipedia and Pitchfork behind a rate gate, and waiting on
   * them before drawing anything is several seconds of spinner for a
   * four-digit number. So `fast=1` answers from the server's caches without
   * opening a socket, the card goes up, and the full lookup runs unawaited.
   */
  async function draw(mine, playing) {
    const album  = playing.album  || "";
    const artist = playing.artist || "";
    const params = new URLSearchParams({ album: album, artist: artist });

    let fast = EMPTY;
    try {
      fast = extrasOf(await getJson("/api/extras?fast=1&" + params));
    } catch (e) { /* a card with no extras is still a card */ }
    if (mine !== token) return;

    const wantSlow = !fast.release || !fast.bio;
    const slow = !wantSlow ? null :
      getJson("/api/extras?" + params).then(extrasOf).catch(() => EMPTY);

    /*
     * A SHORT HEAD START, NOT A WAIT. A blurb makes the card grow downward, so
     * one that lands a beat after the first paint pushes the card taller while
     * somebody is looking at it. Give the lookup a moment to win the race and
     * the card is drawn once, at its final size — and past that, a card that
     * reflows beats a spinner that is still up.
     */
    let painted = fast;
    if (slow) {
      const headStart = await Promise.race([
        slow,
        new Promise((res) => setTimeout(() => res(null), 700))
      ]);
      if (mine !== token) return;
      if (headStart) painted = merge(fast, headStart);
    }

    await paint(mine, playing, painted);

    if (slow) {
      slow.then((full) => {
        if (mine !== token) return;
        // Redraw only if the slow answer adds something to what is ALREADY on
        // screen — after a won head start that is usually nothing, and a card
        // that reflows for no visible change is worse than one that doesn't.
        const better = (full.release && full.release !== painted.release) ||
                       (full.bio && full.bio !== painted.bio) ||
                       (full.score != null && full.score !== painted.score) ||
                       // A review found on the slow path adds a link even when
                       // the score is unchanged, and the links are drawn by
                       // paint() — without this the chip never appears.
                       (full.reviewUrl && full.reviewUrl !== painted.reviewUrl);
        if (!better) return;
        return paint(mine, playing, merge(painted, full));
      }).catch(() => { /* the card without it is already up */ });
    }
  }

  const EMPTY = {
    release: "", bio: "", bioSource: "", score: null, isBestNewMusic: false,
    reviewUrl: "", links: []
  };

  function extrasOf(j) {
    return {
      release: j && j.release ? String(j.release) : "",
      bio: (j && j.bio) || "",
      bioSource: (j && j.bioSource) || "",
      score: j && typeof j.score === "number" ? j.score : null,
      isBestNewMusic: !!(j && j.isBestNewMusic),
      reviewUrl: (j && j.reviewUrl) || "",
      links: (j && Array.isArray(j.links)) ? j.links : []
    };
  }

  function merge(a, b) {
    return {
      release: b.release || a.release,
      bio: b.bio || a.bio,
      bioSource: b.bioSource || a.bioSource,
      score: b.score != null ? b.score : a.score,
      isBestNewMusic: b.isBestNewMusic || a.isBestNewMusic,
      reviewUrl: b.reviewUrl || a.reviewUrl,
      // The fast path already carries these — they need no lookup — so a slow
      // answer that came back empty must not wipe them.
      links: (b.links && b.links.length) ? b.links : a.links
    };
  }

  async function paint(mine, playing, extras) {
    const album  = playing.album  || "";
    const artist = playing.artist || "";

    const blob = await ShareCard.render({
      // Always this server's own path, never the speaker's: a canvas that has
      // drawn a cross-origin image cannot be read back, and toBlob would throw
      // with no picture to share. See ArtProxy on the server side.
      coverUrl: playing.art || "",
      wordmarkUrl: null,
      title: album,
      artist: artist,
      releaseRaw: extras.release,
      bio: extras.bio,
      bioSource: extras.bioSource,
      score: extras.score,
      isBestNewMusic: extras.isBestNewMusic
    });
    if (mine !== token) return;

    const dataUrl = await blobToDataUrl(blob);
    if (mine !== token) return;

    const alt = "Share card for " + (album || "this record") +
      (artist ? " by " + artist : "");
    show('<img src="' + dataUrl + '" alt="' + alt.replace(/"/g, "&quot;") + '">');

    current = { blob: blob, album: album, artist: artist };
    describe(playing);
    buildActions();
    buildLinks(extras);
    // Not awaited: the card is finished, and this only ever improves one chip.
    upgradeQobuz(mine, playing);
  }

  /*
   * WHERE TO HEAR IT, and the review the score came from.
   *
   * PLAIN LINKS, NO target="_blank". On Android the shell's WebViewClient
   * sends anything that is not this server through ACTION_VIEW, so the link
   * opens whichever app claims that domain and the card page is still sitting
   * there behind it. A target="_blank" would need onCreateWindow handled in
   * the chrome client and would otherwise do nothing at all — silently, which
   * is the worst kind.
   *
   * On iOS the same https link is a Universal Link: it hands off to the app
   * without moving Safari off this page, and falls through to the web player
   * when the app is not installed. That fallback is the reason none of these
   * is a spotify:// or qobuz:// custom scheme.
   *
   * A PRE-FILLED SEARCH, mostly. Five of the six go to the service's own
   * search rather than the album's page, because that would need each
   * service's own id for it — so the row is headed "Find it on" rather than
   * promising more than it delivers. Qobuz is the exception once its id
   * arrives (see upgradeQobuz) and Pitchfork is set apart entirely: that link
   * is the review itself.
   */
  function buildLinks(extras) {
    linksEl.innerHTML = "";
    const services = (extras && extras.links) || [];
    const review = extras && extras.reviewUrl;
    if (!services.length && !review) {
      linksEl.classList.add("hidden");
      return;
    }

    const label = document.createElement("p");
    label.className = "links-label";
    label.textContent = review ? "Read about it, or find it on" : "Find it on";
    linksEl.appendChild(label);

    if (review) {
      linksEl.appendChild(link(review, "Pitchfork review", "links-review"));
    }
    for (const svc of services) {
      if (!svc || !svc.url || !svc.name) continue;
      const a = link(svc.url, svc.name, "");
      // Marked so the Qobuz one can be upgraded in place when its album id
      // arrives; see upgradeQobuz.
      if (svc.service) a.dataset.service = svc.service;
      linksEl.appendChild(a);
    }
    linksEl.classList.remove("hidden");
  }

  /*
   * QOBUZ, THE ONE THAT NEEDS A SECOND LOOKUP.
   *
   * The search link lands on the Qobuz download store's search page and never
   * opens the app, because there IS no search route on the host the app claims
   * — only /album/<id>. So the id is fetched separately, off Qobuz's own public
   * search page, and the chip's href is swapped for an open.qobuz.com link
   * when it arrives. The LABEL does not change: the tap lands on the record
   * either way, and on the search only when Qobuz has never heard of it.
   *
   * SEPARATE FROM THE CARD, DELIBERATELY. That lookup is rate-gated to one
   * request every second and a half; folding it into /api/extras would hold the
   * whole card back for a link. A record Qobuz does not carry simply never
   * upgrades, which is the honest outcome — a wrong album would be worse than
   * the search page.
   */
  async function upgradeQobuz(mine, playing) {
    const chip = linksEl.querySelector('[data-service="qobuz"]');
    if (!chip) return;
    const params = new URLSearchParams();
    params.set("album", playing.album || "");
    if (playing.artist) params.set("artist", playing.artist);
    try {
      const data = await getJson("/api/qobuz?" + params);
      if (mine !== token || !data || !data.url) return;
      // The chip may have been rebuilt while that was in flight.
      const now = linksEl.querySelector('[data-service="qobuz"]');
      if (!now) return;
      // The href changes and the label does not. It said "Open in Qobuz"
      // here, which made one chip in the row shout while the other five did
      // not, for a difference nobody has to care about: the tap lands on the
      // record either way, and on the search only when Qobuz has never heard
      // of it.
      now.href = data.url;
    } catch (e) {
      // No upgrade is a fine outcome: the search link is still there.
    }
  }

  function link(href, text, className) {
    const a = document.createElement("a");
    a.href = href;
    a.rel = "noreferrer";
    a.className = className;
    a.textContent = text;
    return a;
  }

  function describe(playing) {
    const room = playing.zone && playing.zone.name ? playing.zone.name : "";
    const verb = playing.playing ? "Playing in" : "Last played in";
    const bits = [];
    if (room) bits.push(verb + " <b>" + escapeHtml(room) + "</b>");
    // Which source answered. Worth saying: it is the difference between a card
    // Roon described and one the speaker guessed at.
    if (playing.source) bits.push("via " + escapeHtml(playing.source));
    if (playing.stream) bits.push("live stream");
    nowEl.innerHTML = bits.join(" · ");
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, (c) => (
      { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
    ));
  }

  // --------------------------------------------------------------- actions

  function fileName() {
    const part = (s, fallback) =>
      (s || fallback).replace(/[^a-z0-9]+/gi, "_").replace(/^_+|_+$/g, "") || fallback;
    return part(current.artist, "artist") + "-" + part(current.album, "album") + ".png";
  }

  function buildActions() {
    actions.innerHTML = "";
    const name = fileName();

    /*
     * Share is offered only when the browser will actually take a FILE.
     *
     * navigator.share exists on plenty of browsers that can only share a URL,
     * and handing one a file there throws at the moment of use rather than at
     * the moment of detection. canShare with a real probe File is the only
     * honest test — and it is what the Android bridge answers true to.
     */
    let canShare = false;
    try {
      canShare = !!(navigator.share && navigator.canShare &&
        navigator.canShare({ files: [new File([new Uint8Array([0])], "p.png", { type: "image/png" })] }));
    } catch (e) { canShare = false; }

    if (canShare) {
      const b = button("primary", "Share…", "share");
      b.onclick = async () => {
        errEl.textContent = "";
        try {
          await navigator.share({ files: [new File([current.blob], name, { type: "image/png" })] });
        } catch (e) {
          // Dismissing the share sheet is not an error worth printing.
          if (e && e.name !== "AbortError") errEl.textContent = e.message || String(e);
        }
      };
      actions.appendChild(b);
    }

    /*
     * Copy, only where an IMAGE can really go on the clipboard.
     *
     * Android's WebView has both ClipboardItem and clipboard.write and cannot
     * do this: the image arrives as a content:// URI that the pasting app has
     * no grant to read, so the write resolves and nothing is pasted. The
     * Android shell therefore leaves both absent deliberately, and this detect
     * then removes the button rather than drawing one that lies.
     */
    if (window.ClipboardItem && navigator.clipboard && navigator.clipboard.write) {
      const b = button("", "Copy", "copy");
      b.onclick = async () => {
        errEl.textContent = "";
        try {
          await navigator.clipboard.write([new ClipboardItem({ "image/png": current.blob })]);
          flash(b, "Copied");
        } catch (e) {
          errEl.textContent = "Could not copy: " + (e.message || String(e));
        }
      };
      actions.appendChild(b);
    }

    // A download anchor is removed outright by the Android shell — scoped
    // storage makes it a no-op there — so its absence in the app is expected.
    const a = document.createElement("a");
    a.className = "";
    a.href = URL.createObjectURL(current.blob);
    a.download = name;
    a.innerHTML = icon("download") + "<span>Download</span>";
    actions.appendChild(a);

    // One button per configured webhook, then the way to add more.
    for (const hook of webhooks) {
      const b = button("", hook.name, "send");
      b.onclick = () => postTo(hook, b);
      actions.appendChild(b);
    }
    const cog = button("", webhooks.length ? "Webhooks" : "Add a Discord webhook", "cog");
    cog.onclick = showWebhookSettings;
    actions.appendChild(cog);

    // A notice from a source — "enable this extension in Roon" — outranks the
    // iOS tip and must NOT be cleared here. This line used to assign
    // unconditionally, so on Android it wiped the notice a moment after it was
    // set and a first Roon run looked like an app that simply ignored Roon.
    if (noticeText) {
      hintEl.textContent = noticeText;
    } else if (isIOS) {
      hintEl.textContent = "Press and hold the card to copy it, save it to Photos or share it.";
    } else {
      hintEl.textContent = "";
    }
  }

  /*
   * The "why can't it see my speakers" button.
   *
   * Only drawn when discovery has actually failed, because it runs a real scan
   * and takes several seconds — it is a thing to reach for when stuck, not a
   * control to have sitting on a working page.
   */
  function offerDiagnostics() {
    actions.innerHTML = "";
    var b = button("primary", "Find my speakers", "search");
    b.onclick = async () => {
      errEl.textContent = "";
      b.disabled = true;
      var span = b.querySelector("span");
      if (span) span.textContent = "Looking…";
      try {
        report(await getJson("/api/debug"));
      } catch (e) {
        errEl.textContent = (e && e.message) ? e.message : String(e);
      } finally {
        b.disabled = false;
        if (span) span.textContent = "Run it again";
      }
    };
    actions.appendChild(b);
  }

  function report(d) {
    var rows = [];
    rows.push("<p class=\"diag-advice\">" + escapeHtml(d.advice || "") + "</p>");

    function section(title, items) {
      if (!items || !items.length) return;
      rows.push("<h3>" + escapeHtml(title) + "</h3><ul>" +
        items.map((i) => "<li>" + escapeHtml(String(i)) + "</li>").join("") + "</ul>");
    }
    if (d.notices && d.notices.length) {
      rows.push('<p class="diag-advice">' + escapeHtml(d.notices.join(" ")) + "</p>");
    }
    section("The app", d.app);
    // What Pitchfork was asked, and what it said. A missing score looks
    // identical to a missing review from the card.
    section("Album reviews", d.reviews);
    // Each source in its own words. Roon's line is where "not approved yet"
    // appears, and that is not a network problem however much it looks like one.
    section("Sources", d.sources);
    section("This device's networks", d.interfaces);
    section("Multicast (SSDP)", (d.ssdp && d.ssdp.notes) || []);
    section("Direct scan of this subnet", (d.scan && d.scan.notes) || []);
    section("Addresses being tried", d.hosts);
    section("What the players said", d.errors);
    if (d.zones && d.zones.length) {
      section("Rooms", d.zones.map((z) =>
        z.name + " (" + z.ip + ") — " + z.state +
        (z.album ? ": " + z.album + (z.artist ? " by " + z.artist : "") : "")));
      // What each player actually reported, verbatim. This is the section to
      // send on when a card comes out wrong for one source and right for
      // another.
      for (const z of d.zones) {
        if (z.raw && z.raw.length) section("Raw reply — " + z.name, z.raw);
      }
    }
    hintEl.innerHTML = "";
    nowEl.innerHTML = "";
    // The links belonged to a card that is no longer on screen.
    linksEl.innerHTML = "";
    linksEl.classList.add("hidden");
    show("<div class=\"diag\">" + rows.join("") + "</div>");
  }

  /*
   * POSTING.
   *
   * The PNG goes to this app's server, which forwards it to Discord. The page
   * never holds the webhook URL — that is the whole point. See DiscordPoster
   * for why the post happens server-side rather than here.
   */
  async function postTo(hook, b) {
    errEl.textContent = "";
    const span = b.querySelector("span");
    const was = span ? span.textContent : "";
    b.disabled = true;
    if (span) span.textContent = "Posting…";
    try {
      const response = await fetch(
        "/api/webhooks/" + encodeURIComponent(hook.id) + "/post",
        { method: "POST", body: current.blob, headers: { "Content-Type": "image/png" } }
      );
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.error || ("Discord said " + response.status));
      if (span) span.textContent = "Posted";
      setTimeout(() => { if (span) span.textContent = was; }, 1800);
    } catch (e) {
      if (span) span.textContent = was;
      errEl.textContent = (e && e.message) ? e.message : String(e);
    } finally {
      b.disabled = false;
    }
  }

  async function loadWebhooks() {
    try {
      const data = await getJson("/api/webhooks");
      webhooks = data.webhooks || [];
      setup.mayConfigure = !!data.mayConfigure;
    } catch (e) {
      webhooks = [];
    }
  }

  /*
   * The webhook panel.
   *
   * A URL typed here is a credential: anyone holding it can post to that
   * channel from anywhere, forever. So it is sent once and never comes back —
   * the list below shows a mask, and there is no "show" button, because there
   * is nothing on this page that could show it.
   */
  async function showWebhookSettings() {
    errEl.textContent = "";
    try {
      setup = await getJson("/api/setup");
    } catch (e) { /* fall back to what the list said */ }
    await loadWebhooks();

    const rows = webhooks.map((h) =>
      '<li><span class="wh-name">' + escapeHtml(h.name) + "</span>" +
      '<span class="wh-mask">' +
      escapeHtml(h.username ? "as " + h.username : h.masked) + "</span>" +
      '<button class="wh-del" data-id="' + escapeHtml(h.id) + '">\u00D7</button></li>'
    ).join("");

    // COMPACT ON PURPOSE. The first version was three paragraphs of prose and
    // five stacked fields, and on a phone the Save button was below the fold —
    // a settings screen you have to scroll to finish is one people abandon
    // half-done. Every explanation here is one line, and the fields are paired
    // across the width where they are short enough to be.
    const pinField = setup.mayConfigure ? "" :
      '<input class="wh-input" id="wh-pin" inputmode="numeric" placeholder="PIN from the device">';

    const pinLine = setup.onDevice && setup.pin
      ? '<p class="wh-note">PIN for other devices: <b>' + escapeHtml(setup.pin) + "</b></p>"
      : "";

    show(
      '<div class="wh">' +
      (rows ? '<ul class="wh-list">' + rows + "</ul>" : "") +
      '<div class="wh-row">' +
      '<input class="wh-input" id="wh-name" placeholder="Channel name">' +
      '<input class="wh-input" id="wh-username" placeholder="Post as (e.g. Menzies)">' +
      "</div>" +
      '<input class="wh-input" id="wh-url" placeholder="Discord webhook URL">' +
      '<div class="wh-row">' +
      '<label class="wh-file" id="wh-pick"><span id="wh-pick-label">Choose a picture</span>' +
      '<input type="file" accept="image/*" id="wh-avatar"></label>' +
      pinField +
      "</div>" +
      '<div class="wh-buttons">' +
      '<button class="primary" id="wh-add">Save</button>' +
      '<button id="wh-back">Back</button>' +
      "</div>" +
      '<p class="wh-note">Discord tags every webhook <b>APP</b>; that cannot be turned off. ' +
      "Edit Channel \u2192 Integrations \u2192 Webhooks \u2192 Copy Webhook URL.</p>" +
      pinLine +
      "</div>"
    );
    actions.innerHTML = "";
    hintEl.textContent = "";
    nowEl.innerHTML = "";

    document.getElementById("wh-back").onclick = () => load(false);
    document.getElementById("wh-add").onclick = addWebhook;
    document.getElementById("wh-avatar").onchange = onAvatarChosen;
    for (const b of document.querySelectorAll(".wh-del")) {
      b.onclick = () => removeWebhook(b.getAttribute("data-id"));
    }
  }

  /** The chosen photo, already scaled, waiting for Save. */
  let pendingAvatar = "";

  /*
   * A PHOTO, NOT A URL.
   *
   * Discord fetches an avatar_url from its own servers, so a picture this app
   * served from a home network would be invisible to it — and a photo on a
   * phone has no URL at all. So the file is read here, scaled to 128px, and
   * sent to Discord as bytes, which it stores on its own CDN.
   *
   * Scaling happens in the browser because the page already has a canvas and
   * the server has no image decoder. A phone camera produces something several
   * thousand pixels wide; Discord wants 128.
   */
  async function onAvatarChosen(event) {
    const file = event.target.files && event.target.files[0];
    const label = document.getElementById("wh-pick-label");
    if (!file) { pendingAvatar = ""; return; }
    errEl.textContent = "";
    try {
      pendingAvatar = await squareThumbnail(file, 128);
      if (label) label.textContent = "Picture ready";
    } catch (e) {
      pendingAvatar = "";
      if (label) label.textContent = "Choose a picture";
      errEl.textContent = "Could not read that picture.";
    }
  }

  function squareThumbnail(file, size) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onerror = () => reject(reader.error || new Error("read failed"));
      reader.onload = () => {
        const img = new Image();
        img.onerror = () => reject(new Error("not an image"));
        img.onload = () => {
          const canvas = document.createElement("canvas");
          canvas.width = size;
          canvas.height = size;
          const ctx = canvas.getContext("2d");
          // Centre-crop to a square: an avatar is round, and letterboxing a
          // portrait photo into it would put bars either side of a face.
          const side = Math.min(img.width, img.height);
          ctx.drawImage(
            img, (img.width - side) / 2, (img.height - side) / 2, side, side,
            0, 0, size, size
          );
          resolve(canvas.toDataURL("image/png"));
        };
        img.src = String(reader.result || "");
      };
      reader.readAsDataURL(file);
    });
  }

  function pinParam() {
    const field = document.getElementById("wh-pin");
    const pin = field && field.value ? field.value.trim() : "";
    return pin ? "?pin=" + encodeURIComponent(pin) : "";
  }

  async function addWebhook() {
    errEl.textContent = "";
    const name = (document.getElementById("wh-name").value || "").trim();
    const url = (document.getElementById("wh-url").value || "").trim();
    const username = (document.getElementById("wh-username").value || "").trim();
    if (!url) { errEl.textContent = "Paste the webhook URL from Discord."; return; }
    try {
      const response = await fetch("/api/webhooks" + pinParam(), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: name, url: url, username: username })
      });
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.error || ("Refused (" + response.status + ")"));

      // The picture is a second call, because it edits the webhook on Discord
      // rather than being stored here.
      if (pendingAvatar && body.id) {
        const avatarResponse = await fetch(
          "/api/webhooks/" + encodeURIComponent(body.id) + "/avatar" + pinParam(),
          { method: "POST", headers: { "Content-Type": "text/plain" }, body: pendingAvatar }
        );
        if (!avatarResponse.ok) {
          const detail = await avatarResponse.json().catch(() => ({}));
          errEl.textContent = "Saved, but the picture was refused: " +
            (detail.error || avatarResponse.status);
        }
        pendingAvatar = "";
      }
      await showWebhookSettings();
    } catch (e) {
      errEl.textContent = (e && e.message) ? e.message : String(e);
    }
  }

  async function removeWebhook(id) {
    errEl.textContent = "";
    try {
      const response = await fetch(
        "/api/webhooks/" + encodeURIComponent(id) + pinParam(), { method: "DELETE" }
      );
      const body = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(body.error || ("Refused (" + response.status + ")"));
      await showWebhookSettings();
    } catch (e) {
      errEl.textContent = (e && e.message) ? e.message : String(e);
    }
  }

  function flash(b, text) {
    const span = b.querySelector("span");
    if (!span) return;
    const was = span.textContent;
    span.textContent = text;
    setTimeout(() => { span.textContent = was; }, 1400);
  }

  // --------------------------------------------------------------- updates

  /*
   * UPDATING IN PLACE.
   *
   * The app downloads the new APK itself and hands it to Android's installer;
   * everything up to that point is :core, and tested there. This is only the
   * bar that reports it.
   *
   * WHERE IT SHOWS. On the device running the app, and nowhere else. The APK
   * installs HERE; a page open on an iPad is looking at software it cannot
   * replace, and the button there asked for a PIN and then updated a machine
   * in another room. /api/update/status says which kind of page this is.
   *
   * THIS IS NOT A POLL. One request when somebody opens the page, in the same
   * spirit as asking a speaker what is playing only when there is somebody
   * there to read the answer. The one timer in here runs only while a download
   * this page started is in flight, and stops when it lands.
   */
  async function checkForUpdate() {
    if (!updateEl) return;
    try {
      // ONLY ON THE DEVICE ITSELF. The APK installs here, on the machine
      // running the app — so an update bar on an iPad across the house is
      // offering to replace software on something else, which is not what
      // anybody pressing it means. It asked for a PIN and then did nothing
      // visible, which was worse than not being there.
      const state = await getJson("/api/update/status");
      if (!state || !state.onDevice) {
        updateEl.classList.add("hidden");
        return;
      }
      // Here, and only here, is it worth going out to GitHub to look.
      const response = await fetch("/api/update/check", { method: "POST", cache: "no-store" });
      showUpdate(await response.json().catch(() => state));
    } catch (e) {
      // An update notice that cannot be fetched is not worth a line of red on
      // a page whose actual job is drawing a card.
      updateEl.classList.add("hidden");
    }
  }

  function showUpdate(state) {
    if (!state || state.supported === false || !state.available) {
      updateEl.classList.add("hidden");
      updateEl.innerHTML = "";
      return;
    }
    updateEl.classList.remove("hidden");

    const phase = (state.phase && state.phase.name) || "idle";
    const busyText = {
      checking: "Checking\u2026",
      downloading: "Downloading\u2026",
      verifying: "Checking the download\u2026",
      installing: "Android is asking you to confirm\u2026"
    }[phase];

    if (phase === "error" && state.phase.error) {
      updateEl.innerHTML = '<span class="update-text">' +
        escapeHtml(state.phase.error) + "</span>";
      return;
    }
    if (busyText) {
      updateEl.innerHTML = '<span class="update-text">' + escapeHtml(busyText) + "</span>";
      return;
    }

    const line = "Version " + escapeHtml(String(state.latest)) + " is available.";
    if (state.blocked) {
      // Says why rather than offering a button that ends in Android's
      // "App not installed" with no reason given.
      updateEl.innerHTML = '<span class="update-text">' + line + " " +
        escapeHtml(state.blocked) + "</span>";
      return;
    }
    updateEl.innerHTML = '<span class="update-text">' + line + "</span>" +
      '<button class="update-go" id="update-go">Update</button>';
    const go = document.getElementById("update-go");
    if (go) go.onclick = startUpdate;
  }

  async function startUpdate() {
    try {
      const state = await (await fetch(
        "/api/update/apply", { method: "POST", cache: "no-store" }
      )).json();
      showUpdate(state);
      watchUpdate();
    } catch (e) {
      updateEl.innerHTML = '<span class="update-text">Could not start the update.</span>';
    }
  }

  /* Runs only while a download is in flight, and stops the moment it is not. */
  function watchUpdate() {
    const timer = setInterval(async () => {
      let state;
      try {
        state = await getJson("/api/update/status");
      } catch (e) {
        clearInterval(timer);
        return;
      }
      showUpdate(state);
      const phase = (state.phase && state.phase.name) || "idle";
      if (phase !== "downloading" && phase !== "verifying") clearInterval(timer);
    }, 1500);
  }

  // ---------------------------------------------------------------- wiring

  refresh.addEventListener("click", () => load(true));
  zoneSel.addEventListener("change", () => load(false));

  /*
   * Redraw when the page is brought back, not on a timer.
   *
   * This runs on a device that is always on, and a poll would mean asking a
   * speaker what it is doing every few seconds for the rest of the day to
   * answer a question nobody is in the room to read. Coming back to the tab is
   * the moment somebody actually wants to know.
   */
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && current) load(false);
  });

  /*
   * PINCH TO ZOOM, OFF.
   *
   * The viewport meta handles Android and desktop. iOS Safari has ignored
   * user-scalable since iOS 10 — deliberately, for accessibility — so the
   * only thing left is refusing the gesture events, which are iOS-only and
   * fire ONLY for a multi-finger pinch.
   *
   * NOT touchstart. A touchstart that calls preventDefault kills the long
   * press, and the long press is how an iPhone copies the card — the one
   * control iOS has that genuinely works. It would fail silently, and only
   * for somebody holding a finger on the picture.
   */
  for (const type of ["gesturestart", "gesturechange", "gestureend"]) {
    document.addEventListener(type, (e) => e.preventDefault(), { passive: false });
  }

  // AFTER the first load, not beside it: whether this browser may ask the
  // device to check comes back with the webhook list, which load() fetches.
  load(false).then(checkForUpdate);
})();
