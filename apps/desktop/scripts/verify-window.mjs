// Test-only WebView2 inspection. The packaged app does not enable remote debugging.
import assert from 'node:assert/strict';
const pages = await fetch('http://127.0.0.1:9222/json/list').then((r) => r.json());
const page = pages.find(
  (p) => p.type === 'page' && /^https?:\/\/tauri\.localhost(?:\/|$)/.test(p.url),
);
assert.ok(page, `Embedded app page was not loaded: ${pages.map((p) => p.url).join(', ')}`);
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.addEventListener('open', resolve, { once: true });
  ws.addEventListener('error', reject, { once: true });
});
let id = 0;
function cdp(method, params = {}) {
  return new Promise((resolve, reject) => {
    const request = ++id;
    const timer = setTimeout(() => {
      ws.removeEventListener('message', receive);
      reject(new Error(`${method} timed out`));
    }, 15000);
    function receive({ data }) {
      const message = JSON.parse(data);
      if (message.id !== request) return;
      clearTimeout(timer);
      ws.removeEventListener('message', receive);
      if (message.error) reject(new Error(JSON.stringify(message.error)));
      else resolve(message.result);
    }
    ws.addEventListener('message', receive);
    ws.send(JSON.stringify({ id: request, method, params }));
  });
}
async function evaluate(expression) {
  const result = await cdp('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  });
  assert.ok(!result.exceptionDetails, JSON.stringify(result.exceptionDetails));
  return result.result?.value;
}
try {
  assert.equal(
    await evaluate(
      `window.__TAURI_INTERNALS__.invoke('plugin:window|is_fullscreen',{label:'main'})`,
    ),
    false,
    'Launch should not be fullscreen',
  );
  assert.equal(
    await evaluate(
      `window.__TAURI_INTERNALS__.invoke('plugin:window|is_maximized',{label:'main'})`,
    ),
    true,
    'Launch should be maximized',
  );
  await evaluate(
    `window.dispatchEvent(new KeyboardEvent('keydown',{key:'F11',code:'F11',bubbles:true}))`,
  );
  await new Promise((r) => setTimeout(r, 300));
  assert.equal(
    await evaluate(
      `window.__TAURI_INTERNALS__.invoke('plugin:window|is_fullscreen',{label:'main'})`,
    ),
    true,
    'F11 should enter fullscreen',
  );
  const text = await evaluate('document.body.innerText');
  assert.match(text ?? '', /dazedev/i, 'DazeDev interface is missing');
  assert.match(text, /connect your first host/i, 'Initial host action is missing');
  await evaluate(`document.querySelector('button[aria-label="Settings"]').click()`);
  await evaluate(
    `new Promise((resolve,reject)=>{let tries=0;const t=setInterval(()=>{if(document.querySelector('.highlight-preview')){clearInterval(t);resolve(true)}else if(++tries>100){clearInterval(t);reject(Error('Settings preview missing'))}},50)})`,
  );
  // The preview sits in a collapsed section; content in a closed <details>
  // gets no resize events, so open it before measuring.
  await evaluate(`document.querySelector('.highlight-preview').closest('details').open = true`);
  assert.equal(
    await evaluate(
      `document.querySelectorAll('[role=radio'+'group][aria-label="Transcription model"] input[type=radio]').length`,
    ),
    5,
  );
  assert.equal(
    await evaluate(`document.querySelectorAll('select option[value=parakeet]').length`),
    0,
    'No duplicate model dropdown',
  );
  await evaluate(`document.querySelector('.highlight-preview').scrollIntoView({block:'center'})`);
  assert.equal(
    await evaluate('document.documentElement.dataset.highlightMotion'),
    'animate',
    'Legacy default must migrate to app-controlled animation',
  );
  for (const reduced of [true, false]) {
    await cdp('Emulation.setEmulatedMedia', {
      features: [{ name: 'prefers-reduced-motion', value: reduced ? 'reduce' : 'no-preference' }],
    });
    for (const mode of ['system', 'animate', 'static']) {
      for (const effect of ['energy', 'rainbow']) {
        await evaluate(`(() => {
          const select = (option,value) => { const s=[...document.querySelectorAll('select')].find(s=>[...s.options].some(o=>o.value===option)); s.value=value; s.dispatchEvent(new Event('change',{bubbles:true})); };
          select('animate',${JSON.stringify(mode)}); select('energy',${JSON.stringify(effect)});
        })()`);
        await evaluate(
          `new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))`,
        );
        if (effect === 'energy') {
          const motes = await evaluate(
            `(() => { const motes=[...document.querySelectorAll('.highlight-preview .mote')]; return {count:motes.length,speeds:motes.slice(0,3).map(i=>i.style.getPropertyValue('--speed'))}; })()`,
          );
          const size = await evaluate(
            `(() => { const r=document.querySelector('.highlight-preview .energy-border').getBoundingClientRect(); return {w:r.width,h:r.height}; })()`,
          );
          // Same density as energyCount in src/lib/energy-motion.ts.
          assert.equal(
            motes.count,
            Math.max(24, Math.min(80, Math.ceil((2 * (size.w + size.h)) / 24))),
          );
          assert.ok(
            motes.speeds.every((s) => parseFloat(s) >= 20),
            'Motes should move at the slower organic pace',
          );
        }
        const result = await evaluate(`new Promise(resolve=>setTimeout(async()=>{
          const sample=()=>{const p=document.querySelector('.highlight-preview'),i=p.querySelector('i');return [getComputedStyle(i).transform,getComputedStyle(i).opacity]};
          const a=sample(); await new Promise(r=>setTimeout(r,600)); resolve({a,b:sample(),notice:document.body.innerText.includes('Your system has reduced motion enabled')});
        },100))`);
        const shouldMove = mode === 'animate' || (mode === 'system' && !reduced);
        assert.equal(
          JSON.stringify(result.a) !== JSON.stringify(result.b),
          shouldMove,
          `${effect}/${mode}/reduced=${reduced}: ${JSON.stringify(result)}`,
        );
        assert.equal(result.notice, reduced && mode === 'system');
      }
    }
  }
  const saved = await evaluate(`window.__TAURI_INTERNALS__.invoke('device_load')`);
  assert.equal(saved.highlightMotion, 'static', 'Highlight motion choice was not saved');
  console.log(
    'Packaged WebView2: embedded interface, energy/rainbow movement, system preference, explicit overrides and portable setting persistence passed.',
  );
} finally {
  ws.close();
}
