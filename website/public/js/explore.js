(function () {
  var view = document.getElementById("x-view");
  if (!view) return;
  var stats = document.getElementById("x-stats");
  var contracts = document.getElementById("x-contracts");
  var search = document.getElementById("x-search");
  var input = document.getElementById("x-q");

  function esc(value) {
    return String(value == null ? "" : value).replace(/[&<>"']/g, function (ch) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch];
    });
  }

  function short(address) {
    if (!address || address.length < 12) return address || "";
    return address.slice(0, 6) + "…" + address.slice(-4);
  }

  function route() {
    var raw = location.hash.replace(/^#\/?/, "");
    var parts = raw.split("/");
    return { kind: parts[0] || "", id: decodeURIComponent(parts.slice(1).join("/")) };
  }

  function cardHtml(row) {
    var who = (row.players && row.players[0]) || row.from || "";
    var player = who
      ? '<a href="#/address/' + esc(who) + '">' + esc(short(who)) + "</a>"
      : "—";
    var block = row.block == null ? "pending" : "block " + esc(row.block);
    return '<article class="deed"><div class="deed-fog" aria-hidden="true"></div><header><a class="hash" href="#/tx/'
      + esc(row.hash) + '">' + esc(short(row.hash)) + "</a><span>" + esc(row.phase || "")
      + "</span></header><h3>" + esc(row.summary) + "</h3><p>" + player + " · " + block
      + '</p><a class="deed-out" href="' + esc(row.shannon) + '" target="_blank" rel="noreferrer">Shannon</a></article>';
  }

  function table(actions) {
    if (!actions || !actions.length) {
      return "<p class=\"xpl-empty\">No game actions in the recent blocks. Play, and they show up here.</p>";
    }
    return '<div class="deed-list">' + actions.map(cardHtml).join("") + "</div>";
  }

  function paintHome(data, filter) {
    var actions = data.actions || [];
    if (filter) {
      var q = filter.toLowerCase();
      actions = actions.filter(function (row) {
        var blob = ((row.summary || "") + " " + (row.action || "") + " " + (row.hash || "")).toLowerCase();
        return blob.indexOf(q) >= 0;
      });
    }
    stats.innerHTML = "<span class=\"chip\">Shannon " + esc(data.chain) + "</span>"
      + "<span class=\"chip\">Block " + esc(data.block == null ? "…" : data.block) + "</span>"
      + "<span class=\"chip\">" + actions.length + " actions</span>";
    contracts.innerHTML = (data.contracts || []).map(function (item) {
      return "<a class=\"chip\" href=\"#/address/" + esc(item.address) + "\"><b>" + esc(item.name) + "</b><span>"
        + esc(short(item.address)) + "</span></a>";
    }).join("");
    var players = (data.players || []).map(function (player) {
      return "<a class=\"shade\" href=\"#/address/" + esc(player.address) + "\">" + esc(short(player.address))
        + "<span>" + esc(player.count) + "</span></a>";
    }).join("");
    view.innerHTML = "<div class=\"xpl-grid\"><div>" + table(actions) + "</div><aside><h2>Players</h2>"
      + (players || "<p class=\"xpl-empty\">None in this window.</p>") + "</aside></div>";
  }

  function paintTx(row) {
    if (row.error) {
      view.innerHTML = "<p>" + esc(row.error) + "</p>";
      return;
    }
    stats.innerHTML = "";
    contracts.innerHTML = "";
    var lines = (row.detail || []).map(function (line) { return "<li>" + esc(line) + "</li>"; }).join("");
    view.innerHTML = "<p><a href=\"#/\">All actions</a></p><article class=\"xpl-detail\"><h2>" + esc(row.summary) + "</h2>"
      + "<p class=\"hash\">" + esc(row.hash) + "</p>"
      + "<p>State " + esc(row.phase || "")
      + (row.block == null ? "" : " · block " + esc(row.block))
      + (row.gas == null ? "" : " · gas " + esc(row.gas)) + "</p>"
      + (row.from ? "<p>From <a href=\"#/address/" + esc(row.from) + "\">" + esc(row.from) + "</a></p>" : "")
      + (row.to ? "<p>To <a href=\"#/address/" + esc(row.to) + "\">" + esc(row.to) + "</a></p>" : "")
      + (row.action ? "<p>Call " + esc(row.action) + "</p>" : "")
      + (lines ? "<ul>" + lines + "</ul>" : "")
      + "<p><a href=\"" + esc(row.shannon) + "\" target=\"_blank\" rel=\"noreferrer\">Open on Shannon</a></p></article>";
  }

  function paintAddress(data) {
    if (data.error) {
      view.innerHTML = "<p>" + esc(data.error) + "</p>";
      return;
    }
    stats.innerHTML = "<span class=\"chip\">" + esc(short(data.address)) + "</span><span class=\"chip\">"
      + (data.actions || []).length + " recent actions</span>";
    contracts.innerHTML = "";
    view.innerHTML = "<p><a href=\"#/\">All actions</a></p><h2 class=\"hash\">" + esc(data.address) + "</h2>"
      + "<p><a href=\"" + esc(data.shannon) + "\" target=\"_blank\" rel=\"noreferrer\">Open on Shannon</a></p>"
      + table(data.actions || []);
  }

  function fail(message) {
    view.innerHTML = "<p class=\"xpl-empty\">" + esc(message) + "</p>";
  }

  function show() {
    var where = route();
    if (where.kind === "tx" && /^0x[0-9a-fA-F]{64}$/.test(where.id)) {
      fetch("/api/tx?hash=" + encodeURIComponent(where.id))
        .then(function (response) { return response.json(); })
        .then(paintTx)
        .catch(function () { fail("Could not read that transaction."); });
      return;
    }
    if (where.kind === "address" && /^0x[0-9a-fA-F]{40}$/.test(where.id)) {
      fetch("/api/address?a=" + encodeURIComponent(where.id))
        .then(function (response) { return response.json(); })
        .then(paintAddress)
        .catch(function () { fail("Could not read that address."); });
      return;
    }
    var filter = where.kind === "q" ? where.id : "";
    fetch("/api/feed")
      .then(function (response) { return response.json(); })
      .then(function (data) { paintHome(data, filter); })
      .catch(function () { fail("The explorer cannot reach Shannon right now."); });
  }

  if (search) {
    search.addEventListener("submit", function (event) {
      event.preventDefault();
      var q = (input.value || "").trim();
      if (/^0x[0-9a-fA-F]{64}$/.test(q)) location.hash = "#/tx/" + q;
      else if (/^0x[0-9a-fA-F]{40}$/.test(q)) location.hash = "#/address/" + q;
      else location.hash = q ? "#/q/" + encodeURIComponent(q) : "#/";
      show();
    });
  }

  window.addEventListener("hashchange", show);
  show();
  setInterval(function () {
    var where = route();
    if (!where.kind || where.kind === "q") show();
  }, 4000);
})();
