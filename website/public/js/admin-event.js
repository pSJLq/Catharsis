(function () {
  var box = document.getElementById("ev-admin");
  var desk = document.getElementById("ward-desk");
  if (!box || !desk) return;
  var rowsEl = box.querySelector("[data-ev-admin-rows]");
  var stateEl = box.querySelector("[data-ev-state]");
  var form = document.getElementById("ev-start");
  var reviewed = document.getElementById("ev-reviewed");
  var data = null;

  function esc(v) {
    return String(v == null ? "" : v).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }
  function num(v) { return Number(v || 0).toLocaleString("en-US"); }
  function when(ms) { return ms ? new Date(ms).toLocaleString() : "-"; }
  function local(ms) {
    var d = new Date(ms - new Date(ms).getTimezoneOffset() * 60000);
    return d.toISOString().slice(0, 16);
  }

  function call(path, body) {
    return fetch(path, {
      method: body ? "POST" : "GET",
      headers: body ? { "Content-Type": "application/json", Accept: "application/json" } : { Accept: "application/json" },
      body: body ? JSON.stringify(body) : undefined,
    }).then(function (r) {
      return r.json().then(function (b) {
        if (!r.ok) throw new Error(b.error || "Failed.");
        return b;
      });
    });
  }

  function paint(d) {
    data = d;
    var age = d.boardAt ? Math.round((Date.now() - d.boardAt) / 1000) : -1;
    stateEl.textContent = "Phase: " + d.phase + (d.start ? " · " + when(d.start) + " → " + when(d.end) : "")
      + (d.baselineAt ? " · start snapshot " + when(d.baselineAt) : "")
      + " · server data " + (age < 0 ? "missing" : age + " s old")
      + " · tx " + num(d.stats && d.stats.tx) + " · players " + num(d.stats && d.stats.players);
    reviewed.checked = !!d.reviewed;
    var all = d.all || [];
    rowsEl.innerHTML = all.length ? all.map(function (r) {
      var flags = (r.flags || []).map(function (f) {
        return '<span class="ev-flag">' + esc(f.text) + " (first " + esc(when(f.firstAt)) + (f.max ? ", max " + esc(num(f.max)) : "") + ")</span>";
      }).join("");
      var review = (r.dev ? "staff account, not ranked" : r.excluded ? "excluded wallet, not ranked" : "") + (r.dq ? '<span class="ev-dq">disqualified ' + esc(when(r.dq.at)) + (r.dq.note ? ": " + esc(r.dq.note) : "") + "</span>" : "")
        + (flags ? "<b>under review</b>" + flags : (r.dev || r.excluded || r.dq ? "" : "ok"));
      var btn = r.dev ? "" : '<button type="button" data-dq="' + esc(r.uuid) + '" data-on="' + (r.dq ? "0" : "1") + '">' + (r.dq ? "Restore" : "Disqualify") + "</button>";
      return "<tr><td>" + (r.rank ? r.rank : "-") + "</td><td>" + esc(r.name) + "</td><td>+" + num(r.xpGained) + "</td><td>+" + num(r.levelGained)
        + "</td><td>" + num(r.tx) + "</td><td>" + review + "</td><td>" + btn + "</td></tr>";
    }).join("") : '<tr><td colspan="7">No players yet.</td></tr>';
  }

  function say(e) { stateEl.textContent = e.message || String(e); }
  function load() {
    if (desk.hidden) return;
    call("/api/admin/event").then(paint).catch(function () {});
  }

  form.addEventListener("submit", function (ev) {
    ev.preventDefault();
    var f = new FormData(form);
    var start = f.get("start") ? new Date(f.get("start")).getTime() : Date.now();
    var end = f.get("end") ? new Date(f.get("end")).getTime() : start + 72 * 3600 * 1000;
    var restart = data && data.phase !== "idle";
    if (!confirm("Start the event " + new Date(start).toLocaleString() + " → " + new Date(end).toLocaleString() + "?" + (restart ? "\nThis wipes the current event's snapshot, flags and results." : ""))) return;
    call("/api/admin/event/start", { start: start, end: end, restart: restart }).then(paint).catch(say);
  });
  document.getElementById("ev-stop").addEventListener("click", function () {
    if (!confirm("Stop the event now? The board freezes at this moment.")) return;
    call("/api/admin/event/stop", {}).then(paint).catch(say);
  });
  reviewed.addEventListener("change", function () {
    call("/api/admin/event/review", { on: reviewed.checked }).then(paint).catch(say);
  });
  rowsEl.addEventListener("click", function (ev) {
    var b = ev.target.closest("[data-dq]");
    if (!b) return;
    var on = b.getAttribute("data-on") === "1";
    var note = on ? prompt("Reason (shown only here):", "") : "";
    if (on && note === null) return;
    call("/api/admin/event/dq", { uuid: b.getAttribute("data-dq"), on: on, note: note || "" }).then(paint).catch(say);
  });

  var startIn = form.querySelector('[name="start"]');
  var endIn = form.querySelector('[name="end"]');
  startIn.addEventListener("change", function () {
    if (startIn.value) endIn.value = local(new Date(startIn.value).getTime() + 72 * 3600 * 1000);
  });
  new MutationObserver(load).observe(desk, { attributes: true, attributeFilter: ["hidden"] });
  load();
  setInterval(load, 30000);
})();
