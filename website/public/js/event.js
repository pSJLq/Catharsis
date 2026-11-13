(function () {
  var $ = function (sel) { return document.querySelector(sel); };
  var data = null;
  var names = { skills: "Skills", bag: "Bag", words: "Spell words", island: "Island", sword: "Sword", market: "Market", keys: "Keys", withdraw: "Withdraw", deploy: "Deploy", other: "Other" };

  function esc(v) {
    return String(v == null ? "" : v).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }
  function num(v) { return Number(v || 0).toLocaleString("en-US"); }
  function when(ms) {
    return new Date(ms).toLocaleString("en-US", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit", timeZoneName: "short" });
  }
  function span(ms) {
    if (ms < 0) ms = 0;
    var s = Math.floor(ms / 1000), d = Math.floor(s / 86400), h = Math.floor(s % 86400 / 3600), m = Math.floor(s % 3600 / 60), sec = s % 60;
    return (d ? d + "d " : "") + String(h).padStart(2, "0") + ":" + String(m).padStart(2, "0") + ":" + String(sec).padStart(2, "0");
  }

  function clock() {
    if (!data) return;
    var skew = data.now - data.fetched;
    var now = Date.now() + skew;
    var phase = data.phase;
    if (phase === "scheduled" && now >= data.start) phase = "running";
    if (phase === "running" && now >= data.end) phase = "ended";
    var label = { idle: "The event has not started yet.", scheduled: "Starts in", running: "Ends in", ended: "The event has ended." }[phase];
    $("[data-ev-phase]").textContent = label;
    $("[data-ev-count]").textContent = phase === "scheduled" ? span(data.start - now) : phase === "running" ? span(data.end - now) : "";
    $("[data-ev-window]").textContent = data.start ? when(data.start) + " – " + when(data.end) : "";
  }

  function paint() {
    var rows = data.rows || [];
    $("[data-ev-rows]").innerHTML = rows.length ? rows.map(function (r) {
      var badge = r.prize ? ' <span class="ev-badge p' + r.rank + '">' + r.prize + " SOMI</span>" : "";
      if (r.claim) {
        badge += r.claim.state === "paid"
          ? ' <a class="ev-claim paid" href="https://explorer.somnia.network/tx/' + esc(r.claim.tx || "") + '" target="_blank" rel="noopener">claimed</a>'
          : ' <span class="ev-claim">' + (r.claim.state === "pending" ? "payout on its way" : "not claimed yet") + "</span>";
      }
      return "<tr" + (r.rank <= 3 ? ' class="top' + r.rank + '"' : "") + "><td>" + r.rank + "</td><td>" + esc(r.name) + badge + "</td><td>+" + num(r.xpGained)
        + "</td><td>+" + num(r.levelGained) + "</td><td>" + num(r.level) + "</td><td>" + num(r.tx) + "</td></tr>";
    }).join("") : '<tr><td colspan="6">' + (data.phase === "idle" || data.phase === "scheduled" ? "The board fills in once the event starts." : "No results yet.") + "</td></tr>";
    var st = data.stats || {};
    $("[data-ev-tx]").textContent = num(st.tx);
    $("[data-ev-players]").textContent = num(st.players);
    var types = Object.keys(st.txBy || {}).sort(function (a, b) { return st.txBy[b] - st.txBy[a]; });
    $("[data-ev-types]").innerHTML = types.map(function (k) { return "<span>" + esc(names[k] || k) + " <b>" + num(st.txBy[k]) + "</b></span>"; }).join("");
    var note = "";
    if (data.phase === "ended") note = data.finalized ? "Final results. Winners claim their prize in game with /event claim." : data.reviewed ? "Final results: the review is done. Prize claims open soon." : "The board is frozen. Results are provisional until the review is done.";
    else if (data.phase === "running") note = "Provisional standings, refreshed every 30 seconds. Prizes are final after the review at the end.";
    $("[data-ev-note]").textContent = note;
    $("[data-ev-updated]").textContent = data.updated ? "Updated " + when(data.updated) : "";
    clock();
  }

  function load() {
    fetch("/api/event", { headers: { Accept: "application/json" }, cache: "no-store" })
      .then(function (r) { return r.json(); })
      .then(function (d) { d.fetched = Date.now(); data = d; paint(); })
      .catch(function () { $("[data-ev-phase]").textContent = "The board is not answering. Retrying…"; });
  }

  load();
  setInterval(load, 30000);
  setInterval(clock, 1000);
})();
