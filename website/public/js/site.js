(function () {
  var veil = document.querySelector(".veil");
  var nav = document.querySelector(".nav");
  var reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  var stage = document.querySelector("[data-stage]");
  var frame = document.querySelector("[data-frame]");
  var statusNode = document.querySelector("[data-status]");
  var disconnectBox = document.querySelector("[data-disconnected]");
  var disconnectReason = document.querySelector("[data-disconnect-reason]");
  var reconnectBtn = document.querySelector("[data-reconnect]");
  var loadingBox = document.querySelector("[data-loading]");
  var loadingStatus = document.querySelector("[data-loading-status]");
  var loadingTimer = document.querySelector("[data-loading-timer]");
  var loadingHint = document.querySelector("[data-loading-hint]");
  var loadingRefresh = document.querySelector("[data-loading-refresh]");
  var joinAddress = "ws://127.0.0.1:25565";
  var joinReady = false;
  var queued = false;
  var started = false;

  if (nav) {
    var onScroll = function () {
      nav.classList.toggle("is-scrolled", window.scrollY > 8);
    };
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
  }

  var menuBtn = document.querySelector("[data-menu]");
  if (menuBtn && nav) {
    menuBtn.addEventListener("click", function () {
      var open = nav.classList.toggle("is-open");
      menuBtn.setAttribute("aria-expanded", open ? "true" : "false");
    });
    nav.addEventListener("click", function (event) {
      if (!event.target.closest("a")) return;
      nav.classList.remove("is-open");
      menuBtn.setAttribute("aria-expanded", "false");
    });
  }

  function closeDrops(except) {
    var drops = document.querySelectorAll(".drop");
    for (var i = 0; i < drops.length; i++) {
      if (drops[i] === except) continue;
      drops[i].classList.remove("is-open");
      var button = drops[i].querySelector("button");
      if (button) button.setAttribute("aria-expanded", "false");
    }
  }
  var dropButtons = document.querySelectorAll(".drop > button");
  for (var d = 0; d < dropButtons.length; d++) {
    dropButtons[d].addEventListener("click", function (event) {
      event.stopPropagation();
      var drop = event.currentTarget.parentElement;
      var open = !drop.classList.contains("is-open");
      closeDrops(open ? drop : null);
      drop.classList.toggle("is-open", open);
      event.currentTarget.setAttribute("aria-expanded", open ? "true" : "false");
    });
  }
  document.addEventListener("click", function (event) {
    if (event.target.closest(".drop")) return;
    closeDrops(null);
  });

  var helpSearch = document.querySelector("[data-help-search]");
  if (helpSearch) {
    var articles = document.querySelectorAll("[data-article]");
    var filterHelp = function () {
      var query = helpSearch.value.trim().toLowerCase();
      for (var i = 0; i < articles.length; i++) {
        var hay = (articles[i].getAttribute("data-article") || "") + " " + articles[i].textContent;
        articles[i].hidden = query.length > 0 && hay.toLowerCase().indexOf(query) < 0;
      }
    };
    helpSearch.addEventListener("input", filterHelp);
    var preset = new URLSearchParams(location.search).get("q");
    if (preset) {
      helpSearch.value = preset;
      filterHelp();
    }
  }

  document.addEventListener("click", function (event) {
    var link = event.target.closest("a");
    if (!link || !veil || link.hasAttribute("data-launch")) return;
    if (event.defaultPrevented || event.button !== 0) return;
    if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    if (link.target && link.target !== "_self") return;
    var url = new URL(link.href, location.href);
    if (url.origin !== location.origin) return;
    if (url.pathname === location.pathname && url.search === location.search) return;
    event.preventDefault();
    veil.classList.add("is-on");
    window.setTimeout(function () { location.href = link.href; }, reduced ? 0 : 180);
  });

  function paintStatus(online, text) {
    if (!statusNode) return;
    statusNode.classList.toggle("is-on", !!online);
    statusNode.classList.toggle("is-off", !online);
    var label = statusNode.querySelector("[data-status-label]");
    if (label) label.textContent = text;
  }

  // ---- Loading overlay: first visit downloads ~57 MB, compiles WASM, then connects. Say what is going on.
  var HINTS_DOWNLOAD = [
    "This can take a minute the first time. The game is about 57 MB.",
    "Nothing is frozen: the game is downloading. Next visits open much faster.",
    "Hang on. Once it is downloaded it is saved in your browser."
  ];
  var loadState = null;

  function mmss(sec) {
    sec = Math.max(0, Math.floor(sec));
    return Math.floor(sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
  }

  function loadingText(node, text) {
    if (node && node.textContent !== text) node.textContent = text;
  }

  function loadingStop() {
    if (!loadState) return;
    window.clearInterval(loadState.tick);
    window.clearInterval(loadState.hook);
    loadState = null;
    if (loadingBox) loadingBox.classList.remove("is-on");
  }

  function hookSocket() {
    if (!loadState || !frame) return;
    var win, doc, Orig;
    try {
      win = frame.contentWindow;
      if (!win || !win.location || win.location.pathname.indexOf("/client/") !== 0) return;
      doc = win.document;
      if (!doc || doc === loadState.hookedDoc || doc === loadState.staleDoc) return;
      Orig = win.WebSocket;
      if (!Orig) return;
      var Wrapped = function (url, protocols) {
        var sock = protocols === undefined ? new Orig(url) : new Orig(url, protocols);
        try {
          if (loadState) loadState.wsSeen = true;
          sock.addEventListener("open", function () { if (loadState) loadState.wsOpen = true; });
        } catch (e) {}
        return sock;
      };
      Wrapped.prototype = Orig.prototype;
      Wrapped.CONNECTING = Orig.CONNECTING;
      Wrapped.OPEN = Orig.OPEN;
      Wrapped.CLOSING = Orig.CLOSING;
      Wrapped.CLOSED = Orig.CLOSED;
      win.WebSocket = Wrapped;
      loadState.hookedDoc = doc;
      window.clearInterval(loadState.hook);
      loadState.hook = 0;
    } catch (e) {}
  }

  function loadingTick() {
    if (!loadState) return;
    var now = Date.now();
    var el = (now - loadState.t0) / 1000;
    var hasCanvas = false;
    var bootReady = false;
    try {
      var doc = childDoc();
      if (doc && loadState.frameSet && doc !== loadState.staleDoc) {
        hasCanvas = !!(doc.querySelector && doc.querySelector("canvas"));
        // The game has its own boot screen (#loading_screen); it gets .is-ready once the game screen is up.
        var boot = doc.getElementById && doc.getElementById("loading_screen");
        bootReady = !!(boot && boot.classList && boot.classList.contains("is-ready"));
      }
    } catch (e) {}
    if (hasCanvas && !loadState.canvasAt) loadState.canvasAt = now;
    loadingText(loadingTimer, mmss(el));

    // Game screen is up (first-time players see the profile screen, returning players connect): hand over.
    if (loadState.wsOpen || bootReady) {
      loadingText(loadingStatus, loadState.wsOpen ? "Connected. Entering the world\u2026" : "Ready!");
      loadingText(loadingHint, "");
      if (!loadState.openAt) loadState.openAt = now;
      if (now - loadState.openAt > 700) loadingStop();
      return;
    }
    if (el > 300) { loadingStop(); return; }
    if (el > 90) {
      loadingText(loadingStatus, "Still loading\u2026 If nothing happens, refresh the page.");
      loadingText(loadingHint, "A slow connection can make the first load take a few minutes.");
      if (loadingRefresh) loadingRefresh.hidden = false;
      return;
    }
    if (!loadState.frameSet) {
      loadingText(loadingStatus, "Loading Catharsis\u2026");
      loadingText(loadingHint, HINTS_DOWNLOAD[0]);
    } else if (!loadState.frameLoaded) {
      loadingText(loadingStatus, loadState.reconnect ? "Connecting to the server\u2026" : "Downloading the game\u2026");
      loadingText(loadingHint, loadState.reconnect ? "" : HINTS_DOWNLOAD[Math.floor(el / 9) % HINTS_DOWNLOAD.length]);
    } else if (!loadState.wsSeen) {
      loadingText(loadingStatus, "Starting the game\u2026");
      loadingText(loadingHint, "Getting everything ready. This takes a few seconds.");
      // If the game never reports ready (client changed), do not cover it forever once its canvas is up.
      if (loadState.canvasAt && now - loadState.canvasAt > 25000) loadingStop();
    } else {
      loadingText(loadingStatus, "Connecting to the server\u2026");
      loadingText(loadingHint, "Almost there.");
    }
  }

  function loadingBegin() {
    if (!loadingBox || loadState) return;
    loadState = { t0: Date.now(), frameSet: false, frameLoaded: false, wsSeen: false, wsOpen: false,
      openAt: 0, canvasAt: 0, hookedDoc: null, staleDoc: null, reconnect: false, tick: 0, hook: 0 };
    if (loadingRefresh) loadingRefresh.hidden = true;
    loadingBox.classList.add("is-on");
    loadState.tick = window.setInterval(loadingTick, 250);
    loadingTick();
  }

  function loadingFrameStarted(isReconnect) {
    if (isReconnect) {
      loadingStop();
      loadingBegin();
    }
    if (!loadState) return;
    // The previous game document stays in the frame until the new one commits; ignore it.
    loadState.staleDoc = childDoc();
    loadState.frameSet = true;
    loadState.frameLoaded = false;
    loadState.wsSeen = false;
    loadState.wsOpen = false;
    loadState.hookedDoc = null;
    loadState.openAt = 0;
    loadState.canvasAt = 0;
    loadState.reconnect = !!isReconnect;
    window.clearInterval(loadState.hook);
    loadState.hook = window.setInterval(hookSocket, 40);
    loadingTick();
  }

  function loadingFrameLoaded() {
    if (!loadState || !loadState.frameSet) return;
    loadState.frameLoaded = true;
    hookSocket();
    loadingTick();
  }

  if (loadingRefresh) {
    loadingRefresh.addEventListener("click", function () { window.location.reload(); });
  }

  function startGame() {
    if (!stage || !frame) return;
    loadingBegin();
    if (!joinReady) {
      queued = true;
      return;
    }
    if (!started) {
      started = true;
      var buttons = document.querySelectorAll("[data-launch]");
      for (var i = 0; i < buttons.length; i++) {
        buttons[i].setAttribute("aria-busy", "true");
        buttons[i].disabled = true;
        var label = buttons[i].querySelector("[data-label]");
        if (label) label.textContent = "Opening";
      }
      frame.addEventListener("load", function () {
        loadingFrameLoaded();
        if (reconnectBtn) {
          reconnectBtn.disabled = false;
          reconnectBtn.removeAttribute("aria-busy");
          var rLabel = reconnectBtn.querySelector("[data-label]");
          if (rLabel) rLabel.textContent = "Reconnect";
        }
        bindFrame();
        grab();
      });
      if (reconnectBtn) {
        reconnectBtn.addEventListener("click", function () {
          if (!frame) return;
          reconnectBtn.disabled = true;
          reconnectBtn.setAttribute("aria-busy", "true");
          var rLabel = reconnectBtn.querySelector("[data-label]");
          if (rLabel) rLabel.textContent = "Reconnecting";
          if (disconnectBox) {
            disconnectBox.classList.remove("is-on");
            disconnectBox.hidden = true;
          }
          frame.src = "/client/index.html?join=" + encodeURIComponent(joinAddress) + "&r=" + Date.now();
          loadingFrameStarted(true);
          grab();
        });
      }
      window.addEventListener("message", function (event) {
        if (event.origin !== location.origin || !frame || event.source !== frame.contentWindow) return;
        if (!event.data || event.data.type !== "catharsis:disconnected") return;
        loadingStop();
        if (!disconnectBox) return;
        var reason = String(event.data.reason || "").trim();
        if (disconnectReason) disconnectReason.textContent = reason || "Connection to the world closed.";
        disconnectBox.hidden = false;
        disconnectBox.classList.add("is-on");
        try { if (document.pointerLockElement && document.exitPointerLock) document.exitPointerLock(); } catch (e) {}
        try {
          var doc = childDoc();
          if (doc && doc.pointerLockElement && doc.exitPointerLock) doc.exitPointerLock();
        } catch (e) {}
      });
      frame.src = "/client/index.html?join=" + encodeURIComponent(joinAddress);
      loadingFrameStarted(false);
      stage.classList.add("is-live");
      releaseLauncherFocus();
    }
    if (location.hash !== "#play") history.replaceState(null, "", "#play");
    stage.scrollIntoView({ behavior: reduced ? "auto" : "smooth", block: "center" });
  }

  document.addEventListener("click", function (event) {
    var launch = event.target.closest("[data-launch]");
    if (!launch) return;
    event.preventDefault();
    startGame();
  });
  window.addEventListener("hashchange", function () {
    if (location.hash === "#play") startGame();
  });

  var boundDoc = null;

  function editable(node) {
    if (!node || !node.tagName) return false;
    var tag = node.tagName;
    return tag === "INPUT" || tag === "TEXTAREA" || tag === "SELECT" || node.isContentEditable;
  }

  function releaseLauncherFocus() {
    try {
      var active = document.activeElement;
      if (active && active.hasAttribute && active.hasAttribute("data-launch")) active.blur();
    } catch (e) {}
  }

  function childDoc() {
    try { return frame && frame.contentDocument; } catch (e) { return null; }
  }

  function childLocked() {
    var doc = childDoc();
    return !!(doc && doc.pointerLockElement);
  }

  function grab(event) {
    var doc = childDoc();
    if (!doc) return;
    if (event && editable(event.target)) return;
    var target = doc.querySelector("canvas") || doc.getElementById("game_frame");
    if (!target) return;
    if (target.tabIndex < 0) target.tabIndex = 0;
    try { target.focus({ preventScroll: true }); }
    catch (e) { try { target.focus(); } catch (e2) {} }
    try { if (frame.contentWindow) frame.contentWindow.focus(); } catch (e) {}
  }

  function onLock() {
    var doc = childDoc();
    var on = !!(doc && doc.pointerLockElement);
    document.documentElement.classList.toggle("is-grabbed", on);
    if (on && doc && !doc.hasFocus()) grab();
  }

  function bindFrame() {
    var doc = childDoc();
    if (!doc || doc === boundDoc) return;
    boundDoc = doc;
    doc.addEventListener("pointerdown", grab, true);
    doc.addEventListener("mousedown", grab, true);
    doc.addEventListener("pointerlockchange", onLock, true);
    var view = frame.contentWindow;
    if (view) {
      view.addEventListener("keydown", onGameKey, true);
      view.addEventListener("keyup", onGameKey, true);
      view.addEventListener("wheel", onGameWheel, { capture: true, passive: false });
    }
  }

  function forwardKey(type, e) {
    var view = frame && frame.contentWindow;
    if (!view || !view.document) return;
    var ev;
    try {
      ev = new view.KeyboardEvent(type, {
        // view = the game window, so fromGame() sees this copy and it is not forwarded again (loop).
        view: view,
        key: e.key,
        code: e.code,
        location: e.location,
        repeat: e.repeat,
        shiftKey: e.shiftKey,
        ctrlKey: e.ctrlKey,
        altKey: e.altKey,
        metaKey: e.metaKey,
        bubbles: true,
        cancelable: true
      });
      var vals = { keyCode: e.keyCode, which: e.which, charCode: e.charCode };
      Object.keys(vals).forEach(function (name) {
        var value = vals[name];
        Object.defineProperty(ev, name, { configurable: true, get: function () { return value; } });
      });
    } catch (err) {
      return;
    }
    var target = view.document.querySelector("canvas") || view.document.getElementById("game_frame") || view;
    try { target.dispatchEvent(ev); } catch (err) {}
  }

  function playing() {
    return !!(started && stage && stage.classList.contains("is-live") && (!disconnectBox || disconnectBox.hidden));
  }

  function fromGame(e) {
    return !!(frame && frame.contentWindow && e.view === frame.contentWindow);
  }

  function gameOwnsKey(e) {
    if (!playing()) return false;
    // Ctrl alone still reaches the game: it is sprint, and Ctrl+Q (drop the stack) needs the game to see it held.
    if ((e.metaKey || e.ctrlKey || e.altKey) && e.key !== "Control") return false;
    if (editable(e.target)) return false;
    if (fromGame(e) || childLocked()) return true;
    if (e.target === frame || document.activeElement === frame) return true;
    return false;
  }

  function gameShouldHear(e) {
    if (!playing()) return false;
    if (fromGame(e)) return !editable(e.target);
    if (editable(e.target)) return false;
    if (childLocked() || document.activeElement === frame) return true;
    try {
      if (frame.contentDocument && frame.contentDocument.hasFocus()) return true;
    } catch (err) {}
    var active = document.activeElement;
    return !active || active === document.body || active === document.documentElement;
  }

  // Ctrl is sprint, so Ctrl+W is "walk" and also "close tab".
  // The same chord family reloads, opens a tab, or leaves the page.
  function fatalShortcut(e) {
    var code = e.code || "";
    var key = (e.key || "").toLowerCase();
    if (e.altKey && !e.ctrlKey && !e.metaKey && (code === "F4" || key === "f4")) return true;
    if (e.altKey && !e.ctrlKey && !e.metaKey && (code === "ArrowLeft" || code === "ArrowRight" || code === "KeyD")) return true;
    if (!e.ctrlKey && !e.metaKey && (code === "F5" || key === "f5")) return true;
    if (!e.ctrlKey && !e.metaKey && !e.altKey && code === "Backspace" && !editable(e.target)) return true;
    if (!(e.ctrlKey || e.metaKey) || e.altKey) return false;
    if (code === "KeyW" || code === "KeyR" || code === "KeyT" || code === "KeyN" || code === "KeyQ" || code === "F4") return true;
    return key === "w" || key === "r" || key === "t" || key === "n" || key === "q" || key === "f4";
  }

  function chromeShortcut(e) {
    if (!(e.ctrlKey || e.metaKey) || e.altKey) return false;
    var code = e.code || "";
    var key = e.key || "";
    if (code === "KeyA" || code === "KeyD" || code === "KeyE" || code === "KeyF" || code === "KeyG" || code === "KeyH" || code === "KeyJ" || code === "KeyK" || code === "KeyL" || code === "KeyO" || code === "KeyP" || code === "KeyS" || code === "KeyU") return true;
    if (code === "Tab" || code === "PageUp" || code === "PageDown") return true;
    if (code === "Equal" || code === "Minus" || code === "Digit0" || code === "NumpadAdd" || code === "NumpadSubtract") return true;
    return key === "+" || key === "-" || key === "_" || key === "=" || key === "0";
  }

  // F1 (browser help), F12 and Ctrl+Shift+I/J/C (devtools). F3/F11 stay with the game.
  function devtoolsShortcut(e) {
    var code = e.code || "";
    var key = (e.key || "").toLowerCase();
    if (!e.ctrlKey && !e.metaKey && !e.altKey && (code === "F1" || key === "f1" || code === "F12" || key === "f12")) return true;
    if ((e.ctrlKey || e.metaKey) && e.shiftKey && !e.altKey && (code === "KeyI" || code === "KeyJ" || code === "KeyC" || key === "i" || key === "j" || key === "c")) return true;
    return false;
  }

  function onGameWheel(e) {
    if (!playing()) return;
    if (!(e.ctrlKey || e.metaKey)) return;
    e.preventDefault();
  }

  var lockedKeys = [
    "KeyW", "KeyR", "KeyT", "KeyN", "KeyQ", "KeyS", "KeyP", "KeyF", "KeyD", "KeyH", "KeyJ", "KeyU", "KeyI", "KeyC",
    "F1", "F4", "F5", "F11", "F12",
    "Digit0", "Equal", "Minus", "NumpadAdd", "NumpadSubtract",
    "ArrowLeft", "ArrowRight", "Backspace", "Tab", "PageUp", "PageDown"
  ];

  function lockKeys() {
    try {
      if (!playing() || !document.fullscreenElement) return;
      if (navigator.keyboard && navigator.keyboard.lock) {
        navigator.keyboard.lock(lockedKeys);
      }
    } catch (err) {}
  }

  function unlockKeys() {
    try {
      if (navigator.keyboard && navigator.keyboard.unlock) navigator.keyboard.unlock();
    } catch (err) {}
  }

  var holdClose = false;

  function closeChord(e) {
    var code = e.code || "";
    var key = (e.key || "").toLowerCase();
    if (e.altKey) return false;
    if (!(e.ctrlKey || e.metaKey)) return false;
    return code === "KeyW" || key === "w" || code === "F4" || key === "f4";
  }

  window.addEventListener("beforeunload", function (e) {
    if (!holdClose) return;
    e.preventDefault();
    e.returnValue = "";
  });

  function onGameKey(e) {
    if (closeChord(e)) {
      holdClose = e.type === "keydown";
      e.preventDefault();
      e.stopPropagation();
      if (playing() && gameShouldHear(e) && !fromGame(e)) forwardKey(e.type, e);
      return;
    }
    if (playing() && (fatalShortcut(e) || devtoolsShortcut(e))) {
      e.preventDefault();
      if (gameShouldHear(e) && !fromGame(e)) forwardKey(e.type, e);
      return;
    }
    if (gameShouldHear(e) && chromeShortcut(e)) {
      e.preventDefault();
      if (!fromGame(e)) forwardKey(e.type, e);
      return;
    }
    if (!gameOwnsKey(e) || fromGame(e)) return;
    if (e.type === "keydown" && e.key !== "Escape") e.preventDefault();
    forwardKey(e.type, e);
    var doc = childDoc();
    if (doc && !doc.hasFocus()) grab();
  }
  window.addEventListener("keydown", onGameKey, true);
  window.addEventListener("keyup", onGameKey, true);
  window.addEventListener("wheel", onGameWheel, { capture: true, passive: false });

  var full = document.querySelector("[data-fullscreen]");
  if (full && stage) {
    var syncFull = function () {
      var on = document.fullscreenElement === stage;
      full.textContent = on ? "Exit full screen" : "Full screen";
      full.setAttribute("aria-pressed", on ? "true" : "false");
      if (on) lockKeys();
      else unlockKeys();
    };
    full.addEventListener("click", function () {
      if (document.fullscreenElement === stage) {
        document.exitFullscreen();
      } else {
        var req = stage.requestFullscreen();
        if (req && req.then) req.then(lockKeys).catch(function () {});
        else window.setTimeout(lockKeys, 0);
      }
      window.setTimeout(grab, 0);
    });
    document.addEventListener("fullscreenchange", syncFull);
  }

  fetch("/api/join", { headers: { Accept: "application/json" } })
    .then(function (response) { return response.json(); })
    .then(function (info) {
      if (info && info.join) joinAddress = info.join;
      joinReady = true;
      paintStatus(!!info.online, info.online ? "World is online" : "World is offline");
      if (queued || location.hash === "#play") startGame();
    })
    .catch(function () {
      joinReady = true;
      paintStatus(false, "Could not check the world");
      if (queued || location.hash === "#play") startGame();
    });

  document.addEventListener("click", function (event) {
    var node = event.target.closest("[data-copy]");
    if (!node || !navigator.clipboard) return;
    var text = node.getAttribute("data-copy");
    var label = node.getAttribute("data-label");
    if (!label) {
      label = node.textContent;
      node.setAttribute("data-label", label);
    }
    navigator.clipboard.writeText(text).then(function () {
      node.textContent = "Copied";
      window.setTimeout(function () { node.textContent = label; }, 1200);
    });
  });

  function showPane(button) {
    var mega = button.closest(".mega");
    if (!mega) return;
    var id = button.getAttribute("data-mega");
    var buttons = mega.querySelectorAll("[data-mega]");
    var panes = mega.querySelectorAll("[data-pane]");
    var i;
    for (i = 0; i < buttons.length; i++) {
      var on = buttons[i] === button;
      buttons[i].classList.toggle("is-on", on);
      buttons[i].setAttribute("aria-selected", on ? "true" : "false");
    }
    for (i = 0; i < panes.length; i++) {
      panes[i].classList.toggle("is-on", panes[i].getAttribute("data-pane") === id);
    }
  }
  var megas = document.querySelectorAll("[data-mega]");
  for (var m = 0; m < megas.length; m++) {
    megas[m].addEventListener("mouseenter", function (event) { showPane(event.currentTarget); });
    megas[m].addEventListener("focus", function (event) { showPane(event.currentTarget); });
    megas[m].addEventListener("click", function (event) {
      event.preventDefault();
      showPane(event.currentTarget);
    });
  }

  var track = document.querySelector("[data-news-track]");
  if (track) {
    var newsIndex = 0;
    var newsWindow = track.parentElement;
    var newsTimer = 0;
    function newsEsc(value) {
      return String(value == null ? "" : value).replace(/[&<>"']/g, function (ch) {
        return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch];
      });
    }
    function newsLayout() {
      var cards = track.children;
      if (!cards.length) return;
      var gap = 16;
      var width = cards[0].getBoundingClientRect().width + gap;
      var view = newsWindow.getBoundingClientRect().width;
      var visible = Math.max(1, Math.round(view / width));
      var max = Math.max(0, cards.length - visible);
      if (newsIndex > max) newsIndex = 0;
      if (newsIndex < 0) newsIndex = max;
      track.style.transform = "translateX(" + (-newsIndex * width) + "px)";
    }
    function newsSlide(dir) {
      var cards = track.children;
      if (!cards.length) return;
      var gap = 16;
      var width = cards[0].getBoundingClientRect().width + gap;
      var view = newsWindow.getBoundingClientRect().width;
      var visible = Math.max(1, Math.round(view / width));
      var max = Math.max(0, cards.length - visible);
      newsIndex += dir;
      if (newsIndex > max) newsIndex = 0;
      if (newsIndex < 0) newsIndex = max;
      if (!reduced) {
        newsWindow.classList.remove("is-mist");
        void newsWindow.offsetWidth;
        newsWindow.classList.add("is-mist");
      }
      newsLayout();
    }
    var newsSig = "";
    function paintNews(items) {
      track.innerHTML = items.map(function (item) {
        var img = '<img src="' + newsEsc(item.image) + '" alt="">';
        var body = '<span class="news-tag">' + newsEsc(item.tag || "News") + "</span><h3>"
          + newsEsc(item.title) + "</h3><p>" + newsEsc(item.blurb) + "</p>";
        var href = item.href || "";
        if (href.charAt(0) === "/" || href.indexOf("https://") === 0) {
          var extra = href.indexOf("https://") === 0 ? ' target="_blank" rel="noreferrer"' : "";
          return '<article class="news-card"><a href="' + newsEsc(href) + '"' + extra + '>' + img + "<div>" + body + "</div></a></article>";
        }
        return '<article class="news-card">' + img + "<div>" + body + "</div></article>";
      }).join("");
      newsLayout();
    }
    function pullNews() {
      if (document.hidden) return;
      fetch("/api/news", { headers: { Accept: "application/json" }, cache: "no-store" })
        .then(function (response) { return response.json(); })
        .then(function (data) {
          var items = data.items || [];
          var next = items.map(function (item) {
            return [item.id, item.title, item.blurb, item.image, item.href].join("\t");
          }).join("\n");
          if (next === newsSig) return;
          newsSig = next;
          if (!items.length) {
            track.innerHTML = '<p class="xpl-empty">The dispatches are quiet.</p>';
            return;
          }
          paintNews(items);
        })
        .catch(function () {
          if (!track.children.length) track.innerHTML = '<p class="xpl-empty">The dispatches are quiet.</p>';
        });
    }
    pullNews();
    window.setInterval(pullNews, 20000);
    document.addEventListener("visibilitychange", function () {
      if (!document.hidden) pullNews();
    });
    try {
      var newsCast = new BroadcastChannel("catharsis-news");
      newsCast.onmessage = function () { pullNews(); };
    } catch (e) {}
    var prev = document.querySelector("[data-news='prev']");
    var next = document.querySelector("[data-news='next']");
    if (prev) prev.addEventListener("click", function () { newsSlide(-1); });
    if (next) next.addEventListener("click", function () { newsSlide(1); });
    window.addEventListener("resize", newsLayout);
    if (!reduced) {
      newsTimer = window.setInterval(function () { newsSlide(1); }, 8000);
      newsWindow.addEventListener("mouseenter", function () { window.clearInterval(newsTimer); });
      newsWindow.addEventListener("mouseleave", function () {
        window.clearInterval(newsTimer);
        newsTimer = window.setInterval(function () { newsSlide(1); }, 8000);
      });
    }
  }
})();

(function () {
  var reduced = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  if (reduced) return;
  var brand = document.querySelector("[data-eyes]");
  if (!brand) return;
  var brandPupils = brand.querySelectorAll(".pupil");
  var mouse = { x: window.innerWidth * 0.62, y: 220, on: false };

  window.addEventListener("pointermove", function (event) {
    mouse.x = event.clientX;
    mouse.y = event.clientY;
    mouse.on = !document.hidden && !document.pointerLockElement;
  }, { passive: true });
  window.addEventListener("pointerleave", function () { mouse.on = false; });
  window.addEventListener("mouseout", function (event) {
    if (!event.relatedTarget) mouse.on = false;
  });
  window.addEventListener("blur", function () { mouse.on = false; });
  document.addEventListener("visibilitychange", function () {
    if (document.hidden) mouse.on = false;
  });

  function gaze(ox, oy, tx, ty, reach) {
    var dx = tx - ox;
    var dy = ty - oy;
    var len = Math.hypot(dx, dy) || 1;
    var amount = Math.min(reach, len);
    return { x: dx / len * amount, y: dy / len * amount };
  }

  function tracker(reach) {
    var x = 0;
    var y = 0;
    return function (pupils, ox, oy, tx, ty, blink) {
      var shift = gaze(ox, oy, tx, ty, reach);
      x += (shift.x - x) * 0.2;
      y += (shift.y - y) * 0.2;
      var move = "translate(-50%, -50%) translate(" + x.toFixed(2) + "px, " + y.toFixed(2) + "px) scaleY(" + blink.toFixed(3) + ")";
      for (var i = 0; i < pupils.length; i++) pupils[i].style.transform = move;
    };
  }

  var lookBrand = tracker(14);

  function blinkOf(now) {
    var beat = now % 4800;
    if (beat < 4460) return 1;
    var p = (beat - 4460) / 340;
    return 0.2 + 0.8 * Math.abs(Math.cos(p * Math.PI));
  }

  function frame(now) {
    var aimX = mouse.x;
    var aimY = mouse.y;
    if (!mouse.on) {
      var box = brand.getBoundingClientRect();
      aimX = box.left + box.width / 2 + Math.cos(now / 1400) * 160;
      aimY = box.top + box.height / 2 + 80 + Math.sin(now / 1400) * 40;
    }
    var home = brand.getBoundingClientRect();
    lookBrand(brandPupils, home.left + home.width / 2, home.top + home.height / 2, aimX, aimY, blinkOf(now));
    window.requestAnimationFrame(frame);
  }
  window.requestAnimationFrame(frame);
})();
