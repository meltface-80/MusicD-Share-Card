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
  const refresh  = document.getElementById("refresh");

  /*
   * Bumped on every load(), so a late redraw cannot land on a card the user
   * has since replaced by picking another room or pressing Refresh. Without it
   * a slow blurb for the previous album repaints over the new card.
   */
  let token = 0;

  /** The card currently on screen, for the action buttons to hand over. */
  let current = null;

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
      search: '<circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/>'
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
    for (const zone of zones) {
      const option = document.createElement("option");
      option.value = zone.uid;
      option.textContent = zone.name;
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
    hintEl.textContent = "";
    errEl.textContent = "";
    nowEl.textContent = "";
    busy(true);
    spinner("Looking for what’s playing…");

    try {
      await loadZones(force);
      if (mine !== token) return;

      const params = new URLSearchParams();
      if (zoneSel.value) params.set("zone", zoneSel.value);
      if (force) params.set("refresh", "1");
      const playing = await getJson("/api/now-playing?" + params);
      if (mine !== token) return;

      if (!playing.album && !playing.artist) {
        message(playing.reason || "Nothing is playing.");
        var noPlayers = !playing.reason || playing.reason.indexOf("No Sonos") === 0 ||
          playing.reason.indexOf("would answer") > 0;
        hintEl.textContent = noPlayers
          ? "The app cannot see your speakers. Run the check below to find out why."
          : "Start something on a Sonos zone, then press refresh.";
        // A dead end with no next step is what made the first failure so hard
        // to act on: the app knew far more than it was saying.
        if (noPlayers) offerDiagnostics();
        return;
      }

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
                       (full.score != null && full.score !== painted.score);
        if (!better) return;
        return paint(mine, playing, merge(painted, full));
      }).catch(() => { /* the card without it is already up */ });
    }
  }

  const EMPTY = { release: "", bio: "", bioSource: "", score: null, isBestNewMusic: false };

  function extrasOf(j) {
    return {
      release: j && j.release ? String(j.release) : "",
      bio: (j && j.bio) || "",
      bioSource: (j && j.bioSource) || "",
      score: j && typeof j.score === "number" ? j.score : null,
      isBestNewMusic: !!(j && j.isBestNewMusic)
    };
  }

  function merge(a, b) {
    return {
      release: b.release || a.release,
      bio: b.bio || a.bio,
      bioSource: b.bioSource || a.bioSource,
      score: b.score != null ? b.score : a.score,
      isBestNewMusic: b.isBestNewMusic || a.isBestNewMusic
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
  }

  function describe(playing) {
    const room = playing.zone && playing.zone.name ? playing.zone.name : "";
    const verb = playing.playing ? "Playing in" : "Last played in";
    const bits = [];
    if (room) bits.push(verb + " <b>" + escapeHtml(room) + "</b>");
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

    hintEl.textContent = isIOS
      ? "Press and hold the card to copy it, save it to Photos or share it."
      : "";
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
    section("This device's networks", d.interfaces);
    section("Multicast (SSDP)", (d.ssdp && d.ssdp.notes) || []);
    section("Direct scan of this subnet", (d.scan && d.scan.notes) || []);
    section("Addresses being tried", d.hosts);
    if (d.zones && d.zones.length) {
      section("Rooms", d.zones.map((z) =>
        z.name + " (" + z.ip + ") — " + z.state +
        (z.album ? ": " + z.album + (z.artist ? " by " + z.artist : "") : "")));
    }
    hintEl.innerHTML = "";
    nowEl.innerHTML = "";
    show("<div class=\"diag\">" + rows.join("") + "</div>");
  }

  function flash(b, text) {
    const span = b.querySelector("span");
    if (!span) return;
    const was = span.textContent;
    span.textContent = text;
    setTimeout(() => { span.textContent = was; }, 1400);
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

  load(false);
})();
