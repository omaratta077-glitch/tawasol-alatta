/* رابط تجريبي لفتح YTV المثبّت من Chrome على أندرويد. لا يتجاوز شرط BROWSABLE. */
(function (global) {
  'use strict';
  const PACKAGE = 'com.ytv.pronew';
  const COMPONENT = 'com.ytv.pronew/dev.google.ytvplayer.ui.splash.SplashActivity';
  const STORE = 'https://play.google.com/store/apps/details?id=com.ytv.pronew';
  const HELP = '/ytv-open.html';
  const channelId = value => {
    const s = String(value ?? '').trim();
    if (!/^[1-9][0-9]{0,18}$/.test(s)) return null;
    try { return BigInt(s) <= 9223372036854775807n ? s : null; }
    catch { return null; }
  };
  function intentFor(value, siteOrigin) {
    const id = channelId(value);
    if (!id) return null;
    const fallback = encodeURIComponent(new URL(HELP, siteOrigin).href);
    // Android native activity is explicitly targeted. Chrome may strip component
    // and requires the target app to declare a BROWSABLE intent-filter.
    // No published YTV browser deep-link is documented: device test is essential.
    return 'intent:#Intent;action=android.intent.action.VIEW;'
      + 'category=android.intent.category.BROWSABLE;'
      + 'package=' + PACKAGE + ';component=' + COMPONENT + ';'
      + 'l.id=' + id + ';i.type=1;'
      + 'S.browser_fallback_url=' + fallback + ';end';
  }
  function url(value, userAgent = global.navigator.userAgent, siteOrigin = global.location.origin) {
    if (!/android/i.test(String(userAgent))) return HELP;
    return intentFor(value, siteOrigin) || HELP;
  }
  global.AttaYtvWebLaunch = Object.freeze({url, intentFor, channelId, storeUrl:STORE, helpUrl:HELP});
})(window);
