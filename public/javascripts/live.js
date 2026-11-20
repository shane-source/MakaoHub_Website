(function () {
    // Keeps a signed-in dashboard page current without a manual refresh. It reloads the page quietly
    // every few seconds and swaps in the new content only when something actually changed.
    var path = location.pathname;
    if (path.indexOf("/dashboard") !== 0) return;
    // Chat threads have their own live connection.
    if (/^\/dashboard\/messages\/(home|roommate)\//.test(path)) return;

    var wrap = document.querySelector(".dash-wrap");
    if (!wrap) return;
    var INTERVAL = 8000;
    var busy = false;

    function parts(root) {
        var out = [];
        Array.prototype.forEach.call(root.children, function (el) {
            if (el.classList.contains("dash-tabs") || el.tagName === "SCRIPT") return;
            out.push(el);
        });
        return out;
    }
    // Page notices, CSRF tokens and badge counts change on every load and are not real changes.
    function signature(nodes) {
        return nodes.map(function (el) {
            var c = el.cloneNode(true);
            Array.prototype.forEach.call(c.querySelectorAll(".notice, [name=csrfToken], .tab-badge"), function (n) { n.remove(); });
            return c.outerHTML;
        }).join("");
    }
    // Never replace a page while the person is typing or has chosen a file.
    function editing() {
        var a = document.activeElement;
        if (a && wrap.contains(a) && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName) && a.type !== "hidden" && a.type !== "submit") return true;
        var fields = wrap.querySelectorAll("input, textarea, select");
        for (var i = 0; i < fields.length; i++) {
            var f = fields[i];
            if (f.type === "hidden" || f.type === "submit" || f.type === "button") continue;
            if (f.type === "file") { if (f.files && f.files.length) return true; continue; }
            if (f.type === "checkbox" || f.type === "radio") { if (f.checked !== f.defaultChecked) return true; continue; }
            if (f.tagName === "SELECT") {
                for (var j = 0; j < f.options.length; j++) if (f.options[j].selected !== f.options[j].defaultSelected) return true;
                continue;
            }
            if (f.value !== f.defaultValue) return true;
        }
        var sel = window.getSelection && window.getSelection().toString();
        return !!(sel && sel.length > 0);
    }

    function tick() {
        if (busy || document.hidden || editing()) return;
        busy = true;
        fetch(location.href, { credentials: "same-origin", headers: { "X-Live-Refresh": "1" }, cache: "no-store" })
            .then(function (r) {
                if (r.redirected && r.url.indexOf("/dashboard") < 0) { location.href = r.url; return null; }
                return r.ok ? r.text() : null;
            })
            .then(function (html) {
                if (!html) return;
                var doc = new DOMParser().parseFromString(html, "text/html");
                var next = doc.querySelector(".dash-wrap");
                if (!next || editing()) return;
                var oldNodes = parts(wrap), newNodes = parts(next);
                if (signature(oldNodes) === signature(newNodes)) return;
                var y = window.scrollY;
                oldNodes.forEach(function (el) { el.remove(); });
                newNodes.forEach(function (el) { wrap.appendChild(document.importNode(el, true)); });
                window.scrollTo(0, y);
            })
            .catch(function () { /* offline or restarting: try again next time */ })
            .then(function () { busy = false; });
    }

    setInterval(tick, INTERVAL);
    document.addEventListener("visibilitychange", function () { if (!document.hidden) tick(); });
})();
