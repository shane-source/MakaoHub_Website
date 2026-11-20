// Renders Google's own sign-in button and hands the resulting ID token to a small form.
// Every step is logged to the browser console with the prefix [google] (never the token itself).
(function () {
  var TAG = '[google]';
  function log() { try { console.log.apply(console, [TAG].concat([].slice.call(arguments))); } catch (e) {} }
  function warn() { try { console.warn.apply(console, [TAG].concat([].slice.call(arguments))); } catch (e) {} }

  var box = document.getElementById('google-button');
  var form = document.getElementById('google-form');
  if (!box || !form) { warn('button or form not found on this page'); return; }
  var field = form.querySelector('[name=idToken]');
  var clientId = box.getAttribute('data-client-id') || '';

  log('page origin:', window.location.origin);
  log('client id ends with:', clientId.slice(-30), '(must be allowed for the origin above in Google Cloud Console)');

  function showProblem(text) {
    warn(text);
    var note = document.getElementById('google-problem');
    if (!note) {
      note = document.createElement('p');
      note.id = 'google-problem';
      note.className = 'form-error';
      note.setAttribute('role', 'alert');
      box.parentNode.insertBefore(note, box.nextSibling);
    }
    note.textContent = text;
  }

  window.addEventListener('securitypolicyviolation', function (e) {
    warn('Blocked by the browser security policy:', e.violatedDirective, e.blockedURI);
    showProblem('Google sign-in was blocked by the browser security policy (' + e.violatedDirective + ' blocked ' + e.blockedURI + ').');
  });

  function start() {
    log('Google script loaded');
    if (!window.google || !google.accounts || !google.accounts.id) {
      showProblem('Google sign-in could not start: the Google library did not load.');
      return;
    }
    try {
      google.accounts.id.initialize({
        client_id: clientId,
        auto_select: false,
        callback: function (response) {
          log('callback fired, credential present:', !!(response && response.credential),
              'length:', response && response.credential ? response.credential.length : 0,
              'select_by:', response && response.select_by);
          if (!response || !response.credential) {
            showProblem('Google did not return a sign-in token. Please try again.');
            return;
          }
          field.value = response.credential;
          log('submitting form to', form.getAttribute('action'));
          form.submit();
        }
      });
      var width = Math.min(Math.max(box.parentElement.clientWidth || 320, 240), 400);
      google.accounts.id.renderButton(box, {
        type: 'standard', theme: 'outline', size: 'large', shape: 'rectangular',
        text: box.getAttribute('data-text') || 'continue_with', logo_alignment: 'left', width: width
      });
      log('button rendered');
    } catch (err) {
      showProblem('Google sign-in could not start: ' + (err && err.message ? err.message : err));
      return;
    }
    // If Google refuses the origin, the button area stays empty or shows an error inside Google's frame.
    setTimeout(function () {
      if (!box.querySelector('iframe') && !box.querySelector('div[role=button]')) {
        showProblem('The Google button did not appear. Check that ' + window.location.origin +
                    ' is listed under Authorized JavaScript origins for this client ID.');
      }
    }, 4000);
  }

  var script = document.createElement('script');
  script.src = 'https://accounts.google.com/gsi/client';
  script.async = true;
  script.defer = true;
  script.onload = start;
  script.onerror = function () {
    showProblem('Could not load Google sign-in from accounts.google.com. Check the internet connection, ad blockers, or the site security policy.');
  };
  log('loading https://accounts.google.com/gsi/client');
  document.head.appendChild(script);
})();
