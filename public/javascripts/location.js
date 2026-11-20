(function () {
    var box = document.getElementById("loc-map");
    var form = document.getElementById("loc-form");
    if (!box || !form) return;

    var area = document.getElementById("area");
    var latField = document.getElementById("lat");
    var lngField = document.getElementById("lng");
    var save = document.getElementById("loc-save");
    var msg = document.getElementById("loc-msg");
    var pinNote = document.getElementById("loc-pin");
    var addr = document.getElementById("addr");
    var find = document.getElementById("find");
    var editable = box.getAttribute("data-editable") === "true";

    // The supported Kenya map area, the same limits as the app.
    var BOUNDS = { south: -5.0, north: 5.5, west: 33.0, east: 42.5 };
    var NAIROBI = [36.8219, -1.2921];

    function say(text) { msg.textContent = text || ""; }

    function inside(lat, lng) {
        return isFinite(lat) && isFinite(lng) &&
            lat >= BOUNDS.south && lat <= BOUNDS.north && lng >= BOUNDS.west && lng <= BOUNDS.east;
    }

    function hasPin() { return latField.value !== "" && lngField.value !== ""; }

    function refresh() {
        if (save) save.disabled = !(editable && area.value && hasPin());
        pinNote.textContent = hasPin()
            ? "Property pin selected · Exact location stays private."
            : "No property pin selected yet.";
    }


    // ---------- Google Maps (used when a Maps key is configured) ----------
    var mapsKey = box.getAttribute("data-maps-key") || "";
    if (mapsKey) {
        window.gm_authFailure = function () {
            say("Google rejected the Maps key. Check the key's allowed website address and APIs. Your saved location is unchanged.");
        };
        window.__locInit = function () {
            var startLat = parseFloat(latField.value);
            var startLng = parseFloat(lngField.value);
            var startPin = hasPin() && inside(startLat, startLng);
            var map = new google.maps.Map(box, {
                center: startPin ? { lat: startLat, lng: startLng } : { lat: NAIROBI[1], lng: NAIROBI[0] },
                zoom: startPin ? 17 : 11,
                restriction: { latLngBounds: BOUNDS, strictBounds: false },
                gestureHandling: "cooperative",
                mapTypeControl: true,
                streetViewControl: false,
                fullscreenControl: false
            });
            var marker = new google.maps.Marker({
                map: startPin ? map : null,
                position: startPin ? { lat: startLat, lng: startLng } : null
            });

            function setPin(lat, lng) {
                if (!inside(lat, lng)) {
                    say("Choose a pin inside the supported Kenya map area.");
                    return false;
                }
                latField.value = lat.toFixed(7);
                lngField.value = lng.toFixed(7);
                marker.setPosition({ lat: lat, lng: lng });
                marker.setMap(map);
                say("");
                refresh();
                return true;
            }

            if (editable) {
                map.addListener("click", function (e) {
                    if (!area.value) {
                        say("Choose the county first, then tap the property on the map.");
                        return;
                    }
                    setPin(e.latLng.lat(), e.latLng.lng());
                });
                // Changing the county clears the pin, so the area and the pin can never disagree.
                area.addEventListener("change", function () {
                    latField.value = "";
                    lngField.value = "";
                    marker.setMap(null);
                    say("");
                    refresh();
                });
            }

            if (find && addr) {
                find.addEventListener("click", function () {
                    var text = addr.value.trim();
                    if (!area.value) { say("Choose the county first."); return; }
                    if (text.length < 3) { say("Type at least 3 characters of the address."); return; }
                    find.disabled = true;
                    say("Searching…");
                    new google.maps.Geocoder().geocode({
                        address: text + ", " + area.value + ", Kenya",
                        region: "ke",
                        componentRestrictions: { country: "KE" }
                    }, function (results, status) {
                        find.disabled = false;
                        if (status === "ZERO_RESULTS" || !results || !results.length) {
                            say("No location was found. Try another address or tap it on the map.");
                            return;
                        }
                        if (status !== "OK") {
                            say("Address search could not complete. Try again or tap the property on the map.");
                            return;
                        }
                        var spot = results[0].geometry.location;
                        if (setPin(spot.lat(), spot.lng())) {
                            map.setCenter(spot);
                            map.setZoom(17);
                            say("Address found. Check the pin is on the right property before saving.");
                        }
                    });
                });
            }
            refresh();
        };
        var tag = document.createElement("script");
        tag.src = "https://maps.googleapis.com/maps/api/js?key=" + encodeURIComponent(mapsKey) + "&callback=__locInit&v=weekly";
        tag.async = true;
        tag.onerror = function () {
            say("The map could not load. Check your connection and refresh the page. Your saved location is unchanged.");
        };
        document.head.appendChild(tag);
        refresh();
        return;
    }

    if (typeof maplibregl === "undefined") {
        say("The map could not load. Check your connection and refresh the page. Your saved location is unchanged.");
        refresh();
        return;
    }

    var startLat = parseFloat(latField.value);
    var startLng = parseFloat(lngField.value);
    var startPin = hasPin() && inside(startLat, startLng);

    var map = new maplibregl.Map({
        container: "loc-map",
        style: "https://tiles.openfreemap.org/styles/liberty",
        center: startPin ? [startLng, startLat] : NAIROBI,
        zoom: startPin ? 17 : 11,
        maxBounds: [[BOUNDS.west, BOUNDS.south], [BOUNDS.east, BOUNDS.north]],
        cooperativeGestures: true,          // so scrolling the page doesn't zoom the map by accident
        attributionControl: { compact: true }
    });
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), "top-right");

    var marker = new maplibregl.Marker({ color: "#672D06" });
    if (startPin) marker.setLngLat([startLng, startLat]).addTo(map);

    var warned = false;
    map.on("error", function () {
        if (warned) return;
        warned = true;
        say("The map could not load fully. Check your connection and refresh. Your saved location is unchanged.");
    });

    function setPin(lat, lng) {
        if (!inside(lat, lng)) {
            say("Choose a pin inside the supported Kenya map area.");
            return false;
        }
        latField.value = lat.toFixed(7);
        lngField.value = lng.toFixed(7);
        marker.setLngLat([lng, lat]).addTo(map);
        say("");
        refresh();
        return true;
    }

    function clearPin() {
        latField.value = "";
        lngField.value = "";
        marker.remove();
        refresh();
    }

    if (editable) {
        map.on("click", function (e) {
            if (!area.value) {
                say("Choose the county first, then tap the property on the map.");
                return;
            }
            setPin(e.lngLat.lat, e.lngLat.lng);
        });

        // Changing the county clears the pin, so the area and the pin can never disagree.
        area.addEventListener("change", function () {
            clearPin();
            say("");
        });
    } else {
        map.dragRotate.disable();
    }

    // Optional address search. It only runs when the person presses the button.
    if (find && addr) {
        find.addEventListener("click", function () {
            var text = addr.value.trim();
            if (!area.value) { say("Choose the county first."); return; }
            if (text.length < 3) { say("Type at least 3 characters of the address."); return; }

            find.disabled = true;
            say("Searching…");

            var url = "https://nominatim.openstreetmap.org/search?format=json&limit=1&countrycodes=ke&q=" +
                encodeURIComponent(text + ", " + area.value + ", Kenya");

            fetch(url, { headers: { "Accept": "application/json" } })
                .then(function (response) { return response.ok ? response.json() : null; })
                .then(function (results) {
                    if (!results) { say("Address search could not complete. Try again or tap the property on the map."); return; }
                    if (!results.length) { say("No location was found. Try another address or tap it on the map."); return; }
                    var lat = parseFloat(results[0].lat);
                    var lng = parseFloat(results[0].lon);
                    if (setPin(lat, lng)) {
                        map.flyTo({ center: [lng, lat], zoom: 17 });
                        say("Address found. Check the pin is on the right property before saving.");
                    }
                })
                .catch(function () { say("Address search could not complete. Try again or tap the property on the map."); })
                .then(function () { find.disabled = false; });
        });
    }

    refresh();
})();