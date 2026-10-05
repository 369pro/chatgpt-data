// Expose only the signed callback, never the application or account APIs.
const http = require('node:http');
const route = '/api/v1/sale/alipay/notify';
const port = Number(process.env.PAYMENT_CALLBACK_PORT || 18091);
const backendPort = Number(process.env.PAYMENT_BACKEND_PORT || 8093);
http.createServer((request, response) => {
    if (request.method !== 'POST' || request.url !== route) {
        response.writeHead(404).end('Not found');
        return;
    }
    let size = 0;
    const chunks = [];
    request.on('data', chunk => {
        size += chunk.length;
        if (size > 65536) { response.writeHead(413).end(); request.destroy(); }
        else chunks.push(chunk);
    });
    request.on('end', () => {
        if (response.writableEnded) return;
        const body = Buffer.concat(chunks);
        const upstream = http.request({hostname: '127.0.0.1', port: backendPort, path: route,
            method: 'POST', timeout: 10000, headers: {
                'content-type': 'application/x-www-form-urlencoded', 'content-length': body.length,
            }}, result => {
            response.writeHead(result.statusCode, {'content-type': 'text/plain;charset=UTF-8'});
            result.pipe(response);
        });
        upstream.on('timeout', () => upstream.destroy());
        upstream.on('error', () => { if (!response.headersSent) response.writeHead(502); response.end('failure'); });
        upstream.end(body);
    });
}).listen(port, '127.0.0.1', () => console.log('Payment callback proxy listening on 127.0.0.1:' + port));
