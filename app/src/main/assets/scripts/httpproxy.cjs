// Minimal HTTP forward proxy for opencode on Android.
//
// Bun's DNS resolver cannot read Android's DNS configuration (no
// /etc/resolv.conf), so every hostname lookup inside the opencode process
// fails or falls back to localhost. Node's resolver works fine, so this proxy
// listens on 127.0.0.1 and forwards both plain HTTP (absolute URI) and HTTPS
// (CONNECT tunnel), letting Bun delegate all name resolution to Node.
const http = require('http');
const net = require('net');

const PORT = Number(process.env.PORT || 8118);

const server = http.createServer((req, res) => {
  let u;
  try {
    u = new URL(req.url);
  } catch {
    res.writeHead(400);
    res.end('bad request');
    return;
  }
  const opts = {
    hostname: u.hostname,
    port: u.port || 80,
    path: u.pathname + u.search,
    method: req.method,
    headers: { ...req.headers, host: u.host },
  };
  const preq = http.request(opts, (prs) => {
    res.writeHead(prs.statusCode, prs.headers);
    prs.pipe(res);
  });
  preq.on('error', (e) => {
    try {
      res.writeHead(502);
      res.end('proxy error: ' + e.message);
    } catch {}
  });
  req.pipe(preq);
});

// HTTPS: CONNECT tunnel; name resolution happens HERE via node net.connect
server.on('connect', (req, sock, head) => {
  let host, port;
  try {
    const idx = req.url.lastIndexOf(':');
    host = idx === -1 ? req.url : req.url.slice(0, idx);
    port = idx === -1 ? 443 : Number(req.url.slice(idx + 1));
  } catch {
    sock.destroy();
    return;
  }
  const upstream = net.connect(port, host, () => {
    sock.write('HTTP/1.1 200 Connection Established\r\n\r\n');
    if (head && head.length) upstream.write(head);
    upstream.pipe(sock);
    sock.pipe(upstream);
  });
  upstream.setTimeout(120000, () => {
    upstream.destroy();
    sock.destroy();
  });
  upstream.on('error', () => sock.destroy());
  sock.on('error', () => upstream.destroy());
});

server.listen(PORT, '127.0.0.1', () => {
  console.log('[httpproxy] listening on 127.0.0.1:' + PORT);
});
