const fs = require('fs'), path = require('path'), z = require('node:zlib');
const root = process.argv[process.argv.length - 1];
const MAGIC = Buffer.from([0x28, 0xb5, 0x2f, 0xfd]);
function decode(buf) {
  const offs = [];
  let i = 0;
  while ((i = buf.indexOf(MAGIC, i)) !== -1) { offs.push(i); i += 4; }
  const parts = [];
  for (const off of offs) {
    try { parts.push(z.zstdDecompressSync(buf.subarray(off))); } catch (e) { parts.push(Buffer.from('<badframe:' + e.message + '>')); }
  }
  return Buffer.concat(parts).toString('utf8');
}
const eventTypes = {};
let samples = 0;
for (const dir of fs.readdirSync(root)) {
  const dp = path.join(root, dir);
  let st; try { st = fs.statSync(dp); } catch { continue; }
  if (!st.isDirectory()) continue;
  for (const f of fs.readdirSync(dp)) {
    if (!/jsonl/.test(f)) continue;
    let txt;
    try { txt = decode(fs.readFileSync(path.join(dp, f))); } catch (e) { console.log('FAIL', dir, e.message); continue; }
    const lines = txt.split('\n').filter(Boolean);
    console.log('FILE', dir.slice(0, 20), 'events', lines.length);
    for (const l of lines) {
      let o; try { o = JSON.parse(l); } catch { continue; }
      eventTypes[o.type] = (eventTypes[o.type] || 0) + 1;
      if (samples < 8 && (o.type === 'llm/response' || /usage/i.test(JSON.stringify(o).slice(0, 2000)))) {
        samples++;
        console.log('  SAMPLE', o.type, l.slice(0, 500));
      }
    }
  }
}
console.log('EVENT_TYPES:');
for (const [k, v] of Object.entries(eventTypes)) console.log(' ', v, 'x', k);
