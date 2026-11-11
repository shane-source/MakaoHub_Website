(function () {
    var KEY = "makaoTheme";
    var root = document.documentElement;
    var query = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)") : null;

    function stored() {
        try {
            var value = localStorage.getItem(KEY);
            return value === "light" || value === "dark" ? value : "system";
        } catch (e) {
            return "system";
        }
    }

    function apply(mode) {
        var dark = mode === "dark" || (mode === "system" && query && query.matches);
        root.setAttribute("data-theme", dark ? "dark" : "light");
    }

    apply(stored());

    // Follow the device while the choice is "system".
    if (query) {
        var changed = function () { if (stored() === "system") apply("system"); };
        if (query.addEventListener) query.addEventListener("change", changed);
        else if (query.addListener) query.addListener(changed);
    }

    // The Appearance page only has the radio buttons; this wires them up.
    function wire() {
        var radios = document.querySelectorAll("input[name=theme-choice]");
        if (!radios.length) return;
        var current = stored();
        radios.forEach(function (radio) {
            radio.checked = radio.value === current;
            radio.addEventListener("change", function () {
                if (!radio.checked) return;
                try {
                    if (radio.value === "system") localStorage.removeItem(KEY);
                    else localStorage.setItem(KEY, radio.value);
                } catch (e) { /* private mode: the choice lasts until the page closes */ }
                apply(radio.value);
            });
        });
    }

    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", wire);
    else wire();
})();