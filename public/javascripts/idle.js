(function () {
    // Signs the person out after two minutes without any activity on the page, like the Android app.
    var LIMIT = 2 * 60 * 1000;
    var form = document.querySelector(".logout-form");
    if (!form) return;
    var timer;

    function leave() {
        var flag = document.createElement("input");
        flag.type = "hidden";
        flag.name = "idle";
        flag.value = "1";
        form.appendChild(flag);
        form.submit();
    }

    function reset() {
        clearTimeout(timer);
        timer = setTimeout(leave, LIMIT);
    }

    ["mousemove", "mousedown", "keydown", "scroll", "touchstart", "click"].forEach(function (name) {
        window.addEventListener(name, reset, { passive: true });
    });
    reset();
})();
