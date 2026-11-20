(function () {
    // Payments that are still waiting are checked with Paystack automatically, so people do not have to
    // press "Check payment". It stops when nothing is waiting any more, or after about five minutes.
    var forms = document.querySelectorAll('form[action="/dashboard/payments/check"]');
    if (!forms.length) { try { sessionStorage.removeItem("mkPayTries"); } catch (e) {} return; }
    var tries = 0, MAX = 60, EVERY = 5000;

    function checkAll() {
        var list = Array.prototype.slice.call(document.querySelectorAll('form[action="/dashboard/payments/check"]'));
        // One at a time, so the session cookie is never refreshed by two requests at once.
        return list.reduce(function (chain, form) {
            return chain.then(function () {
                return fetch(form.action, { method: "POST", body: new FormData(form), credentials: "same-origin" }).catch(function () {});
            });
        }, Promise.resolve());
    }

    function tick() {
        tries += 1;
        checkAll().then(function () {
            // Reloading shows the new status; if everything is settled the page no longer has a waiting payment.
            window.location.reload();
        });
    }

    var already = 0;
    try { already = parseInt(sessionStorage.getItem("mkPayTries") || "0", 10); sessionStorage.setItem("mkPayTries", String(already + 1)); } catch (e) {}
    if (already >= MAX) return;
    setTimeout(tick, EVERY);
})();
