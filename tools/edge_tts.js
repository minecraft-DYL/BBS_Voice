/*
 * Edge "read aloud" TTS client - reference implementation.
 *
 *   node tools/edge_tts.js <ssml.xml> <out.bin> [outputFormat]
 *
 * Microsoft Edge's read-aloud endpoint, driven directly. This is the working recipe as of the
 * Chromium 143 endpoint and it is what tools/edge_tts_probe.ps1 failed to reproduce: undici's
 * WebSocket (Node's global one) cannot set headers, and the service now rejects a request without
 *   - Sec-MS-GEC-Version matching a current Chromium version, and
 *   - a `muid` cookie, and
 *   - a browser User-Agent.
 * Drop any one of the three and the upgrade comes back 403 Forbidden.
 *
 * Because the standard WebSocket API cannot set those headers, the handshake is done by hand over
 * tls and the framing is implemented here. That also makes this file the reference for the Java
 * port, which can use java.net.http.WebSocket.Builder.header(...) instead.
 *
 * The orchestrator:
 *   1. GET with Upgrade headers -> 101
 *   2. send Path:speech.config (JSON, chooses the output format)
 *   3. send Path:ssml (the document to speak)
 *   4. read frames until Path:turn.end; binary frames whose header says Path:audio are the audio
 */
const fs = require('fs');
const crypto = require('crypto');
const tls = require('tls');

const TRUSTED = '6A5AA1D4EAFF4E9FB37E23D68491D6F4';
const CHROMIUM = '143.0.3650.75';
const MAJOR = CHROMIUM.split('.')[0];

const [, , ssmlFile, outFile, format] = process.argv;

if (!ssmlFile || !outFile) {
  console.error('usage: node tools/edge_tts.js <ssml.xml> <out.bin> [outputFormat]');
  process.exit(2);
}

/* sha256 of (windows file time in 100 ns ticks, floored to a 5 minute boundary) + trusted token. */
function secMsGec(skewSeconds = 0) {
  let ticks = Date.now() / 1000 + skewSeconds + 11644473600;
  ticks -= ticks % 300;
  ticks *= 1e9 / 100;
  return crypto.createHash('sha256').update(`${ticks.toFixed(0)}${TRUSTED}`, 'ascii').digest('hex').toUpperCase();
}

/* --- minimal websocket framing ------------------------------------------------------------------ */

function sendFrame(sock, opcode, payload) {
  const len = payload.length;
  const ext = len < 126 ? 0 : (len < 65536 ? 2 : 8);
  const header = Buffer.alloc(2 + ext + 4);
  header[0] = 0x80 | opcode;
  if (ext === 0) header[1] = 0x80 | len;
  else if (ext === 2) { header[1] = 0x80 | 126; header.writeUInt16BE(len, 2); }
  else { header[1] = 0x80 | 127; header.writeBigUInt64BE(BigInt(len), 2); }
  const mask = crypto.randomBytes(4);
  mask.copy(header, 2 + ext);
  const masked = Buffer.allocUnsafe(len);
  for (let i = 0; i < len; i++) masked[i] = payload[i] ^ mask[i & 3];
  sock.write(Buffer.concat([header, masked]));
}

const sendText = (sock, s) => sendFrame(sock, 0x1, Buffer.from(s, 'utf8'));

function makeFrameParser(onMessage) {
  let buf = Buffer.alloc(0);
  let fragOp = 0;
  let frag = null;

  return (chunk) => {
    buf = Buffer.concat([buf, chunk]);
    for (;;) {
      if (buf.length < 2) return;
      const fin = (buf[0] & 0x80) !== 0;
      const opcode = buf[0] & 0x0f;
      const masked = (buf[1] & 0x80) !== 0;
      let len = buf[1] & 0x7f;
      let off = 2;
      if (len === 126) { if (buf.length < 4) return; len = buf.readUInt16BE(2); off = 4; }
      else if (len === 127) { if (buf.length < 10) return; len = Number(buf.readBigUInt64BE(2)); off = 10; }
      let maskKey = null;
      if (masked) { if (buf.length < off + 4) return; maskKey = buf.subarray(off, off + 4); off += 4; }
      if (buf.length < off + len) return;
      let payload = Buffer.from(buf.subarray(off, off + len));
      if (masked) for (let i = 0; i < payload.length; i++) payload[i] ^= maskKey[i & 3];
      buf = buf.subarray(off + len);

      if (opcode === 0x8) { onMessage({ opcode: 'close', payload }); return; }
      if (opcode === 0x9) continue;                     /* ping: ignore */
      if (opcode === 0xa) continue;                     /* pong */
      if (opcode === 0x0) { frag = frag ? Buffer.concat([frag, payload]) : payload; }
      else { fragOp = opcode; frag = payload; }
      if (fin) { onMessage({ opcode: fragOp, payload: frag }); frag = null; }
    }
  };
}

/* --- the conversation --------------------------------------------------------------------------- */

const ssml = fs.readFileSync(ssmlFile, 'utf8');
const cid = crypto.randomUUID().replace(/-/g, '');
const path = '/consumer/speech/synthesize/readaloud/edge/v1'
  + `?TrustedClientToken=${TRUSTED}&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=1-${CHROMIUM}&ConnectionId=${cid}`;

const request = [
  `GET ${path} HTTP/1.1`,
  'Host: speech.platform.bing.com',
  'Connection: Upgrade',
  'Upgrade: websocket',
  'Sec-WebSocket-Version: 13',
  `Sec-WebSocket-Key: ${crypto.randomBytes(16).toString('base64')}`,
  'Pragma: no-cache',
  'Cache-Control: no-cache',
  `Origin: chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold`,
  `Cookie: muid=${crypto.randomBytes(16).toString('hex').toUpperCase()};`,
  'Accept-Encoding: gzip, deflate, br, zstd',
  'Accept-Language: en-US,en;q=0.9',
  `User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${MAJOR}.0.0.0 Safari/537.36 Edg/${MAJOR}.0.0.0`,
  '',
  ''
].join('\r\n');

const chunks = [];
let metas = 0;
let bytesIn = 0;

const sock = tls.connect({ host: 'speech.platform.bing.com', servername: 'speech.platform.bing.com', port: 443 }, () => {
  sock.write(request);
});

const finish = (why) => {
  sock.destroy();
  const audio = Buffer.concat(chunks);
  fs.writeFileSync(outFile, audio);
  console.log(`[${why}] wrote ${outFile}  ${audio.length} bytes  (metadata frames: ${metas}, received ${bytesIn} bytes)`);
  if (audio.length >= 12) {
    console.log(`  magic: ascii="${audio.toString('ascii', 0, 4)}" hex=${audio.subarray(0, 4).toString('hex')}`);
  }
  process.exit(audio.length > 0 ? 0 : 1);
};

let handshakeDone = false;
let preamble = Buffer.alloc(0);

sock.on('error', (e) => { console.error('socket error: ' + e.message); finish('error'); });
sock.on('close', () => { if (!handshakeDone) finish('closed-before-handshake'); });

const onMessage = ({ opcode, payload }) => {
  if (opcode === 'close') {
    const code = payload.length >= 2 ? payload.readUInt16BE(0) : 0;
    const reason = payload.length > 2 ? payload.subarray(2).toString('utf8') : '';
    console.error(`close frame: code=${code} reason="${reason}"`);
    finish('server-close-' + code);
    return;
  }
  if (opcode === 0x1) {
    const text = payload.toString('utf8');
    if (/Path:turn\.end/.test(text)) { finish('turn.end'); return; }
    if (/Path:audio\.metadata/i.test(text)) { metas++; return; }
    console.error('server: ' + text.slice(text.indexOf('\r\n\r\n') + 4).slice(0, 200));
    return;
  }
  if (opcode === 0x2) {
    const headerLength = payload.readUInt16BE(0);
    const header = payload.toString('ascii', 2, 2 + headerLength);
    if (/Path:audio/.test(header)) chunks.push(Buffer.from(payload.subarray(2 + headerLength)));
  }
};

const parseFrame = makeFrameParser(onMessage);

sock.on('data', (chunk) => {
  bytesIn += chunk.length;

  if (!handshakeDone) {
    preamble = Buffer.concat([preamble, chunk]);
    const end = preamble.indexOf('\r\n\r\n');
    if (end < 0) return;
    const head = preamble.toString('latin1', 0, end);
    const status = head.split('\r\n')[0];
    if (!/101/.test(status)) {
      console.error('handshake failed: ' + status);
      finish('http-' + status.replace(/\D/g, ''));
      return;
    }
    handshakeDone = true;
    console.log('handshake: ' + status);

    const ts = new Date().toISOString();
    const cfg = JSON.stringify({
      context: { synthesis: { audio: {
        metadataoptions: { sentenceBoundaryEnabled: 'false', wordBoundaryEnabled: 'true' },
        outputFormat: format || 'audio-24khz-48kbitrate-mono-mp3'
      } } }
    });
    sendText(sock, `X-Timestamp:${ts}\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n${cfg}`);
    sendText(sock, `X-RequestId:${cid}\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:${ts}\r\nPath:ssml\r\n\r\n${ssml}`);

    const rest = preamble.subarray(end + 4);
    preamble = Buffer.alloc(0);
    if (rest.length) parseFrame(rest);
    return;
  }

  parseFrame(chunk);
});

setTimeout(() => { console.error('timeout'); finish('timeout'); }, 60000).unref();
