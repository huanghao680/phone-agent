// CDP probe for the phone-agent webUIs: verify viewport patch + synthetic pinch zoom.
// Usage: node cdp-pinch.mjs <wsUrl> [pinch]
import WebSocket from 'ws';

const wsUrl = process.argv[2];
const doPinch = process.argv.includes('pinch');
const ws = new WebSocket(wsUrl, { perMessageDeflate: false });
let id = 0;
const pending = new Map();

function send(method, params = {}) {
  return new Promise((res, rej) => {
    const mid = ++id;
    pending.set(mid, { res, rej });
    ws.send(JSON.stringify({ id: mid, method, params }));
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

ws.on('message', (d) => {
  const m = JSON.parse(d.toString());
  if (m.id && pending.has(m.id)) {
    const { res, rej } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? rej(new Error(m.error.message)) : res(m.result);
  }
});

ws.on('open', async () => {
  try {
    const vp = await send('Runtime.evaluate', {
      expression: "document.querySelector('meta[name=viewport]')?.content || 'NO-META'",
      returnByValue: true,
    });
    console.log('VIEWPORT:', vp.result.value);

    const scaleBefore = await send('Runtime.evaluate', {
      expression: 'window.visualViewport ? window.visualViewport.scale : -1',
      returnByValue: true,
    });
    console.log('SCALE_BEFORE:', scaleBefore.result.value);

    if (doPinch) {
      // two-pointer pinch-out centered mid-screen
      const cx = 500, cy = 400;
      const pts = (d) => ([
        { x: cx - d, y: cy, id: 11 },
        { x: cx + d, y: cy, id: 12 },
      ]);
      const seq = [
        { type: 'touchStart', touchPoints: pts(60).map(p => ({ ...p, radiusX: 2, radiusY: 2, force: 0.5 })) },
        { type: 'touchMove', touchPoints: pts(100).map(p => ({ ...p, radiusX: 2, radiusY: 2, force: 0.5 })) },
        { type: 'touchMove', touchPoints: pts(150).map(p => ({ ...p, radiusX: 2, radiusY: 2, force: 0.5 })) },
        { type: 'touchMove', touchPoints: pts(200).map(p => ({ ...p, radiusX: 2, radiusY: 2, force: 0.5 })) },
        { type: 'touchEnd', touchPoints: [] },
      ];
      for (const ev of seq) {
        await send('Input.dispatchTouchEvent', ev);
        await sleep(60);
      }
      await sleep(400);
    }

    const scaleAfter = await send('Runtime.evaluate', {
      expression: 'window.visualViewport ? window.visualViewport.scale : -1',
      returnByValue: true,
    });
    console.log('SCALE_AFTER:', scaleAfter.result.value);

    const pageScale = await send('Runtime.evaluate', {
      expression: 'window.innerWidth',
      returnByValue: true,
    });
    console.log('INNER_WIDTH:', pageScale.result.value);
  } catch (e) {
    console.error('ERR:', e.message);
    process.exitCode = 1;
  } finally {
    ws.close();
  }
});
ws.on('error', (e) => { console.error('WSERR:', e.message); process.exit(1); });
