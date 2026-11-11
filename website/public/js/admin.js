(function () {
  var login = document.getElementById("ward-login");
  var desk = document.getElementById("ward-desk");
  var list = document.getElementById("ward-list");
  var note = document.querySelector("[data-ward-note]");
  if (!login || !desk) return;

  function say(text) {
    if (note) note.textContent = text || "";
  }

  function esc(value) {
    return String(value == null ? "" : value).replace(/[&<>"']/g, function (ch) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch];
    });
  }

  function tellHome() {
    try {
      var cast = new BroadcastChannel("catharsis-news");
      cast.postMessage("changed");
      cast.close();
    } catch (e) {}
  }

  function paint(items) {
    list.innerHTML = (items || []).map(function (item) {
      return '<article class="ward-row"><img src="' + esc(item.image) + '" alt=""><div><b>'
        + esc(item.tag) + '</b><h2>' + esc(item.title) + '</h2><p>' + esc(item.blurb)
        + '</p></div><button type="button" data-drop="' + esc(item.id) + '">Remove</button></article>';
    }).join("");
  }

  function load() {
    return fetch("/api/news", { headers: { Accept: "application/json" } })
      .then(function (response) { return response.json(); })
      .then(function (data) { paint(data.items || []); });
  }

  function showDesk(on) {
    login.hidden = on;
    desk.hidden = !on;
    if (on) load();
  }

  fetch("/api/admin/me", { headers: { Accept: "application/json" } })
    .then(function (response) { return response.json(); })
    .then(function (data) { showDesk(!!data.ok); })
    .catch(function () { say("The ward is not answering."); });

  login.addEventListener("submit", function (event) {
    event.preventDefault();
    var data = new FormData(login);
    say("");
    fetch("/api/admin/login", {
      method: "POST",
      headers: { "Content-Type": "application/json", Accept: "application/json" },
      body: JSON.stringify({ name: data.get("name"), password: data.get("password") }),
    }).then(function (response) {
      if (!response.ok) throw new Error("no");
      login.reset();
      showDesk(true);
    }).catch(function () { say("Wrong name or password."); });
  });

  document.getElementById("ward-add").addEventListener("submit", function (event) {
    event.preventDefault();
    var form = event.currentTarget;
    var data = new FormData(form);
    var file = data.get("file");
    var upload = Promise.resolve(data.get("image"));
    if (file && file.size) {
      upload = fetch("/api/admin/image", {
        method: "POST",
        headers: { "Content-Type": file.type || "application/octet-stream", Accept: "application/json" },
        body: file,
      }).then(function (response) {
        return response.json().then(function (body) {
          if (!response.ok) throw new Error(body.error || "Could not store that picture.");
          return body.image;
        });
      });
    }
    upload.then(function (image) {
      return fetch("/api/admin/news", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({
          tag: data.get("tag"),
          title: data.get("title"),
          blurb: data.get("blurb"),
          image: image,
          href: data.get("href"),
        }),
      });
    }).then(function (response) { return response.json().then(function (body) { return { ok: response.ok, body: body }; }); })
      .then(function (result) {
        if (!result.ok) throw new Error(result.body.error || "no");
        event.currentTarget.reset();
        paint(result.body.items || []);
        tellHome();
      })
      .catch(function (error) { say(error.message || "Could not add that."); });
  });

  list.addEventListener("click", function (event) {
    var button = event.target.closest("[data-drop]");
    if (!button) return;
    fetch("/api/admin/news?id=" + encodeURIComponent(button.getAttribute("data-drop")), {
      method: "DELETE",
      headers: { Accept: "application/json" },
    }).then(function (response) { return response.json(); })
      .then(function (body) { paint(body.items || []); tellHome(); });
  });

  document.getElementById("ward-out").addEventListener("click", function () {
    fetch("/api/admin/logout", { method: "POST", headers: { Accept: "application/json" } })
      .then(function () { showDesk(false); });
  });
})();
