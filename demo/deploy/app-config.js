/* Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 */

/* Deployed demo: Fuseki serves this app itself (--base), so the SPARQL endpoints are
   same-origin. The local workflow keeps '/fuseki', where serve_app.py proxies to a
   separately started server.

   window.location.origin, not '': app.js reads the base as
   `APP_CONFIG.fusekiBase || 'http://localhost:3030'`, and an empty string is falsy, so
   the same-origin answer would be discarded for the hardcoded default — which on a
   developer's machine is some other Fuseki, and on a deployed host is nothing at all.
   Taking the origin from the browser also means the image needs no knowledge of the URL
   it ends up published under. */

window.APP_CONFIG = {
  fusekiBase: window.location.origin,
  labelCacheVersion: '1',
};
