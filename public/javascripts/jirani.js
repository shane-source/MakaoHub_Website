(function () {
    var box = document.getElementById("jirani");
    if (!box) return;

    var log = document.getElementById("jirani-log");
    var form = document.getElementById("jirani-form");
    var field = form.elements.content;
    var button = form.querySelector("button[type=submit]");
    var errorBox = document.getElementById("jirani-error");
    var token = form.elements.csrfToken ? form.elements.csrfToken.value : "";
    var history = [];        // { role, content }, newest last
    var waiting = false;

    function add(text, mine, className) {
        var item = document.createElement("div");
        item.className = "chat-msg" + (mine ? " is-own" : "") + (className ? " " + className : "");
        var p = document.createElement("p");
        p.className = "chat-text";
        p.textContent = text;          // always text, never HTML
        item.appendChild(p);
        log.appendChild(item);
        item.scrollIntoView({ block: "nearest" });
        return item;
    }

    function showError(text) {
        errorBox.textContent = text || "";
        errorBox.hidden = !text;
    }

    function send(text) {
        text = text.trim();
        if (!text || waiting) return;
        if (text.length > 1000) { showError("Please keep your message under 1000 characters."); return; }

        waiting = true;
        button.disabled = true;
        showError("");
        add(text, true);
        history.push({ role: "user", content: text });
        field.value = "";
        var thinking = add("Jirani is typing…", false, "is-pending");

        fetch("/dashboard/jirani", {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/json", "Csrf-Token": token },
            body: JSON.stringify({ messages: history.slice(-12) })
        }).then(function (r) {
            return r.json().catch(function () { return {}; }).then(function (j) { return { status: r.status, json: j }; });
        }).then(function (res) {
            thinking.remove();
            if (res.status === 200 && res.json.reply) {
                add(res.json.reply, false);
                history.push({ role: "assistant", content: res.json.reply });
            } else {
                history.pop();
                showError(res.json.error || "Jirani is busy right now. Please try again.");
                field.value = text;
            }
        }).catch(function () {
            thinking.remove();
            history.pop();
            showError("Couldn't reach Jirani. Check your connection and try again.");
            field.value = text;
        }).then(function () {
            waiting = false;
            button.disabled = false;
            field.focus();
        });
    }

    form.addEventListener("submit", function (e) {
        e.preventDefault();
        send(field.value);
    });

    field.addEventListener("keydown", function (e) {
        if (e.key === "Enter" && !e.shiftKey && !e.isComposing) {
            e.preventDefault();
            if (form.requestSubmit) form.requestSubmit(); else button.click();
        }
    });

    box.querySelectorAll("[data-ask]").forEach(function (chip) {
        chip.addEventListener("click", function () { send(chip.getAttribute("data-ask")); });
    });
})();
