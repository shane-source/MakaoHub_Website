(function () {
    var links = document.querySelectorAll(".dash-tabs [data-badge]");
    if (!links.length) return;

    var path = location.pathname;
    var thread = path.match(/^\/dashboard\/messages\/(?:home|roommate)\/([^\/]+)/);
    var onApplications = path.indexOf("/dashboard/applications") === 0;

    function show(kind, count) {
        links.forEach(function (a) {
            if (a.getAttribute("data-badge") !== kind) return;
            var old = a.querySelector(".tab-badge");
            if (old) old.remove();
            if (count > 0) {
                var b = document.createElement("span");
                b.className = "tab-badge";
                b.textContent = count > 99 ? "99+" : String(count);
                b.setAttribute("aria-label", count + (kind === "messages" ? " unread" : " applications"));
                a.appendChild(b);
            }
        });
    }

    // What this browser has already looked at, kept per signed-in person.
    function read(key) {
        try { return JSON.parse(localStorage.getItem(key) || "null"); } catch (e) { return null; }
    }
    function write(key, value) {
        try { localStorage.setItem(key, JSON.stringify(value)); } catch (e) { /* private mode: numbers just stay */ }
    }

    function apply(d) {
        if (!d) return;
        var base = "makao.seen." + (d.who || "");
        var threads = Array.isArray(d.threads) ? d.threads : null;
        var apps = Array.isArray(d.apps) ? d.apps : null;

        var messages = d.messages || 0;
        if (threads) {
            var seenThreads = read(base + ".threads") || {};
            if (thread) {
                threads.forEach(function (t) { if (t.id === thread[1]) seenThreads[t.id] = t.at; });
            }
            var keep = {};
            messages = 0;
            threads.forEach(function (t) {
                if (seenThreads[t.id] !== undefined) keep[t.id] = seenThreads[t.id];
                if (seenThreads[t.id] !== t.at) messages += t.unread || 0;
            });
            write(base + ".threads", keep);
        }

        var applications = d.applications || 0;
        if (apps) {
            var seenApps = read(base + ".apps") || [];
            if (onApplications) seenApps = seenApps.concat(apps);
            var now = {};
            apps.forEach(function (k) { now[k] = true; });
            seenApps = seenApps.filter(function (k, i, all) { return now[k] && all.indexOf(k) === i; });
            applications = apps.filter(function (k) { return seenApps.indexOf(k) < 0; }).length;
            write(base + ".apps", seenApps);
        }

        show("messages", messages);
        show("applications", applications);
    }

    function load() {
        if (document.hidden) return;
        fetch("/dashboard/badges", { credentials: "same-origin", cache: "no-store" })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(apply)
            .catch(function () { /* keep the last numbers */ });
    }

    load();
    setInterval(load, 30000);
})();
