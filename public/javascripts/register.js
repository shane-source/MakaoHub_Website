(function () {
    var form = document.getElementById("register-form");
    if (!form) return;

    // We show our own messages, so turn off the browser's built-in pop-ups.
    form.noValidate = true;

    var submit = form.querySelector("button[type=submit]");
    var touched = {};

    function value(name) {
        return form.elements[name].value;
    }

    // Each rule returns an error message, or "" when the value is fine.
    var rules = {
        fullName: function (v) {
            var name = v.trim();
            var ok = /^[\p{L}][\p{L} .'-]{1,78}$/u.test(name) && name.split(/\s+/).length >= 2;
            return ok ? "" : "Enter your first and last name.";
        },
        email: function (v) {
            return /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(v.trim()) ? "" : "Enter a valid email address.";
        },
        phone: function (v) {
            var digits = v.replace(/[\s-]/g, "");
            return /^(?:\+254|254|0)[17]\d{8}$/.test(digits) ? "" : "Enter a Kenyan number, like 0712 345 678.";
        },
        password: function (v) {
            var ok = v.length >= 8 && v.length <= 72 && /[A-Za-z]/.test(v) && /\d/.test(v);
            return ok ? "" : "Use 8 to 72 characters, with a letter and a number.";
        },
        confirmPassword: function (v) {
            return v !== "" && v === value("password") ? "" : "Passwords do not match.";
        },
        terms: function () {
            return form.elements.terms.checked ? "" : "Accept the Terms and Privacy Policy to continue.";
        }
    };

    var names = Object.keys(rules);

    function error(name) {
        return rules[name](value(name));
    }

    // Show or clear the message for one field (only after the person has visited it).
    function paint(name) {
        var input = form.elements[name];
        var message = error(name);
        var show = !!touched[name];
        input.setAttribute("aria-invalid", show && message ? "true" : "false");
        input.classList.toggle("ok", show && !message);
        document.getElementById(name + "-msg").textContent = show ? message : "";
    }

    // The button is only active when every field is valid.
    function refresh() {
        submit.disabled = names.some(function (n) { return error(n) !== ""; });
    }

    names.forEach(function (name) {
        var input = form.elements[name];

        input.addEventListener("blur", function () {
            touched[name] = true;
            paint(name);
        });

        ["input", "change"].forEach(function (type) {
            input.addEventListener(type, function () {
                if (touched[name]) paint(name);
                if (name === "password" && touched.confirmPassword) paint("confirmPassword");
                refresh();
            });
        });
    });

    // Safety net: if someone submits anyway (for example with the Enter key), show every error.
    form.addEventListener("submit", function (e) {
        names.forEach(function (n) { touched[n] = true; paint(n); });
        if (names.some(function (n) { return error(n) !== ""; })) e.preventDefault();
    });

    window.addEventListener("pageshow", refresh);
    refresh();
})();