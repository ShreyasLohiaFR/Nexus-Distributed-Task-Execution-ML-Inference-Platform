'use strict';
const $ = id => document.getElementById(id);
let key = '';
let connected = false;
let refreshRunning = false;
let submitting = false;
let pendingSubmission = null;
let session = 0;
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
  $('submit-button').disabled = !ok || submitting;
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
  const currentSession = session;
  try {
    const query = $('filter').value ? '?state=' + encodeURIComponent($('filter').value) : '';
    const [counts, jobs] = await Promise.all([request('/stats'), request('/jobs' + query)]);
    if (session !== currentSession) return;
    for (const s of states) $(s).textContent = counts[s] ?? 0;
    renderRows(jobs);
    const viewed = selectedJob;
    if (viewed) {
      const details = await request(`/jobs/${viewed}`);
      if (session !== currentSession) return;
      if (selectedJob === viewed) showDetails(details);
    }
    connection(true, 'Connected');
  } catch (e) { if (session === currentSession) connection(false, `Unavailable · ${e.message}`); }
  finally { refreshRunning = false; if (session !== currentSession) refresh(); }
}
$('connect').addEventListener('click', () => {
  if (submitting) return;
  session++; key = $('key').value.trim(); $('key').value = ''; selectedJob = null; pendingSubmission = null;
  $('details').hidden = true; $('detail-json').textContent = ''; $('rows').replaceChildren();
  for (const s of states) $(s).textContent = '—';
  connection(false, key ? 'Connecting…' : 'Enter workspace key'); refresh();
});
$('key').addEventListener('keydown', e => { if (e.key === 'Enter') $('connect').click(); });
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
  e.preventDefault(); if (!connected || submitting) return;
  submitting = true;
  const button = $('submit-button'); button.disabled = true; $('connect').disabled = true;
  const message = $('form-message');
  const kind = $('kind').value;
  const data = {kind, payload:kind === 'DIGIT_CLASSIFY' ? {pixels:[...pixels]} : {text:$('payload').value}, priority:Number($('priority').value), maxAttempts:Number($('attempts').value), delaySeconds:Number($('delay').value)};
  try {
    const body = JSON.stringify(data);
    if (!pendingSubmission || pendingSubmission.body !== body) pendingSubmission = {body, id:crypto.randomUUID()};
    const job = await request('/jobs', {method:'POST', headers:{'Idempotency-Key':pendingSubmission.id}, body});
    pendingSubmission = null;
    message.textContent = `Accepted job ${job.id.slice(0,8)}`; message.className = 'mini success'; selectedJob = job.id; await refresh();
  } catch(e) { message.textContent = `Submission not confirmed: ${e.message}. Retry the unchanged form to reuse the request key.`; message.className = 'mini error'; }
  finally { submitting = false; button.disabled = !connected; $('connect').disabled = false; }
});
setInterval(refresh, 2000);
