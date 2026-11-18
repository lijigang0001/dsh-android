#!/usr/bin/env node
/**
 * Mobile preview proxy for the DSH Web UI.
 *
 * Serves the real running DSH web app on a second port, adding the signed
 * browser-session cookie (so a headless browser needs no launch token) and
 * injecting the Android WebView mobile shim into the served HTML. WebSocket
 * upgrades are tunnelled verbatim so the client UI behaves exactly as it does
 * inside the app.
 *
 *   node tools/mobile-preview.mjs [--upstream 127.0.0.1:3080] [--listen 3099]
 *                                 [--credentials <DSH_HOME>/.credentials.yaml]
 *                                 [--css captures/shim.css] [--js captures/shim.js]
 *                                 [--upstream-host <host:port used in the Host header>]
 */
import { createServer, request as httpRequest } from 'node:http';
import { connect } from 'node:net';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { forgeCookie, readSigningSecret } from './dsh-auth.mjs';

const argv = process.argv.slice(2);
const arg = (name, fallback) => {
  const at = argv.indexOf(`--${name}`);
  return at >= 0 && at + 1 < argv.length ? argv[at + 1] : fallback;
};

const upstream = arg('upstream', '127.0.0.1:3080');
const listenPort = Number(arg('listen', '3099'));
const credentials = arg('credentials', 'C:\\Users\\lenovo\\.dsh\\.credentials.yaml');
const cssPath = arg('css', '');
const jsPath = arg('js', '');
const [upstreamHost, upstreamPort] = upstream.split(':');
const authority = arg('upstream-host', `${upstreamHost}:${upstreamPort}`);

const secret = readSigningSecret(credentials);
const cookie = forgeCookie(secret, authority);
const cookieHeader = `${cookie.name}=${cookie.value}`;

const css = cssPath && existsSync(cssPath) ? readFileSync(cssPath, 'utf8') : '';
const js = jsPath && existsSync(jsPath) ? readFileSync(jsPath, 'utf8') : '';

/** Insert the shim into a served HTML document, right before </head>. */
function inject(html) {
  const payload = `${css ? `<style id="dsh-mobile-shim">${css}</style>` : ''}${js ? `<script id="dsh-mobile-shim-js">${js}</script>` : ''}`;
  if (!payload) return html;
  const marker = '</head>';
  const at = html.toLowerCase().indexOf(marker);
  if (at === -1) return `${payload}${html}`;
  return `${html.slice(0, at)}${payload}${html.slice(at)}`;
}

const server = createServer((req, res) => {
  const headers = { ...req.headers, host: authority, cookie: cookieHeader };
  // Ask upstream for identity encoding: HTML must be readable to inject the shim.
  delete headers['accept-encoding'];
  delete headers['content-length'];
  // The /api browser-trust fence compares Origin/Referer with the Host authority.
  if (headers.origin) headers.origin = `http://${authority}`;
  if (headers.referer) headers.referer = String(headers.referer).replace(/^https?:\/\/[^/]+/u, `http://${authority}`);
  const upstreamReq = httpRequest(
    { host: upstreamHost, port: Number(upstreamPort), method: req.method, path: req.url, headers },
    (upstreamRes) => {
      const contentType = String(upstreamRes.headers['content-type'] ?? '');
      if (!contentType.includes('text/html')) {
        res.writeHead(upstreamRes.statusCode ?? 502, upstreamRes.headers);
        upstreamRes.pipe(res);
        return;
      }
      const chunks = [];
      upstreamRes.on('data', (chunk) => chunks.push(chunk));
      upstreamRes.on('end', () => {
        const body = Buffer.from(inject(Buffer.concat(chunks).toString('utf8')), 'utf8');
        const outHeaders = { ...upstreamRes.headers };
        delete outHeaders['content-length'];
        delete outHeaders['transfer-encoding'];
        outHeaders['content-length'] = String(body.length);
        res.writeHead(upstreamRes.statusCode ?? 502, outHeaders);
        res.end(body);
      });
    },
  );
  upstreamReq.on('error', (error) => {
    res.writeHead(502, { 'content-type': 'text/plain; charset=utf-8' });
    res.end(`preview proxy: upstream error: ${error.message}\n`);
  });
  req.pipe(upstreamReq);
});

server.on('upgrade', (req, socket, head) => {
  const upstreamSocket = connect(Number(upstreamPort), upstreamHost, () => {
    const lines = [`${req.method} ${req.url} HTTP/1.1`];
    for (const [name, value] of Object.entries(req.headers)) {
      if (name.toLowerCase() === 'host') lines.push(`host: ${authority}`);
      else if (name.toLowerCase() === 'cookie') lines.push(`cookie: ${cookieHeader}`);
      else if (Array.isArray(value)) lines.push(`${name}: ${value.join(', ')}`);
      else lines.push(`${name}: ${value}`);
    }
    if (!('cookie' in req.headers)) lines.push(`cookie: ${cookieHeader}`);
    upstreamSocket.write(`${lines.join('\r\n')}\r\n\r\n`);
    if (head?.length) upstreamSocket.write(head);
    socket.pipe(upstreamSocket);
    upstreamSocket.pipe(socket);
  });
  upstreamSocket.on('error', () => socket.destroy());
  socket.on('error', () => upstreamSocket.destroy());
});

server.listen(listenPort, '127.0.0.1', () => {
  const info = { url: `http://127.0.0.1:${listenPort}/`, upstream, authority, css: css.length, js: js.length };
  writeFileSync('captures/preview-info.json', JSON.stringify(info, null, 2));
  console.log(JSON.stringify(info));
});
