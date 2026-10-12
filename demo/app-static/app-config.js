/* Licensed under the terms of http://www.apache.org/licenses/LICENSE-2.0 */

window.APP_CONFIG = {
  fusekiBase: '/fuseki',
  // The Fuseki service name (fuseki:name in config.ttl) whose query endpoint answers
  // luc:config, luc:query and luc:facet.
  dataset: 'mining',
  // Cache salt for label lookups. Labels are cached by the browser for a day
  // (see serve_app.py); bump this to invalidate every cached label at once.
  labelCacheVersion: '1',
};
