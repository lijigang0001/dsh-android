/*
 * DSH Mobile — WebView shim script.
 *
 * Injected into every finished page load by MainActivity. It:
 *   1. tags <html> with dsh-mobile so the shim stylesheet can hook the DOM;
 *   2. rewrites the viewport meta for WebView (device-width or desktop width,
 *      interactive-widget=resizes-content so the IME resizes the layout);
 *   3. keeps the focused input above the on-screen keyboard;
 *   4. turns blob:/data: downloads into files Android can save, because a
 *      WebView cannot save them through DownloadListener at all.
 *
 * The native half is window.DshBridge (see DshBridge.java): beginDownload,
 * chunkDownload, endDownload, downloadUrl, toast.
 */
window.__dshMobileShim = function (css, mode) {
  var doc = document;
  var html = doc.documentElement;
  if (!html) return;

  html.classList.add('dsh-mobile');
  html.setAttribute('data-dsh-mobile', mode || 'responsive');

  var style = doc.getElementById('dsh-mobile-style');
  if (!style) {
    style = doc.createElement('style');
    style.id = 'dsh-mobile-style';
    (doc.head || html).appendChild(style);
  }
  if (typeof css === 'string' && style.textContent !== css) style.textContent = css;

  var viewport = doc.querySelector('meta[name="viewport"]');
  if (!viewport) {
    viewport = doc.createElement('meta');
    viewport.setAttribute('name', 'viewport');
    (doc.head || html).appendChild(viewport);
  }
  var content = mode === 'desktop'
    ? 'width=1280, initial-scale=0.1, maximum-scale=5, viewport-fit=cover'
    : 'width=device-width, initial-scale=1, maximum-scale=5, viewport-fit=cover, interactive-widget=resizes-content';
  if (viewport.getAttribute('content') !== content) viewport.setAttribute('content', content);

  if (html.getAttribute('data-dsh-mobile-bound') === '1') return;
  html.setAttribute('data-dsh-mobile-bound', '1');

  var bridge = window.DshBridge;

  var isTextField = function (node) {
    if (!node || !node.tagName) return false;
    var tag = node.tagName;
    return tag === 'TEXTAREA' || tag === 'INPUT' || node.isContentEditable === true;
  };

  var keepVisible = function (target) {
    if (!target || !target.getBoundingClientRect) return;
    var rect = target.getBoundingClientRect();
    var visible = window.visualViewport ? window.visualViewport.height : window.innerHeight;
    if (rect.bottom > visible - 16 || rect.top < 0) {
      try {
        target.scrollIntoView({ block: 'center', inline: 'nearest' });
      } catch (error) {
        target.scrollIntoView();
      }
    }
  };

  doc.addEventListener('focusin', function (event) {
    var target = event.target;
    if (!isTextField(target)) return;
    setTimeout(function () { keepVisible(target); }, 300);
  }, true);

  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', function () {
      var active = doc.activeElement;
      if (isTextField(active)) setTimeout(function () { keepVisible(active); }, 80);
    });
  }

  // ---- blob:/data: downloads ------------------------------------------------
  var chunkSize = 256 * 1024; // multiple of 4 so base64 slice boundaries are legal
  var usableName = function (name) {
    var cleaned = String(name || '').replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').trim();
    if (!cleaned) cleaned = 'dsh-download-' + Date.now();
    return cleaned.length > 120 ? cleaned.slice(cleaned.length - 120) : cleaned;
  };

  var sendBase64 = function (name, mime, base64) {
    if (!bridge || !bridge.beginDownload) return false;
    try {
      bridge.beginDownload(usableName(name), mime || 'application/octet-stream');
      for (var at = 0; at < base64.length; at += chunkSize) {
        bridge.chunkDownload(base64.slice(at, at + chunkSize));
      }
      bridge.endDownload();
      return true;
    } catch (error) {
      try { bridge.toast('保存失败：' + (error && error.message ? error.message : error)); } catch (ignored) { /* bridge gone */ }
      return false;
    }
  };

  var saveDataUrl = function (href, name) {
    var comma = href.indexOf(',');
    if (comma < 0) return false;
    var meta = href.slice(5, comma);
    var mime = (meta.split(';')[0] || 'application/octet-stream');
    var payload = meta.indexOf('base64') >= 0
      ? href.slice(comma + 1)
      : btoa(unescape(encodeURIComponent(decodeURIComponent(href.slice(comma + 1)))));
    return sendBase64(name, mime, payload);
  };

  var downloadFromHref = function (href, name) {
    if (!href) return false;
    if (href.indexOf('blob:') === 0) {
      fetch(href).then(function (response) { return response.blob(); }).then(function (blob) {
        var reader = new FileReader();
        reader.onload = function () {
          var result = String(reader.result);
          var comma = result.indexOf(',');
          sendBase64(name, blob.type || 'application/octet-stream', comma < 0 ? '' : result.slice(comma + 1));
        };
        reader.readAsDataURL(blob);
      }).catch(function () {
        try { bridge.toast('无法读取页面生成的下载数据'); } catch (ignored) { /* no bridge */ }
      });
      return true;
    }
    if (href.indexOf('data:') === 0) return saveDataUrl(href, name);
    return false;
  };

  var anchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var href = this.href || '';
    if (this.hasAttribute('download') && downloadFromHref(href, this.getAttribute('download'))) return;
    return anchorClick.apply(this, arguments);
  };

  doc.addEventListener('click', function (event) {
    var node = event.target;
    while (node && node.tagName !== 'A') node = node.parentElement;
    if (!node || !node.href) return;
    if (!/^(blob:|data:)/.test(node.href)) return;
    if (downloadFromHref(node.href, node.getAttribute('download'))) event.preventDefault();
  }, true);

  var openOriginal = window.open;
  window.open = function (url) {
    if (typeof url === 'string' && downloadFromHref(url, 'dsh-download-' + Date.now())) return null;
    return openOriginal.apply(window, arguments);
  };

  // ---- native hooks the shell can call -------------------------------------
  window.__dshMobileReloadShim = function (nextCss, nextMode) {
    html.setAttribute('data-dsh-mobile-bound', '0');
    window.__dshMobileShim(nextCss, nextMode);
  };
};
