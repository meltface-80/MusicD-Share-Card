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
  // The page's one column. Its side padding is dropped for the sleeve wall,
  // which is the only screen here that wants the glass — see restage().
  const wrap     = document.querySelector(".wrap");
  const nowEl    = document.getElementById("now");
  const actions  = document.getElementById("actions");
  const hintEl   = document.getElementById("hint");
  const errEl    = document.getElementById("err");
  const zoneSel  = document.getElementById("zone");
  const zoneWrap = document.getElementById("zone-wrap");
  const updateEl = document.getElementById("update");
  const linksEl  = document.getElementById("links");
  const simEl    = document.getElementById("similar");
  const refresh  = document.getElementById("refresh");
  const settingsBtn = document.getElementById("settings");
  const tabCard  = document.getElementById("tab-card");
  const tabNew   = document.getElementById("tab-new");

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

  /**
   * The PIN, the version and which shell this is.
   *
   * `variant` decides whether the action row draws a Download button, so it
   * has to be in hand before the first card is painted rather than arriving
   * with the update bar. "android" is the default because it is the one that
   * KEEPS the button: a page that has not been told what it is running on
   * should not remove a control on a guess.
   */
  let setup = {
    onDevice: false, mayConfigure: false, pin: null, needsPin: true,
    version: "", variant: "android"
  };

  /** The project page — release notes, the APK link and the Docker commands. */
  const PROJECT_URL = "https://meltface-80.github.io/MusicD-Share-Card/";
  const PROJECT_HOST = "meltface-80.github.io";

  /** What the settings screens last read back from the server. */
  let settings = { services: [], zones: [], anyZoneEnabled: false };

  /** A source asking to be let in, which outranks any tip the page would show. */
  let noticeText = "";

  /*
   * Has anybody actually touched the room picker on THIS page?
   *
   * It is the difference between a select that has never been built and one
   * deliberately set back to "Whatever's playing" — see loadZones, where
   * conflating the two made the grid unreachable.
   */
  let pickerUsed = false;

  const isIOS = /iP(hone|ad|od)/.test(navigator.platform || "") ||
    (navigator.userAgent.includes("Mac") && "ontouchend" in document);

  // --------------------------------------------------------------- helpers

  /*
   * The page does not scroll — see the body rule in style.css — and the
   * diagnostics are the single exception. They are a wall of facts meant to be
   * read off the screen of a device in another room and typed out, so clipping
   * them would be worse than the scrolling they replace. Decided here, from
   * what actually went into the stage, rather than by a caller remembering to.
   */
  function show(html) {
    stage.innerHTML = html;
    restage();
  }

  /** The same, for a screen that builds nodes rather than a string. */
  function showNode(node) {
    stage.innerHTML = "";
    stage.appendChild(node);
    restage();
  }

  /*
   * WHICH KIND OF STAGE THIS IS, DECIDED FROM WHAT WENT INTO IT.
   *
   * Every one of these is read off the content rather than set by the caller,
   * and that is the rule: a caller that has to remember to CLEAR a class is a
   * caller that will one day leave the diagnostics' scroll on a card. Adding a
   * screen means adding a line here, not a line in every screen.
   */
  function restage() {
    stage.classList.toggle("scrolls", !!stage.querySelector(".diag"));
    stage.classList.toggle("choosing", !!stage.querySelector(".rooms"));
    stage.classList.toggle(
      "browsing",
      !!(stage.querySelector(".newgrid") || stage.querySelector(".newone"))
    );
    /*
     * THE GRID IS FULL-BLEED; THE SINGLE RECORD IS NOT.
     *
     * A wall of sleeves is a list and the sleeves are the screen, so it loses
     * the frame and the gutter. One record opened from it is the same kind of
     * object as the card on the other tab — one picture, framed — and making
     * those two disagree would be a difference with no reason behind it.
     */
    const wall = !!stage.querySelector(".newgrid");
    stage.classList.toggle("grid-full", wall);
    wrap.classList.toggle("edge", wall);
  }

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

  /*
   * An action button.
   *
   * NO ICON ON THE ACTION ROW ANY MORE, AND THAT IS WHAT MAKES THE LABEL FIT.
   * These became pills the height of a review chip — asked for, and the height
   * it saves is what lets a suggestion's name wrap below. A pill is wide and
   * shallow, so an icon beside the label eats the width the label needs: at
   * 320px "Discord Now Playing" came out as "Discord Now…", and that label is
   * the user's own webhook name, which this page has a standing rule never to
   * clip. Without the icon it wraps to two lines and fits.
   *
   * It also finishes the thing that was actually asked for. The review chips
   * these are now sized to are words and nothing else, so an action row with
   * icons would be the same shape as them and not the same furniture.
   *
   * `icon()` is untouched and still used by the header, the update bar and
   * "Find my speakers", which is alone in its row and has the width for one.
   */
  function button(cls, label, name) {
    const b = document.createElement("button");
    b.className = cls;
    b.dataset.icon = name || "";
    b.innerHTML = "<span>" + label + "</span>";
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

  /**
   * The requests the CARD is waiting on, so they can be let go of.
   *
   * A browser allows a handful of connections to one origin and this server
   * answers on a small pool of threads, so a slow request holds a slot. That
   * matters here because the one request that can be slow is the card's —
   * `/api/now-playing` runs a discovery sweep — and the screen somebody is
   * most likely to open while it runs is Settings, which needs a request of
   * its own to draw anything.
   *
   * WITHOUT THIS, OPENING SETTINGS DURING A FIRST RUN LOOKS BROKEN. Measured
   * in a browser against the real server with no players on the network: the
   * menu appeared, the sub-screens did not, and thirty seconds later they all
   * arrived at once. A first run is precisely when discovery is slowest AND
   * when the empty card says "open Settings", so that is the worst possible
   * place for it.
   */
  let cardRequests = null;

  async function getJson(url, options) {
    const response = await fetch(url, Object.assign({ cache: "no-store" }, options));
    if (!response.ok) {
      let detail = "";
      try { detail = (await response.json()).error || ""; } catch (e) { /* no body */ }
      throw new Error(detail || ("The card server answered " + response.status + "."));
    }
    return response.json();
  }

  // ----------------------------------------------------------------- zones

  async function loadZones(force, signal) {
    let data;
    try {
      data = await getJson("/api/zones" + (force ? "?refresh=1" : ""), signal);
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
    // WHATEVER'S PLAYING IS THE EMPTY STRING, AND THE EMPTY STRING IS FALSY.
    //
    // `zoneSel.value || data.selected` could not tell "the user has not chosen
    // yet" from "the user just chose Whatever's playing", because both are "".
    // So picking it fell straight through to the room the server still
    // remembered, the picker snapped back, and load() then SENT that room — so
    // the server never got a request without a zone and never cleared its
    // memory. Self-perpetuating: reported as being able to reach every
    // individual room but never the grid again.
    //
    // The server's `selected` is only ever a SEED, for a page that has just
    // been opened and has no options yet. Once somebody has used the picker,
    // the picker is the truth.
    const chosen = pickerUsed ? zoneSel.value : (zoneSel.value || data.selected || "");
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
    // The card is the Playing tab whichever way it was reached — the cog, a
    // tile on the chooser, Done in Settings. Setting it here rather than in
    // every caller is what stops the tabs and the screen disagreeing.
    selectTab("card");
    // Anything the last card was waiting on is no longer wanted.
    if (cardRequests) cardRequests.abort();
    cardRequests = typeof AbortController === "function" ? new AbortController() : null;
    const signal = cardRequests ? { signal: cardRequests.signal } : undefined;
    current = null;
    actions.innerHTML = "";
    linksEl.innerHTML = "";
    linksEl.classList.add("hidden");
    simEl.innerHTML = "";
    simEl.classList.add("hidden");
    hintEl.textContent = "";
    errEl.textContent = "";
    nowEl.textContent = "";
    noticeText = "";
    busy(true);
    spinner("Looking for what’s playing…");

    try {
      await loadZones(force, signal);
      // Once per page, not once per card: the version and the variant cannot
      // change while this page is open, and this route opens no socket to
      // anything. buildActions needs the variant, so it must be in hand before
      // the first paint rather than arriving with the update bar.
      if (!setup.version) await refreshSetup();
      await loadWebhooks(signal);
      if (mine !== token) return;

      const params = new URLSearchParams();
      if (zoneSel.value) params.set("zone", zoneSel.value);
      if (force) params.set("refresh", "1");
      // THE SLOW ONE. On a first run this is a discovery sweep, and it is the
      // request Settings has to be able to walk away from.
      const playing = await getJson("/api/now-playing?" + params, signal);
      if (mine !== token) return;

      // MORE THAN ONE ROOM ON. The server decides this, not the page: the
      // rule for when a grid beats a card lives in :core where it is tested,
      // and a copy of it here would be a second place for it to drift.
      if (playing.choose) {
        showChooser(playing.rooms || []);
        // The action row, the links and the suggestions stay as load() left
        // them — emptied and hidden. There is no card, so they have nothing
        // to act on, and the grid takes the space they leave.
        const chooserNotices = playing.notices || [];
        hintEl.textContent = chooserNotices.length
          ? chooserNotices.join(" ")
          : "Tap a room to make its card.";
        return;
      }

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
      // ABANDONING THIS LOAD IS NOT A FAILURE. Opening Settings aborts
      // whatever the card was waiting on, and the rejection that causes must
      // not paint "Could not build the card" over the screen that did it.
      if (e && e.name === "AbortError") return;
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
    /*
     * Remembered from the CARD, not from the picker. "Whatever's playing"
     * sends no zone at all, so the room that answered is the only one a queue
     * could sensibly go to.
     *
     * WHETHER IT CAN TAKE ONE IS THE SERVER'S ANSWER, NOT A TEST HERE. This
     * read `uid.indexOf("roon:") === 0`, which is a rule — in the one file
     * where nothing can test it, and where adding Lyrion would have meant a
     * second copy to keep in step with the route. `canQueue` is computed from
     * the same list the route dispatches on. Same argument as the chooser's
     * `choose` flag and Discover's `why`.
     */
    const zone = playing.zone || {};
    queueZone = zone.canQueue ? (zone.uid || "") : "";
    queueWhere = zone.source || "";
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
                       // paint() — without this the chip never appears. The
                       // article behind the blurb is the same story: the words
                       // can already be on screen from the cache while the URL
                       // for them arrives a moment later.
                       (full.reviewUrl && full.reviewUrl !== painted.reviewUrl) ||
                       (full.bioUrl && full.bioUrl !== painted.bioUrl);
        if (!better) return;
        return paint(mine, playing, merge(painted, full));
      }).catch(() => { /* the card without it is already up */ });
    }
  }

  const EMPTY = {
    release: "", bio: "", bioSource: "", bioUrl: "", score: null,
    isBestNewMusic: false, reviewUrl: "", links: [], reading: []
  };

  function extrasOf(j) {
    return {
      release: j && j.release ? String(j.release) : "",
      bio: (j && j.bio) || "",
      bioSource: (j && j.bioSource) || "",
      bioUrl: (j && j.bioUrl) || "",
      score: j && typeof j.score === "number" ? j.score : null,
      isBestNewMusic: !!(j && j.isBestNewMusic),
      reviewUrl: (j && j.reviewUrl) || "",
      reading: (j && j.reading) || [],
      links: (j && Array.isArray(j.links)) ? j.links : []
    };
  }

  function merge(a, b) {
    return {
      release: b.release || a.release,
      bio: b.bio || a.bio,
      bioSource: b.bioSource || a.bioSource,
      bioUrl: b.bioUrl || a.bioUrl,
      score: b.score != null ? b.score : a.score,
      isBestNewMusic: b.isBestNewMusic || a.isBestNewMusic,
      reviewUrl: b.reviewUrl || a.reviewUrl,
      reading: (b.reading && b.reading.length) ? b.reading : a.reading,
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
    // Neither of these is awaited: the card is finished. One improves a chip,
    // the other adds a row under it — and both are several requests to outside
    // hosts behind rate gates, which is not something to hold a card for.
    upgradeQobuz(mine, playing);
    buildSimilar(mine, playing);
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
  /*
   * THE PREFERRED SERVICE, AND WHY IT IS NOT A SETTING.
   *
   * The suggestion chips have to link SOMEWHERE, and that was Qobuz for
   * everybody because Qobuz is first in the list. Making it a choice would
   * normally mean a settings screen, and this page has one of those already —
   * it is for webhooks, it is a credential form, and burying a one-tap
   * preference behind it would be worse than the default it replaced.
   *
   * So the choice is made ON the thing being chosen: hold a finger on a
   * service chip and it is marked with a tick. Nothing new to find, nothing to
   * open, and the chip you press is the answer to the question.
   *
   * IT LIVES IN localStorage, NOT ON THE SERVER, and that is deliberate twice
   * over. It is a per-DEVICE preference — the phone and the iPad across the
   * house can reasonably differ — and it keeps this a read: every route here
   * answers without writing, only three things in the whole app touch disk,
   * and a new one of those would have to go behind the configure gate. A
   * display preference is not worth that. Storage can also throw outright in a
   * private window, so every touch of it is guarded and the default stands.
   */
  const PREF_KEY = "sharecard.preferredService";
  const DEFAULT_SERVICE = "qobuz";

  function preferredService() {
    try {
      return localStorage.getItem(PREF_KEY) || DEFAULT_SERVICE;
    } catch (e) {
      return DEFAULT_SERVICE;
    }
  }

  function setPreferredService(service) {
    if (!service || service === preferredService()) return;
    try {
      localStorage.setItem(PREF_KEY, service);
    } catch (e) { /* the choice lasts this session, which is better than none */ }
    markPreferred();
    // The suggestions point at the old service until they are asked again.
    // Cheap: the server answered from its shelf, so this is one local request.
    if (current) buildSimilar(token, { artist: current.artist, album: current.album });
  }

  /** Exactly one chip carries the tick, so the old one has to lose it. */
  function markPreferred() {
    const chosen = preferredService();
    let marked = false;
    const chips = linksEl.querySelectorAll("a[data-service]");
    for (const chip of chips) {
      const mine = chip.dataset.service === chosen;
      chip.classList.toggle("preferred", mine);
      chip.setAttribute("aria-pressed", mine ? "true" : "false");
      if (mine) marked = true;
    }
    // A remembered service this record has no chip for — nothing marked would
    // look like the preference had been forgotten, so the default takes it.
    if (!marked && chips.length) {
      for (const chip of chips) {
        if (chip.dataset.service === DEFAULT_SERVICE) {
          chip.classList.add("preferred");
          chip.setAttribute("aria-pressed", "true");
        }
      }
    }
  }

  /*
   * A HOLD, NOT A TAP, and the tap still has to work.
   *
   * A timer started on touchstart and cancelled by a move or a lift is the
   * only way to tell the two apart — there is no long-press event. When it
   * fires, the click that iOS and Android send afterwards has to be swallowed,
   * or choosing a service would also open it.
   *
   * contextmenu covers the desktop right-click and is also what iOS raises
   * when the callout is suppressed; preventing it is what stops a held chip
   * showing a link preview instead of choosing.
   */
  const HOLD_MS = 500;

  function holdToPrefer(chip, service) {
    let timer = null;
    let held = false;

    const cancel = () => {
      if (timer) clearTimeout(timer);
      timer = null;
    };
    const start = () => {
      held = false;
      cancel();
      timer = setTimeout(() => {
        held = true;
        setPreferredService(service);
      }, HOLD_MS);
    };

    chip.addEventListener("touchstart", start, { passive: true });
    chip.addEventListener("touchmove", cancel, { passive: true });
    chip.addEventListener("touchend", cancel);
    chip.addEventListener("touchcancel", cancel);
    chip.addEventListener("click", (e) => {
      if (!held) return;
      held = false;
      e.preventDefault();
    });
    chip.addEventListener("contextmenu", (e) => {
      e.preventDefault();
      setPreferredService(service);
    });
  }

  function buildLinks(extras) {
    linksEl.innerHTML = "";
    const services = (extras && extras.links) || [];
    const review = extras && extras.reviewUrl;
    // The article the blurb was lifted from — Wikipedia for every record that
    // has one. The card already credits it in small type under the words; this
    // is the same credit made followable.
    const article = extras && extras.bioUrl;
    const extraReading = ((extras && extras.reading) || []).length > 0;
    const reading = !!(review || article || extraReading);
    if (!services.length && !reading) {
      linksEl.classList.add("hidden");
      return;
    }

    const label = document.createElement("p");
    label.className = "links-label";
    label.textContent = reading ? "Read about it, or find it on" : "Find it on";
    linksEl.appendChild(label);

    /*
     * TWO ROWS, AND NEITHER MAY SPILL INTO THE OTHER.
     *
     * One grid held both, so whatever the reviews did not use up was filled by
     * the first service — Qobuz sat on the end of the review line and Spotify
     * and Bandcamp started a line of their own underneath. Two chips that do
     * completely different things shared a row, and which ones did depended on
     * how many review sources happened to be switched on.
     *
     * A grid each. Reviews take as many lines as they need and services start
     * on a fresh one, so the row you are looking at is always one kind of
     * thing. Four of anything is a line; a fifth starts a second, which is the
     * honest cost of a fixed grid and is rare either side.
     */
    const reviewRow = document.createElement("div");
    reviewRow.className = "links-row";
    const serviceRow = document.createElement("div");
    serviceRow.className = "links-row";

    // Both of these go to a page about THIS record rather than a search for
    // it, which is what sets them apart from the row that follows.
    if (review) {
      /*
       * NAMED BY WHOEVER WROTE IT, NOT HARD-CODED. Pitchfork was the only
       * source of a review link for as long as one came from a score lookup,
       * so this chip carried their name in the page. A record that arrived
       * out of NME's feed then drew an NME review under Pitchfork's name —
       * the same mistake the blurb chip two lines below already has a rule
       * about, made in the one place the rule had not reached.
       *
       * The default keeps the card's own behaviour exactly: the score lookup
       * is Pitchfork's and names nobody, because it never had to.
       */
      const by = (extras && extras.reviewName) || "Pitchfork review";
      reviewRow.appendChild(link(review, by, "links-review"));
    }
    if (article) {
      // Named by whoever the blurb came from rather than hard-coded, so a
      // second source added later labels its own chip.
      const source = (extras && extras.bioSource) || "Wikipedia";
      reviewRow.appendChild(link(article, source, "links-review"));
    }
    /*
     * Whatever else Reviews is switched on for — AllMusic, an artist's
     * article. The SERVER names them and this draws what it is given, so a
     * source added later needs no change here. They sit with the review chips
     * rather than the services because they go to a page ABOUT the record or
     * the act, not a search for somewhere to play it.
     */
    for (const extra of (extras && extras.reading) || []) {
      if (extra && extra.url && extra.name) {
        reviewRow.appendChild(link(extra.url, extra.name, "links-review"));
      }
    }
    for (const svc of services) {
      if (!svc || !svc.url || !svc.name) continue;
      const a = link(svc.url, svc.name, "");
      // Marked so the Qobuz one can be upgraded in place when its album id
      // arrives (see upgradeQobuz), and so a held chip knows which service it
      // is choosing.
      if (svc.service) {
        a.dataset.service = svc.service;
        a.setAttribute("aria-pressed", "false");
        holdToPrefer(a, svc.service);
      }
      serviceRow.appendChild(a);
    }
    // An empty row still carries its gap, so one that holds nothing is not
    // added at all — a household with every service switched off must not get
    // a band of blank space where they were.
    if (reviewRow.children.length) linksEl.appendChild(reviewRow);
    if (serviceRow.children.length) linksEl.appendChild(serviceRow);
    markPreferred();
    linksEl.classList.remove("hidden");
  }

  /*
   * IF YOU LIKE THIS — acts to hear next, under the links and never on the card.
   *
   * AFTER THE CARD, NOT BEFORE IT. This is up to six requests to two outside
   * hosts behind rate gates. The cached answer comes back at once; the slow one
   * is left to arrive on its own and fills the row in when it does, the same
   * shape as the Qobuz chip. Nothing here can delay a picture.
   *
   * ARTISTS, NOT ALBUMS, AND THE LABEL SAYS SO. Nothing keyless does
   * album-to-album similarity, so what comes back is acts with one record each
   * — see Similar.kt. "If you like this" promises what it can deliver where
   * "You might also like" would promise a recommendation engine.
   *
   * AN EMPTY ROW IS NOT DRAWN AT ALL. A heading with nothing under it looks
   * like a failure; no heading looks like a record nobody has listened to next
   * to anything else, which is the truth. /api/debug says which it was.
   */
  async function buildSimilar(mine, playing) {
    const artist = playing.artist || "";
    if (!artist) return;
    // The service is the page's to choose and the URL is the server's to
    // build: Qobuz's search needs a storefront segment, and the rules for that
    // live in StreamingLinks with a test each.
    const params = new URLSearchParams({
      artist: artist,
      album: playing.album || "",
      service: preferredService()
    });

    let acts = [];
    try {
      acts = actsOf(await getJson("/api/similar?fast=1&" + params));
    } catch (e) { /* the shelf is empty, which is not an error */ }
    if (mine !== token) return;
    if (acts.length) { drawSimilar(acts); upgradeSuggestions(mine, acts); }

    // Already drawn from the shelf means the slow path has nothing to add:
    // both answers come out of the same cache entry.
    if (acts.length) return;
    try {
      const slow = actsOf(await getJson("/api/similar?" + params));
      if (mine !== token || !slow.length) return;
      drawSimilar(slow);
      upgradeSuggestions(mine, slow);
    } catch (e) { /* no row, which is the honest outcome */ }
  }

  /*
   * THE SUGGESTIONS GET THE SAME QOBUZ UPGRADE THE CARD'S CHIP DOES.
   *
   * Reported with a photograph of where a tap landed: qobuz.com's DOWNLOAD
   * STORE, "Results for U2 Rattle And Hum — 1-60 of 1000 albums", the first of
   * them a record by somebody called ItsLee. That is what a Qobuz search link
   * does, and it is exactly the failure StreamingLinks already carries a rule
   * about: Qobuz needs an album ID, and a search link can never open that app.
   *
   * The CARD's chip has been upgraded to a real album link since that was
   * reported. The suggestions were not, because their URLs are built on the
   * server in one go — which is right, the encoding rules live there — and
   * nothing then went back to resolve them. So the row that exists to send you
   * somewhere new was the one place still landing on a shop's search page.
   *
   * AFTER THE ROW IS DRAWN AND NEVER BEFORE IT, and only for the service the
   * chips actually point at. Each is a page read off www.qobuz.com behind a
   * rate gate; none of it may hold up a suggestion appearing, and a failure
   * leaves the search link that was already there.
   */
  async function upgradeSuggestions(mine, acts) {
    if (preferredService() !== "qobuz") return;
    for (const act of acts) {
      if (!act.album) continue;
      try {
        const params = new URLSearchParams({ album: act.album, artist: act.name || "" });
        const data = await getJson("/api/qobuz?" + params);
        if (mine !== token) return;
        if (!data || !data.url) continue;
        // The chip may have been rebuilt while that was in flight, so it is
        // found again by what it links to rather than held onto.
        const chip = simEl.querySelector('a[href="' + cssEscape(act.url) + '"]');
        if (chip) chip.href = data.url;
      } catch (e) { /* the search link is still there, which is not nothing */ }
    }
  }

  /** Quotes and backslashes, so a URL can sit inside an attribute selector. */
  function cssEscape(value) {
    return String(value).replace(/["\\]/g, "\\$&");
  }

  function actsOf(j) {
    return (j && Array.isArray(j.acts)) ? j.acts.filter((a) => a && a.name && a.url) : [];
  }

  /**
   * The zone the card on screen is about, if it can take a queue, or "".
   *
   * Set from the card itself rather than from the picker: "Whatever's playing"
   * sends no zone at all, and the room that answered is the one a queue would
   * go to. WHETHER it can take one is the server's answer (`canQueue`), never
   * a prefix tested here — that rule lives in :core where it has tests.
   */
  let queueZone = "";
  let queueWhere = "";

  /**
   * Queue a suggestion into the room instead of leaving the page for it.
   *
   * THE LINK STAYS ON THE CHIP AND IS THE FALLBACK. The room may never have
   * heard of the record — a suggestion is deliberately something you have not
   * played — and a tap that does nothing would be worse than the streaming
   * search it replaced. So: try to queue, and if the library cannot find it,
   * follow the link exactly as before. The tap always does something.
   *
   * `preventDefault` only once queueing is known to have worked would be too
   * late (the navigation has already happened), so it is prevented up front
   * and the navigation is done by hand if the queue attempt comes back empty.
   */
  function queueOnTap(chip, act) {
    chip.classList.add("sim-queue");
    chip.addEventListener("click", async (event) => {
      event.preventDefault();
      errEl.textContent = "";
      const was = chip.textContent;
      chip.textContent = "Queueing\u2026";
      try {
        const response = await fetch("/api/queue" + pinParam(), {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({
            album: act.album || "",
            // `name` IS the artist on a suggestion — the row is "act · record"
            // and there is no separate artist field. Sending act.artist would
            // have posted an empty string and matched the album by title
            // alone, which is how a stranger's record ends up in the queue.
            artist: act.name || "",
            zone: queueZone
          })
        });
        const answer = await response.json().catch(() => ({}));
        if (response.ok && answer.queued) {
          /*
           * NAMED BY WHOEVER TOOK IT, NOT HARD-CODED. This said "Queued in
           * Roon" whatever answered — true while Roon was the only source
           * with a queue, and a Lyrion player would have reported itself as
           * Roon the moment one was added. The third time this exact mistake
           * has been made in this repo: the "Pitchfork review" chip drew an
           * NME review under Pitchfork's byline, and `bioSource` was added
           * because a blurb credit was hard-coded before that.
           */
          chip.textContent = queueWhere ? "Queued in " + queueWhere : "Queued";
          chip.classList.add("sim-queued");
          return;
        }
        // It could not, so do what the chip says it does — AND SAY SO.
        //
        // The reason was thrown away here: the server works out which step it
        // stopped at and puts it in `detail`, and this opened a streaming
        // search without showing a word of it. So a record sitting in the
        // library that would not queue looked exactly like a record nobody had
        // heard of, which is how this was reported with nothing to go on.
        // /api/debug keeps the full note; this is the line somebody sees.
        chip.textContent = was;
        if (answer.detail) errEl.textContent = answer.detail;
        window.open(chip.href, "_blank", "noopener");
      } catch (e) {
        chip.textContent = was;
        errEl.textContent = (e && e.message) ? e.message : "Could not reach the card server.";
        window.open(chip.href, "_blank", "noopener");
      }
    });
  }

  function drawSimilar(acts) {
    simEl.innerHTML = "";
    const label = document.createElement("p");
    label.className = "links-label";
    label.textContent = "If you like this, try these";
    simEl.appendChild(label);

    for (const act of acts) {
      // The URL is the SERVER'S, not one built here. Qobuz's search needs a
      // storefront segment or it 404s, a space has to be %20 because the query
      // rides in the path, and a slash has to be spent rather than encoded —
      // three rules that already live in StreamingLinks with a test each. A
      // second copy of them in this file is how they drift apart.
      if (!act.url) continue;
      /*
       * A ROW, NOT A CHIP, SO THAT A REVIEW CAN SIT BESIDE IT.
       *
       * Asked for: somewhere to read about a suggestion, the way the card has
       * under it. The record link keeps the width it had and the review is a
       * short pill on the end — AllMusic, built on the server from the two
       * names with no lookup, which is why three of them cost nothing. It is
       * absent when AllMusic is switched off in Settings, and absent for an
       * act with no album, because there is nothing to review.
       */
      const row = document.createElement("div");
      row.className = "sim-row";
      const chip = link(act.url, actLabel(act), "sim-main");
      /*
       * ON A ROON CARD, A TAP QUEUES IT INSTEAD OF LEAVING.
       *
       * Only there: the room has to be one Roon is playing to, or "add to the
       * end of the queue" names no queue. The link stays on the chip and is
       * what happens if Roon has never heard of the record — so the tap always
       * does something, and the something it does when it can is the better
       * one. Asked for as: if the card came from a Roon zone and the
       * suggestion is in the library, put it on the end of the queue.
       */
      /*
       * AN ACT WITH NO RECORD CANNOT BE QUEUED. Deezer's albums endpoint
       * returns singles and EPs as well, so the lookup keeps only real albums
       * — and an act whose only releases were singles keeps its name and
       * loses the record. There is nothing to put in a queue then, so the
       * chip stays an ordinary link.
       */
      if (queueZone && act.album) queueOnTap(chip, act);
      row.appendChild(chip);
      if (act.review && act.reviewName) {
        row.appendChild(link(act.review, act.reviewName, "sim-review"));
      }
      simEl.appendChild(row);
    }
    simEl.classList.remove("hidden");
  }

  /*
   * THE TYPE IS NOT MEASURED ANY MORE, AND THAT IS A DELETION WORTH
   * EXPLAINING.
   *
   * `fitSuggestions` measured the longest label on a canvas and set one font
   * size on the whole row, because the chips were full width, single line, and
   * at any one fixed size the longest clipped while the shortest floated in
   * nothing. It was the right answer to that layout.
   *
   * The layout changed underneath it. A suggestion is a ROW now — the record
   * on the left and a review pill on the end — so no chip is full width any
   * more, and the label WRAPS instead of being shrunk to fit. Wrapping is what
   * was asked for, and it is the better trade for the names this row actually
   * gets: "The Chemical Brothers · Live in Leicester 1995 (1995)" set at 9.5px
   * to avoid a second line is smaller than the credit under the card.
   *
   * With the text wrapping there is nothing left to measure: the size is
   * fixed, the row is as tall as its label needs, and the resize listener and
   * the fonts.ready re-fit went with it.
   */

  function actLabel(act) {
    if (!act.album) return act.name;
    return act.name + " \u00b7 " + act.album + (act.year ? " (" + act.year + ")" : "");
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

  /*
   * A chip.
   *
   * THE LABEL GOES IN A SPAN, WHICH IS NOT DECORATION. The links row gives
   * every chip one fixed height so that no label can set the height of the row
   * it is on — an artist chip labelled with a four-name Roon artist string
   * turned a whole row into circles — and the clamp that keeps a long label
   * inside that height needs `display: -webkit-box`, which the anchor itself
   * cannot be: it is a flex box so that a one-line label sits centred in a
   * two-line chip. See .links a in style.css.
   *
   * `a.textContent` still reads and writes the label either way, which is what
   * the suggestion chips do while a queue request is in flight.
   */
  function link(href, text, className) {
    const a = document.createElement("a");
    a.href = href;
    a.rel = "noreferrer";
    a.className = className;
    const label = document.createElement("span");
    label.textContent = text;
    a.appendChild(label);
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

  /*
   * THE CHOOSER: every room, drawn from what the server sent.
   *
   * The rooms that are ON get a cover each, because a sleeve is how you
   * recognise what is playing without reading. The SILENT ones are listed
   * underneath in plain text — they are listed at all because a grid holding
   * only the live rooms reads as the others having dropped off the network,
   * which is a worse and wronger statement than "nothing is playing in there".
   *
   * Tapping either takes you to that room's card, silent or not. That is the
   * same lock the dropdown applies: each zone is independent, and a room that
   * is not playing says so by name rather than borrowing the neighbours'
   * music.
   */
  function showChooser(rooms) {
    const live = rooms.filter((r) => r.playing);
    const idle = rooms.filter((r) => !r.playing);

    // Two sources can see the same room and answer differently — Roon playing
    // to a Sonos speaker is exactly that — so the tile says which, on the same
    // condition the dropdown does. rooms() already collapses the pair when
    // both call it the same name; this is for the rooms it cannot.
    const manySources = new Set(rooms.map((r) => r.source).filter(Boolean)).size > 1;
    const via = (room) => (manySources && room.source)
      ? `<div class="room-src">${escapeHtml(room.source)}</div>` : "";

    const what = (room) => {
      const bits = [room.album, room.artist].filter(Boolean);
      // A stream with no album still has a title worth showing.
      return bits.length ? bits.join(" — ") : (room.track || "");
    };

    const tile = (room) => {
      const art = room.art
        ? `<img class="room-art" src="${escapeHtml(room.art)}" alt="">`
        // No cover is a fact, not a gap to paper over — the tile keeps its
        // square so the row does not go ragged.
        : '<div class="room-art blank">♪</div>';
      const line = what(room);
      return `<button type="button" class="room" data-zone="${escapeHtml(room.uid)}">`
        + art
        + '<div class="room-text">'
        + `<div class="room-name">${escapeHtml(room.name)}</div>`
        + (line ? `<div class="room-what">${escapeHtml(line)}</div>` : "")
        + via(room)
        + "</div></button>";
    };

    const quiet = (room) =>
      `<button type="button" class="room-idle" data-zone="${escapeHtml(room.uid)}">`
      + escapeHtml(room.name)
      + '<div class="room-what">not playing</div>'
      + via(room)
      + "</button>";

    show('<div class="rooms">'
      + `<div class="room-grid">${live.map(tile).join("")}</div>`
      + (idle.length ? `<div class="rooms-idle">${idle.map(quiet).join("")}</div>` : "")
      + "</div>");

    stage.querySelectorAll("[data-zone]").forEach((el) => {
      el.addEventListener("click", () => {
        // Drive the picker rather than going around it, so the dropdown and
        // the card never disagree about which room is being shown.
        zoneSel.value = el.getAttribute("data-zone");
        load(false);
      });
    });
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
      /*
       * NOT "primary", and that is a deliberate match rather than an
       * oversight. iOS has no Share button here — its share sheet is reached
       * by holding the card — so what it draws is Download, the webhook and
       * the cog, three identical plain buttons. Android drew a filled yellow
       * Share beside two plain ones, which made the same page look like two
       * different apps depending on the phone it was opened on. The row is
       * uniform on both now.
       *
       * The class still exists and "Find my speakers" still uses it: that one
       * is drawn only when discovery has failed, it is the single thing worth
       * doing at that moment, and it is alone in the row.
       */
      const b = button("", "Share…", "share");
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

    /*
     * DOWNLOAD, ONLY WHERE THE BROWSER ITSELF WILL NOT DO IT.
     *
     * The card is an <img>, so on iOS holding it gives Save to Photos, Copy
     * and Share, and in a desktop browser right-clicking it gives Save image
     * as. On both of those the button is a third way to do something the
     * platform already does better — and the row it sits in is the space the
     * suggestions below need in order to wrap. Asked for as exactly that:
     * remove it on iOS and on the container, because iOS can long press.
     *
     * The ANDROID app keeps it. Its WebView has no long-press save at all, and
     * the shell removes the anchor there anyway when scoped storage makes it a
     * no-op — so what is drawn is decided in one place rather than two.
     *
     * "android" is the default variant, so a page that has not been told what
     * it is running on keeps the button. Removing a control on a guess is the
     * wrong way round.
     */
    const savesItself = isIOS || setup.variant === "server";
    if (!savesItself) {
      const a = document.createElement("a");
      a.className = "";
      a.href = URL.createObjectURL(current.blob);
      a.download = name;
      a.innerHTML = "<span>Download</span>";
      actions.appendChild(a);
    }

    // One button per configured webhook, then the way to add more.
    for (const hook of webhooks) {
      const b = button("", hook.name, "send");
      b.onclick = () => postTo(hook, b);
      actions.appendChild(b);
    }
    /*
     * NO COG HERE ANY MORE. Setting a webhook up moved to Settings, under the
     * cog in the header, alongside Services, Reviews and Zones — one place for
     * configuration instead of a button that lives on the row that vanishes
     * whenever there is no card. POSTING a card stays right here: it is the
     * everyday action and must not cost a trip through a menu, which is why
     * the loop above is untouched.
     */

    // A notice from a source — "enable this extension in Roon" — outranks the
    // iOS tip and must NOT be cleared here. This line used to assign
    // unconditionally, so on Android it wiped the notice a moment after it was
    // set and a first Roon run looked like an app that simply ignored Roon.
    if (noticeText) {
      hintEl.textContent = noticeText;
    } else if (isIOS) {
      hintEl.textContent = "Press and hold the card to copy it, save it to Photos or share it.";
    } else if (savesItself) {
      // The container, browsed from somewhere else. The Download button is
      // gone because the browser does this better — but a control that is
      // simply absent teaches nobody, so the row it left says what to do
      // instead.
      hintEl.textContent = "Right-click or press and hold the card to save or copy it.";
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
    // Alone in the row and the one thing worth doing at that moment, so it has
    // the width for an icon where the everyday row does not.
    b.innerHTML = icon("search") + b.innerHTML;
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
    // And what the similar-artist lookup was asked, and which source answered.
    // An empty row has three causes that look identical from the page, and
    // only one of them is not a bug — see Similar.attempts().
    section("Similar artists", d.similar);
    // What became of each cover. A card with no sleeve says nothing about why,
    // and the four causes — no art url, a refused host, a 404, or bytes that
    // were not an image — want four different fixes.
    section("Album art", d.art);
    // Every attempt to queue a suggestion into Roon, and where it stopped.
    // Reported from the field as a record that IS in the library not arriving
    // in the queue, against a chain with seven places to stop — and nothing
    // anywhere saying which. See RoonBrowse.attempts().
    // Named for the ACTION, not for one source: Roon and Lyrion both land
    // here and each line says which it came from.
    section("Queueing a suggestion", d.queue);
    // What the Discover screen was able to find. An empty screen there has
    // three causes that look identical from it — nothing heard yet, neither
    // endpoint answering, and a window with nothing in it — and only the first
    // is not a bug. Neither endpoint has ever been reached from where this was
    // written, so these lines are the first evidence anybody will have.
    section("Discover", d.discover);
    // Playing on THIS phone, which no network source can see. Named for the
    // device rather than for a source, because it is not one — see DeviceAudio.
    section("Playing on this device", d.device);
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

  async function loadWebhooks(signal) {
    try {
      const data = await getJson("/api/webhooks", signal);
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
    claimStage();
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
    const pinField = (setup.mayConfigure || setup.needsPin === false) ? "" :
      '<input class="wh-input" id="wh-pin" inputmode="numeric" placeholder="PIN from the device"' +
      ' value="' + escapeHtml(heldPin) + '">';

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

  // ---------------------------------------------------------------- settings

  /*
   * SETTINGS, AND THE SHAPE OF IT IS DELIBERATE.
   *
   * One menu, four screens, every one of them drawn into the SAME stage the
   * card uses and built from the same `.wh` furniture as the webhook panel —
   * because that panel already solved this once: short rows, one line of
   * explanation each, and the buttons above the fold on a phone. A settings
   * screen you have to scroll to finish is one people abandon half done.
   *
   * EVERY CHANGE IS A WRITE TO THE SERVER, not to this page. What is switched
   * on decides what the SERVER does — a disabled service is never looked up, a
   * disabled room is never asked — so the answer has to be the same on the
   * phone, the iPad and the browser on the machine itself. That is also why it
   * is not localStorage, which is where the one genuinely per-device
   * preference (the held-chip service tick) still lives.
   */
  /*
   * DISCOVER — NEW RECORDS, AS SLEEVES.
   *
   * Asked for as "new music based on listening", drawn as cover art and
   * nothing else: tap a sleeve and the links open. That is not a style choice,
   * it is the legal position. A title and an artist are facts and a sleeve
   * identifies the record the same way the card already does; everything
   * anybody has WRITTEN about it stays a link to whoever wrote it. No article
   * text is reproduced here and no route could return any.
   *
   * THE SERVER DECIDES WHAT IS ON IT AND WHY. The page draws `why` rather than
   * working it out — "Because you played Slint" against "New this week" is the
   * difference between this screen meaning what its name says and being a
   * new-releases list, and that rule lives in :core where it has tests.
   */
  let newMusicToken = 0;

  async function showNewMusic(force) {
    // Before any await, like every other screen: a load() already in flight
    // will otherwise paint the card over this one. See claimStage.
    claimStage();
    clearCardRows();
    errEl.textContent = "";
    selectTab("new");
    const mine = ++newMusicToken;
    show('<div class="placeholder"><div class="spinner"></div>' +
      "<div>Looking for new music\u2026</div></div>");

    let picks = [];
    try {
      const data = await getJson("/api/new" + (force ? "?refresh=1" : ""));
      picks = (data && Array.isArray(data.picks)) ? data.picks : [];
    } catch (e) {
      if (mine !== newMusicToken) return;
      message("Could not look for new music.");
      errEl.textContent = (e && e.message) ? e.message : String(e);
      return;
    }
    if (mine !== newMusicToken) return;

    if (!picks.length) {
      /*
       * AN EMPTY SCREEN MUST NAME THE RIGHT CAUSE. "Nothing new" is a lie when
       * the truth is "this app has not seen you play anything yet" — the same
       * mistake as "no players found" for a household that is simply switched
       * off, which sent somebody to hosts.txt and VLANs for a problem whose
       * fix was two taps.
       */
      message("Nothing new yet.");
      hintEl.textContent =
        "This fills up as you play things \u2014 it is based on what this app " +
        "has made a card for. It also needs a route to the internet.";
      return;
    }

    const grid = document.createElement("div");
    grid.className = "newgrid";
    for (const pick of picks) grid.appendChild(newTile(pick));
    showNode(grid);
    hintEl.textContent = "Tap a sleeve to read about it or find it.";
  }

  /** One sleeve, its record, and why it is on this screen. */
  function newTile(pick) {
    const tile = document.createElement("button");
    tile.className = "newtile";
    const art = pick.art
      ? '<img class="newtile-art" src="' + escapeHtml(pick.art) + '" alt="" loading="lazy">'
      // A record with no sleeve is still a record. The tile keeps its shape so
      // the grid stays a grid.
      : '<div class="newtile-art blank">\u266a</div>';
    tile.innerHTML = art +
      '<span class="newtile-name">' + escapeHtml(pick.album) + "</span>" +
      '<span class="newtile-act">' + escapeHtml(pick.artist) + "</span>" +
      '<span class="newtile-why' + (pick.heard ? " heard" : "") + '">' +
      escapeHtml(pick.why || "") + "</span>";
    tile.onclick = () => showNewRecord(pick);
    return tile;
  }

  /*
   * ONE RECORD, AND EVERYWHERE TO READ ABOUT IT OR HEAR IT.
   *
   * The links are the SAME ones the card has, built by the same server route,
   * so a service switched off in Settings is switched off here too and the
   * encoding rules have one home. This screen adds no new place a URL is
   * built.
   */
  async function showNewRecord(pick) {
    claimStage();
    clearCardRows();
    errEl.textContent = "";
    const mine = ++newMusicToken;
    show('<div class="placeholder"><div class="spinner"></div>' +
      "<div>Looking it up\u2026</div></div>");

    let extras = EMPTY;
    try {
      const params = new URLSearchParams({ album: pick.album, artist: pick.artist });
      extras = extrasOf(await getJson("/api/extras?" + params));
    } catch (e) { /* the sleeve and the name are still worth showing */ }
    if (mine !== newMusicToken) return;

    const panelEl = document.createElement("div");
    panelEl.className = "newone";
    panelEl.innerHTML =
      (pick.art
        ? '<img class="newone-art" src="' + escapeHtml(pick.art) + '" alt="">'
        : '<div class="newone-art blank">\u266a</div>') +
      '<p class="newone-name">' + escapeHtml(pick.album) + "</p>" +
      '<p class="newone-act">' + escapeHtml(pick.artist) +
      (pick.released ? " \u00b7 " + escapeHtml(pick.released) : "") + "</p>";
    showNode(panelEl);

    /*
     * THE REVIEW THIS RECORD CAME FROM, WHEN IT CAME FROM ONE.
     *
     * A LINK AND NOTHING ELSE — no headline, no excerpt, not a line of the
     * piece. The record was identified out of a feed and the writing stays
     * with whoever wrote it.
     *
     * IT REPLACES THE LOOKUP'S GUESS RATHER THAN SITTING BESIDE IT, AND A
     * RENDER IS WHAT FOUND THAT. Added as an extra chip, a record that came
     * from Pitchfork's own feed drew TWO chips both reading "Pitchfork
     * review" — one the publisher's own link, one a URL this app built out of
     * an artist and an album slug, and nothing on the row to tell a reader
     * which was which. The publisher's link is the one that is certainly
     * right: it is where the feed said the review is. So it takes the review
     * slot, names itself, and any same-publisher chip the lookup added is
     * dropped from behind it.
     *
     * `readAtName` is one of [Editorial.FEEDS]'s own names — a constant in
     * this app, never a string off the feed — which is what keeps the chip
     * label short enough for a quarter of a phone. See LinkChipsTest.
     */
    const withReview = pick.readAt && pick.readAtName
      ? Object.assign({}, extras, {
        reviewUrl: pick.readAt,
        reviewName: pick.readAtName + " review",
        reading: (extras.reading || [])
          .filter((r) => r && r.name !== pick.readAtName)
      })
      : extras;
    // The row under the card, unchanged and in the same place, so there is one
    // set of rules about which links appear and what they promise.
    buildLinks(withReview);
    hintEl.textContent = pick.why || "";
    actions.innerHTML = "";
    const back = button("", "Back to Discover", "");
    back.onclick = showNewMusic;
    actions.appendChild(back);
  }

  /** Which tab is lit. The card's own screens all put it back on Playing. */
  function selectTab(which) {
    const onNew = which === "new";
    tabNew.setAttribute("aria-selected", onNew ? "true" : "false");
    tabCard.setAttribute("aria-selected", onNew ? "false" : "true");
    tabNew.classList.toggle("on", onNew);
    tabCard.classList.toggle("on", !onNew);
  }

  function showSettings() {
    /*
     * CLAIMED BEFORE ANY await, AND THE MENU ASKS THE SERVER FOR NOTHING.
     *
     * Both halves were wrong in the first cut and the symptom was the same: a
     * cog that did nothing. The screen claimed the stage inside `panel()`,
     * which is reached only AFTER an await — so during a first-run discovery
     * sweep the very first await queued behind it and the menu never drew.
     * Claiming first is what frees the connection; asking for nothing is what
     * makes the menu instant even when the server is busy.
     */
    claimStage();
    errEl.textContent = "";
    panel(
      menuRow("settings-services", "Services", "Which streaming links appear under the card"),
      menuRow("settings-reviews", "Reviews", "Album reviews and scores"),
      menuRow("settings-zones", "Zones", "Which rooms this app may show"),
      menuRow("settings-webhooks", "Webhooks", "Post the card to a Discord channel"),
      /*
       * THE REPORT HAD NO WAY IN ON A WORKING APP, AND THAT IS WHAT IT IS FOR.
       *
       * `offerDiagnostics()` draws its button only when discovery has failed
       * outright (`noPlayers && !notices.length`), which was right for a
       * "why can't it see my speakers" control. But /api/debug answers far
       * more than that — which build this is, what each room reported, why a
       * score or a sleeve or a suggestion did not arrive — and every one of
       * those questions comes up on an app that is working perfectly. So the
       * only route was typing the path into a browser, which is exactly the
       * complaint the version line at the foot of this menu was added to fix.
       *
       * `DiagnosticsDrawnTest` made this HARDER to see rather than easier: it
       * proves the page can draw every key the report can carry, which reads
       * as "the report is fine" while nobody could reach it.
       */
      menuRow("settings-diagnostics", "Diagnostics", "What this app found, and what it did not"),
      // NO PIN FIELD ON THE MENU — nothing here writes — but there must be a
      // way out. The first cut had none, and the only route back to the card
      // was reloading the page.
      '<div class="wh-buttons"><button id="set-back">Done</button></div>',
      /*
       * WHICH BUILD THIS IS, AND WHERE IT LIVES.
       *
       * The version was only ever in /api/debug, which is a wall of facts
       * somebody has to be told to open — so "which version are you on" was a
       * question every bug report started with. It is one line at the foot of
       * the one screen people already go to.
       *
       * Asked for alongside a link to the project page, which is also where
       * the release notes and the Docker instructions are.
       */
      '<p class="set-foot">MusicD Share Card ' + escapeHtml(setup.version || "") +
      ' &middot; <a href="' + PROJECT_URL + '" rel="noreferrer">' +
      escapeHtml(PROJECT_HOST) + "</a></p>"
    );
    bind("settings-services", showServices);
    bind("settings-reviews", showReviews);
    bind("settings-zones", showZones);
    bind("settings-webhooks", showWebhookSettings);
    bind("settings-diagnostics", showDiagnostics);
    bind("set-back", () => load(false));
  }

  /** A row in the settings menu: a name, a line saying what it is, a chevron. */
  function menuRow(id, name, note) {
    return '<button class="set-row" id="' + id + '">' +
      '<span class="set-row-text"><b>' + escapeHtml(name) + "</b>" +
      '<span class="set-row-note">' + escapeHtml(note) + "</span></span>" +
      '<span class="set-chev" aria-hidden="true">›</span></button>';
  }

  /**
   * The frame every settings screen is drawn in.
   *
   * It empties the action row, the hint and the caption itself, exactly as the
   * webhook panel does — those describe a card, and there is no card here.
   */
  function panel(...parts) {
    claimStage();
    show('<div class="wh set">' + parts.join("") + "</div>");
    clearCardRows();
  }

  /*
   * EVERYTHING BELOW THE STAGE BELONGS TO THE CARD, AND ANOTHER SCREEN MUST
   * TAKE IT DOWN.
   *
   * The caption, the action row, the links and the suggestions all describe a
   * record, and a screen that is not about a record has no business leaving
   * them up. Drawn over the Discover grid on the first cut: the sleeves came
   * in, and under them sat "Playing in SR11 · via Roon", a Download button and
   * two rows of links about something else entirely — and because those rows
   * still had their height, the grid was squeezed into a strip and its first
   * row of sleeves was clipped.
   *
   * One function rather than four lines in each screen, because the fourth
   * screen is where somebody forgets one.
   */
  function clearCardRows() {
    actions.innerHTML = "";
    hintEl.textContent = "";
    nowEl.innerHTML = "";
    linksEl.innerHTML = "";
    linksEl.classList.add("hidden");
    simEl.innerHTML = "";
    simEl.classList.add("hidden");
  }

  /**
   * TAKE THE STAGE OFF A load() THAT HAS NOT FINISHED YET.
   *
   * Found by opening Settings on a household that was still being discovered:
   * the menu drew, and a second later the request that was already in flight
   * came back and painted its own answer straight over it. The screen did not
   * fail — it appeared and then silently vanished, which reads as a button
   * that does not work.
   *
   * `token` is the mechanism this page already has for "somebody has moved
   * on", and a second press of Refresh uses it the same way; bumping it makes
   * the pending load discard its own answer when it lands. It also has to
   * clear `busy`, because that load will check the token before re-enabling
   * the Refresh button and will decide the job is no longer its to finish.
   */
  function claimStage() {
    ++token;
    // AND LET GO OF WHAT THE CARD WAS WAITING ON. Bumping the token alone
    // makes the answer be discarded when it arrives, which is not the same as
    // not waiting for it: the connection stays held, and the request this
    // screen needs queues behind one whose answer is already unwanted.
    if (cardRequests) { cardRequests.abort(); cardRequests = null; }
    busy(false);
  }

  function bind(id, fn) {
    const el = document.getElementById(id);
    if (el) el.onclick = fn;
  }

  /**
   * Back, and the PIN field that has to sit beside it.
   *
   * A browser that is not on the device running this cannot configure without
   * the PIN — see the server's Access gate, which trusts loopback and nothing
   * else. The field carries the same id the webhook panel uses, so `pinParam`
   * reads it without needing to know which screen asked.
   */
  function backRow() {
    // Redrawn WITH what was typed, for the reason in [heldPin]: a field that
    // empties itself on every change is one that stops working after the
    // first, silently.
    // NOTHING TO TYPE WHERE NOTHING IS ASKED. The container trusts its own
    // network unless a PIN is configured, so drawing an input there would be
    // a box that does nothing sitting under every settings screen.
    const pinField = (setup.mayConfigure || setup.needsPin === false) ? "" :
      '<p class="wh-note">Changes need the PIN from the device running Share Card' +
      " — in Docker, set SHARECARD_PIN or read it from the log.</p>" +
      '<div class="wh-row"><input class="wh-input" id="wh-pin" inputmode="numeric"' +
      ' placeholder="PIN from the device" value="' + escapeHtml(heldPin) + '"></div>';
    return pinField +
      '<div class="wh-buttons"><button id="set-back">Back</button></div>' +
      (setup.onDevice && setup.pin
        ? '<p class="wh-note">PIN for other devices: <b>' + escapeHtml(setup.pin) + "</b></p>"
        : "");
  }

  /** Ask the server who we are before drawing a screen that can write. */
  async function refreshSetup() {
    try {
      setup = await getJson("/api/setup");
    } catch (e) { /* fall back to what the last answer said */ }
  }

  /**
   * A row with a switch on it.
   *
   * The input is a real checkbox rather than a div that looks like one, so it
   * is reachable by keyboard and announced as a switch; the stylesheet draws
   * the track and the knob over it.
   */
  function toggleRow(id, label, note, on) {
    return '<label class="set-toggle"><span class="set-row-text"><b>' +
      escapeHtml(label) + "</b>" +
      (note ? '<span class="set-row-note">' + escapeHtml(note) + "</span>" : "") +
      "</span>" +
      '<input type="checkbox" data-key="' + escapeHtml(id) + '"' + (on ? " checked" : "") +
      '><span class="set-switch" aria-hidden="true"></span></label>';
  }

  /**
   * Send one change and redraw from what the server says came back.
   *
   * DELIBERATELY ONE KEY AT A TIME. The body names only what changed, so two
   * people with this open cannot overwrite each other's unrelated choices with
   * a stale snapshot of everything.
   */
  async function writeSetting(group, key, value, redraw) {
    errEl.textContent = "";
    const body = {};
    body[group] = {};
    body[group][key] = value;
    try {
      const response = await fetch("/api/settings" + pinParam(), {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body)
      });
      const answer = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(answer.error || ("Refused (" + response.status + ")"));
      settings = answer;
      await redraw();
    } catch (e) {
      /*
       * THE MESSAGE IS SET AFTER THE REDRAW, NOT BEFORE IT, AND THAT WAS THE
       * WHOLE BUG.
       *
       * The switch has to go back — leaving it flipped would be a lie about
       * what is switched on — and putting it back means redrawing the screen.
       * But every screen clears the error line as it opens, so setting the
       * message first and redrawing second wiped the one explanation there
       * was. What a person actually saw was a switch that flicked back and
       * said nothing at all, which reads as an app that ignores you.
       *
       * Exactly the shape of `buildActions` clearing `hintEl` unconditionally
       * and silencing a Roon notice a moment after it was set.
       */
      await redraw();
      errEl.textContent = e.message || String(e);
    }
  }

  /**
   * @param withZones ask for the room list too, which costs a discovery sweep.
   *   Services and Reviews must not: they need nothing from the network, and
   *   waiting on one made them look like screens that did not open.
   */
  async function loadSettings(withZones) {
    try {
      settings = await getJson("/api/settings" + (withZones ? "?zones=1" : ""));
    } catch (e) {
      settings = { services: [], zones: [], anyZoneEnabled: false };
    }
  }

  /**
   * The whole report, reached from the menu rather than only from a failure.
   *
   * `report()` does the drawing — the same function `offerDiagnostics()` has
   * always called — so there is ONE renderer and `DiagnosticsDrawnTest` still
   * covers it. This adds a door, not a second copy.
   *
   * It says so while it waits, because this is the one screen that genuinely
   * takes seconds: the report asks every source and every enabled room, which
   * is a discovery sweep on a cold cache.
   */
  async function showDiagnostics() {
    // Before the await, so the card's pending request is let go of rather than
    // holding the connection this screen needs. See claimStage.
    claimStage();
    errEl.textContent = "";
    hintEl.textContent = "Asking every source\u2026";
    try {
      report(await getJson("/api/debug"));
    } catch (e) {
      errEl.textContent = (e && e.message) ? e.message : String(e);
    } finally {
      hintEl.textContent = "";
    }
  }

  async function showServices() {
    // Before the awaits below, so the card's pending request is let go of
    // rather than holding the connection this screen needs.
    claimStage();
    errEl.textContent = "";
    await refreshSetup();
    await loadSettings(false);
    const rows = (settings.services || [])
      .map((s) => toggleRow(s.id, s.name, "", s.enabled))
      .join("");
    panel(
      '<p class="wh-note">Switched off, a service is not linked under the card ' +
      "and is not looked up.</p>",
      rows,
      backRow()
    );
    bind("set-back", showSettings);
    for (const box of document.querySelectorAll('.set-toggle input[data-key]')) {
      box.onchange = () =>
        writeSetting("services", box.getAttribute("data-key"), box.checked, showServices);
    }
  }

  async function showReviews() {
    // Before the awaits below, so the card's pending request is let go of
    // rather than holding the connection this screen needs.
    claimStage();
    errEl.textContent = "";
    await refreshSetup();
    await loadSettings(false);

    const all = settings.reviews || [];
    const group = (kind) => all.filter((r) => r.kind === kind)
      .map((r) => toggleRow(r.id, r.name, "", r.enabled))
      .join("");

    /*
     * TWO GROUPS, AND THEY DEFAULT DIFFERENTLY. The album sources are what the
     * card has always drawn — the blurb under the cover and the score in
     * the corner — so they start on; switching them off by default would
     * empty every card in the house to make this screen tidy. The artist ones
     * are about whoever made the record rather than the record itself, so they
     * are asked for.
     */
    panel(
      // BOTH GROUPS ARE HEADED NOW. The artist half had a heading and the
      // album half did not — it opened straight into a sentence beginning
      // "About the record", which read as a caption for the screen rather
      // than as the name of the group above the switches. Same element, same
      // colour, so the two halves look like two halves.
      '<h3 class="set-group">About the albums</h3>',
      '<p class="wh-note">Switched off, a source is not ' +
      "looked up and its words do not appear on the card.</p>",
      group("album"),
      '<h3 class="set-group">About the artist</h3>',
      '<p class="wh-note">Links only — the card stays about the record.</p>',
      group("artist"),
      backRow()
    );
    bind("set-back", showSettings);
    for (const box of document.querySelectorAll(".set-toggle input[data-key]")) {
      box.onchange = () =>
        writeSetting("reviews", box.getAttribute("data-key"), box.checked, showReviews);
    }
  }

  async function showZones() {
    // Before the awaits below, so the card's pending request is let go of
    // rather than holding the connection this screen needs.
    claimStage();
    errEl.textContent = "";

    /*
     * DRAWN BEFORE IT IS ASKED, because this is the one settings screen that
     * genuinely has to wait: a list of discovered rooms cannot be produced
     * without discovering them, and on a first run that is the slowest thing
     * this app does. Without this the screen is blank for the whole sweep,
     * which is indistinguishable from a menu item that does nothing.
     */
    panel(
      '<div class="placeholder"><div class="spinner"></div>' +
      "<div>Looking for rooms\u2026</div></div>"
    );

    const mine = token;
    await refreshSetup();
    await loadSettings(true);
    // Somebody left while the sweep ran. Their screen is not ours to replace.
    if (mine !== token) return;
    const zones = settings.zones || [];

    const rows = zones
      .map((z) => toggleRow(z.uid, z.name, z.source || "", z.enabled))
      .join("");

    /*
     * A HOUSE WITH NOTHING IN IT YET IS NOT AN ERROR. A television that is
     * powered off has not answered discovery, so it is simply not here — and
     * saying that is the difference between "wait and press Refresh" and an
     * evening spent on the network.
     */
    const note = zones.length
      ? '<p class="wh-note">Only the rooms switched on here appear in the picker. ' +
        "Anything powered off joins this list when it answers.</p>"
      : '<p class="wh-note">No rooms have answered yet. Anything powered off ' +
        "appears here once it does — press Refresh to look again.</p>";

    panel(note, rows, backRow());
    bind("set-back", showSettings);
    for (const box of document.querySelectorAll('.set-toggle input[data-key]')) {
      box.onchange = () =>
        writeSetting("zones", box.getAttribute("data-key"), box.checked, showZones);
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

  /**
   * The PIN typed on this page, kept for as long as it is open.
   *
   * IT USED TO BE READ STRAIGHT OFF THE FIELD, and that was a bug with a nasty
   * shape: every settings change redraws the screen from the server's answer,
   * which recreates the field EMPTY — so the first switch after typing the PIN
   * worked and every one after it was refused. Reported as changes that would
   * not stick on Docker, where every browser is a remote one and the PIN is
   * always required. Measured in a real browser: Spotify off succeeded, Deezer
   * off a moment later did not.
   */
  let heldPin = "";

  function pinParam() {
    const field = document.getElementById("wh-pin");
    if (field && field.value) heldPin = field.value.trim();
    return heldPin ? "?pin=" + encodeURIComponent(heldPin) : "";
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
      /*
       * ONLY ON THE DEVICE ITSELF — UNLESS THE UPDATE IS THE SERVER'S.
       *
       * The APK installs HERE, on the machine running the app, so an update
       * bar on an iPad across the house offers to replace software on
       * something else. That was reported as "shows the update button, does
       * nothing" and is why this hides off the socket address.
       *
       * A container update is not device-specific: it replaces the machine
       * serving this page, which is the same machine whichever browser asked
       * — and that machine usually has no browser on it at all, so hiding the
       * bar from every other device would hide it from everybody. The server
       * says which case it is rather than the page guessing.
       */
      const state = await getJson("/api/update/status");
      if (!state || !(state.onDevice || state.fromAnyDevice)) {
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
    /*
     * THE LAST STEP IS NOT THE SAME STEP ON BOTH BUILDS, and saying it was
     * left a container stuck under "Android is asking you to confirm" for
     * ever. Nothing is asking: Android hands an APK to the system installer
     * and waits for a human, while the container has already unpacked the new
     * build and is about to exit so the launcher can start it. Reported from
     * a Docker install, where there is no Android in the picture at all.
     */
    const onServer = state.variant === "server";
    const busyText = {
      checking: "Checking\u2026",
      downloading: "Downloading\u2026",
      verifying: "Checking the download\u2026",
      installing: onServer
        ? "Restarting into the new version\u2026"
        : "Android is asking you to confirm\u2026"
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
  /**
   * Follow an update that is already running. Ends when it does.
   *
   * A BOUNDED POLL DURING SOMETHING SOMEBODY JUST PRESSED, which is not the
   * timer the no-polling rule forbids: that one interrogates the household all
   * day to answer a question nobody is reading. This one has a beginning, an
   * end and a person watching it.
   */
  function watchUpdate() {
    let restarting = false;
    let tries = 0;
    const timer = setInterval(async () => {
      /*
       * THE SERVER GOING AWAY IS THE UPDATE WORKING, not the update failing.
       * The container exits so its launcher can start the build it just
       * unpacked, so the status request fails for a few seconds by design.
       * Giving up there is what froze the bar on the last message it managed
       * to read.
       */
      if (++tries > MAX_UPDATE_POLLS) {
        clearInterval(timer);
        if (restarting) {
          updateEl.innerHTML = '<span class="update-text">' +
            "Still restarting. Reload the page in a moment.</span>";
        }
        return;
      }

      let state;
      try {
        state = await getJson("/api/update/status");
      } catch (e) {
        if (restarting) return;
        clearInterval(timer);
        return;
      }

      if (restarting) {
        // It answered again, so the new build is up. Reload rather than patch
        // the bar: everything on this page came from the old one.
        clearInterval(timer);
        location.reload();
        return;
      }

      showUpdate(state);
      const phase = (state.phase && state.phase.name) || "idle";
      if (phase === "installing" && state.variant === "server") {
        restarting = true;
        return;
      }
      if (phase !== "downloading" && phase !== "verifying") clearInterval(timer);
    }, 1500);
  }

  /** Ninety seconds at 1.5s a go. A restart that takes longer has gone wrong. */
  const MAX_UPDATE_POLLS = 60;

  // ---------------------------------------------------------------- wiring

  /*
   * REFRESH REFRESHES THE SCREEN YOU ARE LOOKING AT.
   *
   * It called load(true) unconditionally, so pressing it on Discover threw
   * away the sleeves and drew the card instead — which reads as the button
   * navigating rather than refreshing, and left Discover with no way to ask
   * again at all now that it remembers its answer. The tab decides.
   */
  refresh.addEventListener("click", () => {
    if (tabNew.getAttribute("aria-selected") === "true") showNewMusic(true);
    else load(true);
  });
  settingsBtn.addEventListener("click", showSettings);
  tabCard.addEventListener("click", () => load(false));
  tabNew.addEventListener("click", showNewMusic);
  zoneSel.addEventListener("change", () => {
    // From here on the picker outranks whatever the server remembers, which
    // is what lets "Whatever's playing" mean it.
    pickerUsed = true;
    load(false);
  });

  /*
   * NOTHING REDRAWS THE CARD BUT THE REFRESH BUTTON.
   *
   * There is no timer — this runs on a device that is never switched off, and
   * a poll would mean asking a speaker what it is doing every few seconds for
   * the rest of the day to answer a question nobody is in the room to read.
   *
   * AND THERE IS NO visibilitychange EITHER, which is the part that had to be
   * taken back out. Redrawing when the page came back sounded like the same
   * rule — ask at the moment somebody wants to know — but it is not what it
   * does. Going to the Home Screen and returning fires it, so the card you
   * were looking at was thrown away and replaced by a spinner every single
   * time, and a card you had deliberately left up to send or show somebody
   * could not survive a glance at anything else. Leaving the app is not a
   * request for a different record.
   *
   * The cost is a card that can be stale, which is the correct trade: it says
   * what it is a picture of, and Refresh is one tap away.
   */

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
