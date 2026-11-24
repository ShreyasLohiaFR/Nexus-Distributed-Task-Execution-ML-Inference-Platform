'use strict';
const $ = id => document.getElementById(id);
let key = '';
let connected = false;
let refreshRunning = false;
let selectedJob = null;
const pixels = Array(64).fill(0);
const states = ['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'];

async function request(path, options = {}) {
  const response = await fetch('/api' + path, {
    ...options, headers: {'X-API-Key': key, 'Content-Type': 'application/json', ...options.headers},
    signal: AbortSignal.timeout(8000)
  });
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}
function connection(ok, message) {
  connected = ok;
  $('connection').textContent = message;
  $('indicator').classList.toggle('live', ok);
  $('submit-button').disabled = !ok;
}
function textCell(row, text) {
  const td = document.createElement('td');
  td.textContent = text;
  row.appendChild(td);
  return td;
}
function action(label, handler) {
  const button = document.createElement('button');
  button.type = 'button'; button.className = 'secondary'; button.textContent = label;
  button.addEventListener('click', handler);
  return button;
}
function renderRows(jobs) {
  $('rows').replaceChildren();
  if (!jobs.length) {
    const tr = document.createElement('tr');
    const td = textCell(tr, 'No jobs match this filter.'); td.colSpan = 5;
    $('rows').append(tr); return;
  }
  for (const job of jobs) {
    const tr = document.createElement('tr');
    const first = textCell(tr, '');
    const code = document.createElement('code'); code.textContent = job.id.slice(0, 8);
    const kind = document.createElement('small'); kind.textContent = job.kind; first.append(code, kind);
    const state = textCell(tr, ''); const badge = document.createElement('span');
    badge.textContent = job.state; badge.className = 'pill ' + (states.includes(job.state) ? job.state : ''); state.append(badge);
    textCell(tr, job.priority); textCell(tr, `${job.attempts}/${job.maxAttempts}`);
    const actions = textCell(tr, ''); actions.className = 'actions';
    actions.append(action('View', () => { selectedJob = job.id; showDetails(job); }));
    if (['QUEUED', 'RUNNING'].includes(job.state)) actions.append(action('Cancel', async () => {
      try { await request(`/jobs/${job.id}/cancel`, {method: 'POST'}); await refresh(); }
      catch(e) { $('form-message').textContent = `Cancellation: ${e.message}`; }
    }));
    $('rows').append(tr);
  }
}
function showDetails(job) {
  $('details').hidden = false;
  $('detail-json').textContent = JSON.stringify(job, null, 2);
}
async function refresh() {
  if (!key || refreshRunning) return;
  refreshRunning = true;
  try {
    const query = $('filter').value ? '?state=' + encodeURIComponent($('filter').value) : '';
    const [counts, jobs] = await Promise.all([request('/stats'), request('/jobs' + query)]);
    for (const s of states) $(s).textContent = counts[s] ?? 0;
    renderRows(jobs);
    if (selectedJob) showDetails(await request(`/jobs/${selectedJob}`));
    connection(true, 'Connected');
  } catch (e) { connection(false, `Unavailable · ${e.message}`); }
  finally { refreshRunning = false; }
}
$('connect').addEventListener('click', () => { key = $('key').value.trim(); $('key').value = ''; selectedJob = null; $('details').hidden = true; refresh(); });
$('filter').addEventListener('change', refresh);
$('close-details').addEventListener('click', () => { selectedJob = null; $('details').hidden = true; });
$('kind').addEventListener('change', () => {
  const ml = $('kind').value === 'DIGIT_CLASSIFY'; $('digit-field').hidden = !ml; $('text-field').hidden = ml;
});
function setPixel(i, value) {
  pixels[i] = value; const cell = $('pixels').children[i]; cell.classList.toggle('on', value > 0);
  cell.setAttribute('aria-pressed', String(value > 0));
}
for (let i = 0; i < 64; i++) {
  const cell = document.createElement('button'); cell.type = 'button'; cell.setAttribute('aria-label', `Pixel ${i+1}`);
  cell.setAttribute('aria-pressed', 'false'); cell.addEventListener('click', () => setPixel(i, pixels[i] ? 0 : 16));
  cell.addEventListener('pointerenter', e => { if (e.buttons === 1) setPixel(i, 16); }); $('pixels').append(cell);
}
$('clear').onclick = () => pixels.forEach((_, i) => setPixel(i, 0));
$('sample').onclick = () => { const pattern = ['00011000','00111000','01111000','00011000','00011000','00011000','00111100','00000000'].join(''); [...pattern].forEach((n,i) => setPixel(i, Number(n)*16)); };
$('submit').addEventListener('submit', async e => {
  e.preventDefault(); if (!connected) return;
  const button = $('submit-button'); button.disabled = true;
  const message = $('form-message');
  const kind = $('kind').value;
  const data = {kind, payload:kind === 'DIGIT_CLASSIFY' ? {pixels:[...pixels]} : {text:$('payload').value}, priority:Number($('priority').value), maxAttempts:Number($('attempts').value), delaySeconds:Number($('delay').value)};
  try {
    const job = await request('/jobs', {method:'POST', headers:{'Idempotency-Key':crypto.randomUUID()}, body:JSON.stringify(data)});
    message.textContent = `Accepted job ${job.id.slice(0,8)}`; message.className = 'mini success'; selectedJob = job.id; await refresh();
  } catch(e) { message.textContent = `Submission failed: ${e.message}. Inspect jobs before retrying.`; message.className = 'mini error'; }
  finally { button.disabled = !connected; }
});
setInterval(refresh, 2000);
