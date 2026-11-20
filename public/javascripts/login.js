(function () {
    var form = document.getElementById("login-form");
    if (!form) return;

    // We show our own messages, so turn off the browser's built-in pop-ups.
    form.noValidate = true;

    var touched = {};
    var rules = {
        email: function (v) {
            return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(v.trim()) ? "" : "Enter a valid email address.";
        },
        password: function (v) {
            return v.length > 0 ? "" : "Enter your password.";
        }
    };
    var names = Object.keys(rules);

    function error(name) {
        return rules[name](form.elements[name].value);
    }

    function paint(name) {
        var input = form.elements[name];
        var message = error(name);
        var show = !!touched[name];
        input.setAttribute("aria-invalid", show && message ? "true" : "false");
        input.classList.toggle("ok", show && !message);
        document.getElementById(name + "-msg").textContent = show ? message : "";
    }

    names.forEach(function (name) {
        var input = form.elements[name];
        input.addEventListener("blur", function () { touched[name] = true; paint(name); });
        input.addEventListener("input", function () { if (touched[name]) paint(name); });
    });

    form.addEventListener("submit", function (e) {
        var firstBad = null;
        names.forEach(function (n) {
            touched[n] = true;
            paint(n);
            if (!firstBad && error(n) !== "") firstBad = form.elements[n];
        });
        if (firstBad) {
            e.preventDefault();
            firstBad.focus();
        }
    });
})();
