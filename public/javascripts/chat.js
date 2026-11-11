(function () {
    var log = document.getElementById("chat-log");
    if (!log) return;

    // The form is missing in a closed conversation: reading still works, sending does not.
    var form = document.getElementById("chat-form");
    var errorBox = document.getElementById("chat-error");
    var field = form ? form.elements.content : null;
    var button = form ? form.querySelector("button[type=submit]") : null;
    var token = form && form.elements.csrfToken ? form.elements.csrfToken.value : "";
    var thread = form ? form.getAttribute("data-thread") || "" : "";
    var channel = form ? form.getAttribute("data-channel") || "" : "";

    var olderBox = document.getElementById("chat-older");
    var olderBtn = olderBox ? olderBox.querySelector(".chat-older-btn") : null;
    var olderNote = olderBox ? olderBox.querySelector(".chat-older-note") : null;
    var loadingOlder = false;

    var pendingId = null;      // reused if a send fails, so a retry can never create a duplicate
    var pendingText = "";
    var sending = false;
    var acked = {};

    function uuid() {
        if (window.crypto && crypto.randomUUID) return crypto.randomUUID();
        return "10000000-1000-4000-8000-100000000000".replace(/[018]/g, function (c) {
            return (c ^ (crypto.getRandomValues(new Uint8Array(1))[0] & (15 >> (c / 4)))).toString(16);
        });
    }

    function clock(iso) {
        try {
            return new Date(iso).toLocaleTimeString("en-KE", { hour: "numeric", minute: "2-digit", timeZone: "Africa/Nairobi" });
        } catch (e) { return ""; }
    }

    function showError(text) {
        if (!errorBox) return;
        errorBox.textContent = text;
        errorBox.hidden = !text;
    }

    function scrollDown() {
        window.scrollTo(0, document.body.scrollHeight);
    }

    function post(url, payload) {
        return fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json", "Csrf-Token": token },
            body: JSON.stringify(payload)
        }).then(function (response) {
            return response.json().catch(function () { return {}; }).then(function (json) {
                return { status: response.status, json: json };
            });
        });
    }

    function has(id) {
        return !!log.querySelector('[data-id="' + CSS.escape(id) + '"]');
    }

    // One shape for every message: { id, text, time, mine, delivered, masked }.
    // Text is always inserted as text, never as HTML.
    function element(m) {
        var box = document.createElement("div");
        box.className = "chat-msg" + (m.mine ? " is-own" : "");
        box.setAttribute("data-id", m.id);
        if (!m.mine) {
            box.setAttribute("data-incoming", "true");
            box.setAttribute("data-delivered", m.delivered ? "true" : "false");
        }

        var text = document.createElement("p");
        text.className = "chat-text";
        text.textContent = m.text || "";

        var meta = document.createElement("p");
        meta.className = "chat-meta";
        var when = document.createElement("span");
        when.textContent = [m.time, m.masked ? "Flagged contact attempt · masked" : ""].filter(Boolean).join(" · ");
        meta.appendChild(when);
        if (m.mine) {
            var ticks = document.createElement("span");
            ticks.className = "chat-ticks";
            ticks.textContent = m.delivered ? "✓✓" : "✓";
            meta.appendChild(ticks);
        }

        box.appendChild(text);
        box.appendChild(meta);
        return box;
    }

    function dropEmptyNote() {
        var empty = log.querySelector(".chat-empty");
        if (empty) empty.remove();
    }

    // The server's saved copy of a message we just sent.
    function addSent(raw) {
        if (!raw || !raw.id || has(raw.id)) return;
        dropEmptyNote();
        log.appendChild(element({
            id: raw.id, text: raw.content, time: clock(raw.createdAt),
            mine: true, delivered: !!raw.deliveredAt, masked: !!raw.contactMasked
        }));
        scrollDown();
    }

    // ---------- Sending ----------

    if (form) {
        form.addEventListener("submit", function (e) {
            e.preventDefault();
            var text = field.value;
            if (sending || !text.trim() || text.length > 2000) return;

            sending = true;
            button.disabled = true;
            showError("");

            if (!pendingId || text !== pendingText) {
                pendingId = uuid();
                pendingText = text;
            }

            post(form.getAttribute("action"), {
                channel: channel, threadId: thread, clientMessageId: pendingId, content: text
            }).then(function (res) {
                if (res.status === 200 && res.json.ok && res.json.message) {
                    addSent(res.json.message);
                    field.value = "";
                    pendingId = null;
                    pendingText = "";
                } else if (res.status === 401) {
                    showError("Your session expired. Please sign in again.");
                } else {
                    showError(res.json.message || "Message not sent. Please try again.");
                }
            }).catch(function () {
                showError("Message not sent. Check your connection and try again.");
            }).then(function () {
                sending = false;
                button.disabled = false;
                field.focus();
            });
        });

        // Enter sends, Shift+Enter makes a new line.
        field.addEventListener("keydown", function (e) {
            if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
                e.preventDefault();
                if (form.requestSubmit) form.requestSubmit(); else button.click();
            }
        });
    }

    // ---------- Delivery ticks for messages we receive ----------

    function acknowledge() {
        if (!form) return;
        var ids = [];
        log.querySelectorAll('[data-incoming="true"][data-delivered="false"]').forEach(function (el) {
            var id = el.getAttribute("data-id");
            if (id && !acked[id]) ids.push(id);
        });
        if (!ids.length) return;
        ids = ids.slice(0, 100);
        ids.forEach(function (id) { acked[id] = true; });

        post("/dashboard/messages/delivered", { channel: channel, threadId: thread, messageIds: ids })
            .then(function (res) {
                if (res.status !== 200) ids.forEach(function (id) { delete acked[id]; });
            })
            .catch(function () { ids.forEach(function (id) { delete acked[id]; }); });
    }

    // ---------- New messages from the other person ----------
    // Only adds what is new and updates ticks, so older messages already loaded stay in place.

    function refresh() {
        if (document.hidden || sending || loadingOlder) return;
        fetch(window.location.href, { credentials: "same-origin" })
            .then(function (response) { return response.ok ? response.text() : null; })
            .then(function (html) {
                if (!html) return;
                var page = new DOMParser().parseFromString(html, "text/html");
                var fresh = page.getElementById("chat-log");
                if (!fresh) return;

                var atBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 80;
                var added = false;

                fresh.querySelectorAll(".chat-msg").forEach(function (node) {
                    var id = node.getAttribute("data-id");
                    if (!id) return;
                    var existing = log.querySelector('[data-id="' + CSS.escape(id) + '"]');
                    var copy = document.importNode(node, true);
                    if (!existing) {
                        dropEmptyNote();
                        log.appendChild(copy);
                        added = true;
                    } else if (existing.outerHTML !== copy.outerHTML) {
                        existing.replaceWith(copy);     // for example, a single tick became a double tick
                    }
                });

                if (added && atBottom) scrollDown();
                acknowledge();
            })
            .catch(function () { /* try again at the next tick */ });
    }

    // ---------- Older messages ----------

    function say(text) {
        if (olderNote) olderNote.textContent = text;
    }

    if (olderBox && olderBtn) {
        olderBtn.addEventListener("click", function () {
            var cursor = olderBox.getAttribute("data-cursor") || "";
            var url = olderBox.getAttribute("data-url") || "";
            if (!cursor || !url || loadingOlder) return;

            loadingOlder = true;
            olderBtn.disabled = true;
            say("");

            fetch(url + "?cursor=" + encodeURIComponent(cursor), { credentials: "same-origin" })
                .then(function (response) { return response.ok ? response.json() : null; })
                .then(function (data) {
                    if (!data || !Array.isArray(data.messages)) {
                        say("Couldn't load older messages. Please try again.");
                        return;
                    }

                    var fresh = data.messages.filter(function (m) { return m && m.id && !has(m.id); });
                    if (!fresh.length) {
                        // Nothing new came back, so the paging did not work. Stop here rather than loop.
                        olderBox.hidden = true;
                        say("");
                        return;
                    }

                    var before = document.documentElement.scrollHeight;
                    var anchor = log.querySelector(".chat-msg");
                    dropEmptyNote();
                    fresh.forEach(function (m) {
                        var node = element(m);
                        if (anchor) log.insertBefore(node, anchor); else log.appendChild(node);
                    });
                    // Keep the reader where they were instead of jumping to the top.
                    window.scrollBy(0, document.documentElement.scrollHeight - before);

                    if (data.hasNext && data.nextCursor) {
                        olderBox.setAttribute("data-cursor", data.nextCursor);
                    } else {
                        olderBox.hidden = true;
                    }
                })
                .catch(function () { say("Couldn't load older messages. Check your connection and try again."); })
                .then(function () {
                    loadingOlder = false;
                    olderBtn.disabled = false;
                    acknowledge();
                });
        });
    }

    setInterval(refresh, 5000);
    scrollDown();
    acknowledge();
})();