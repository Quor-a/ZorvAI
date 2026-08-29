const port = parseInt(process.argv[2] || '8766', 10);
const http = require('http');
const server = http.createServer((req, res) => {
  let body = '';
  req.on('data', c => body += c);
  req.on('end', () => {
    res.writeHead(200, {'Content-Type': 'application/json'});
    res.end(JSON.stringify({module: 'quro.term.node', status: 'ok', echo: req.url, body: body, method: req.method}));
  });
});
server.listen(port, '0.0.0.0', () => console.log('node backend on ' + port));
