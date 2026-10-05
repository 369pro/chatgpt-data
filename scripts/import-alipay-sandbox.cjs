// Import the user's existing sandbox configuration without printing credentials.
const fs = require('node:fs');
const path = require('node:path');
const source = process.argv[2];
if (!source) throw new Error('Usage: node scripts/import-alipay-sandbox.cjs /path/to/AlipayConfig.java');
const text = fs.readFileSync(source, 'utf8');
function value(name) {
    const match = text.match(new RegExp('\\b' + name + '\\s*=\\s*"([^"\\r\\n]+)"'));
    if (!match) throw new Error('Missing configuration field: ' + name);
    return match[1];
}
const destination = path.join(__dirname, '..', '.env.payment');
const data = [
    'payment.alipay.enabled=true',
    'payment.alipay.app-id=' + value('APPID'),
    'payment.alipay.private-key=' + value('RSA_PRIVATE_KEY'),
    'payment.alipay.public-key=' + value('ALIPAY_PUBLIC_KEY'),
    'payment.alipay.seller-id=',
    'payment.alipay.notify-url=',
    'payment.alipay.return-url=http://localhost:3003/#/sale',
    '',
].join('\n');
fs.writeFileSync(destination, data, {flag: 'wx', mode: 0o600});
console.log('Imported sandbox configuration into .env.payment (credentials not displayed).');
