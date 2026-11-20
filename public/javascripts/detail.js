(function () {
  var box = document.querySelector('[data-pager]');
  if (box) {
    var slides = Array.prototype.slice.call(box.querySelectorAll('.hm-slide'));
    var count = box.querySelector('[data-count]');
    var next = box.querySelector('[data-next]');
    var at = 0;
    var show = function () {
      slides = slides.filter(function (s) { return s.isConnected; });
      if (!slides.length) { if (count) count.textContent = ''; return; }
      if (at >= slides.length) at = 0;
      slides.forEach(function (s, i) { s.style.display = i === at ? 'block' : 'none'; });
      if (count) count.textContent = (at + 1) + ' / ' + slides.length + (slides.length > 1 ? '  ·  Next' : '');
      if (next) next.style.display = slides.length > 1 ? '' : 'none';
    };
    if (next) next.addEventListener('click', function () { at += 1; show(); });
    if (count) count.addEventListener('click', function () { at += 1; show(); });
    box.addEventListener('error', function () { setTimeout(show, 0); }, true);
    show();
  }
  Array.prototype.forEach.call(document.querySelectorAll('[data-share]'), function (b) {
    b.addEventListener('click', function () {
      var data = { title: document.title, url: location.href };
      if (navigator.share) { navigator.share(data).catch(function () {}); return; }
      if (navigator.clipboard) {
        navigator.clipboard.writeText(location.href).then(function () { b.textContent = 'Link copied'; });
      }
    });
  });
})();
