(() => {
  'use strict';

  const $ = id => document.getElementById(id);
  const pad = n => String(n).padStart(2, '0');
  const mmss = ms => { const s = Math.floor(ms / 1000); return pad(Math.floor(s / 60)) + ':' + pad(s % 60); };
  const secToMmss = sec => mmss(sec * 1000);
  const BADGE = { AVAILABLE: 'AVAILABLE', IN_USE: 'RUNNING', FAILED: 'FAILED', RECOVERING: 'RECOVERING', OFFLINE: 'OFFLINE' };
  const MAX_LOG = 700;

  let last = null;
  let logs = [];
  let lastLogId = 0;
  let filter = 'all';
  let modalDismissed = false;

  async function call(path, method = 'GET') {
    const res = await fetch(path, { method });
    const body = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(body.error || res.statusText || 'Request failed');
    return body;
  }

  function toast(message) {
    const box = $('toast');
    box.textContent = message;
    box.classList.remove('hidden');
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => box.classList.add('hidden'), 3500);
  }

  function act(path) {
    call(path, 'POST').then(render).catch(e => toast(e.message));
  }

  function machineCard(m, icon) {
    const who = m.customerId > 0 ? 'Customer #' + pad(m.customerId) : '—';
    const sub = m.state === 'IN_USE'
      ? 'Remaining ' + m.remainingSec.toFixed(1) + ' sec'
      : (m.state === 'AVAILABLE' ? 'Idle' : m.stateLabel);
    return `<div class="machine st-${m.state}">
      <div class="m-top">${icon} ${m.name.replace('-', ' ')}</div>
      <span class="badge b-${m.state}">${BADGE[m.state] || m.stateLabel}</span>
      <div class="m-cust">${who}</div>
      <div class="m-rem">${sub}</div>
      <div class="bar"><i style="width:${Math.round(m.progress * 100)}%"></i></div>
    </div>`;
  }

  const chips = ids => ids.length
    ? ids.map(i => `<span class="chip">Customer #${pad(i)}</span>`).join('')
    : '<span class="empty">Queue empty</span>';

  function render(s) {
    last = s;
    const st = s.statistics;
    const p = s.pipeline;

    $('simState').textContent = s.state;
    $('statePill').className = 'state s-' + s.state;
    $('statePill').textContent = s.state;
    $('simMode').textContent = s.mode;
    $('simTime').textContent = mmss(s.elapsedMs);

    const idleLike = s.state === 'IDLE' || s.state === 'STOPPED';
    const active = s.state === 'RUNNING' || s.state === 'PAUSED';
    $('btnStart').disabled = !idleLike;
    $('btnPause').disabled = s.state !== 'RUNNING';
    $('btnResume').disabled = s.state !== 'PAUSED';
    $('btnStop').disabled = !active;
    $('btnReset').disabled = s.state === 'STARTING' || s.state === 'STOPPING';
    ['selMode', 'selFailure'].forEach(id => $(id).disabled = !idleLike);

    $('kArrived').textContent = st.customersArrived + ' / ' + s.totalCustomers;
    $('kActive').textContent = st.customersInSystem;
    $('kDone').textContent = st.customersServed + ' / ' + s.totalCustomers;
    $('kAvg').textContent = st.avgTotalTimeSec.toFixed(1) + ' s';
    $('kFail').textContent = st.washerFailures + ' / ' + st.paymentFailures;

    const stages = [
      ['Arrival', p.arrived, ''],
      ['Wash Queue', p.washQueue, 'queue'],
      ['Washing', p.washing, 'work'],
      ['Dry Queue', p.dryQueue, 'queue'],
      ['Drying', p.drying, 'work'],
      ['Payment Queue', p.paymentQueue, 'queue' + (p.paymentQueue >= s.congestionThreshold ? ' hot' : '')],
      ['Payment', p.paying, 'work'],
      ['Exit', p.completed, 'done']
    ];
    $('pipeline').innerHTML = stages.map((x, i) =>
      `<div class="stage ${x[2]}"><span>${x[0]}</span><b>${x[1]}</b></div>${i < stages.length - 1 ? '<div class="arrow">→</div>' : ''}`
    ).join('');

    $('washers').innerHTML = s.washers.map(m => machineCard(m, 'W')).join('');
    $('dryers').innerHTML = s.dryers.map(m => machineCard(m, 'D')).join('');
    $('kiosks').innerHTML = s.kiosks.map(m => machineCard(m, 'P')).join('');

    $('qw').innerHTML = chips(s.washerQueue);
    $('qd').innerHTML = chips(s.dryerQueue);
    $('qp').innerHTML = chips(s.paymentQueue);
    $('qwc').textContent = s.washerQueue.length;
    $('qdc').textContent = s.dryerQueue.length;
    $('qpc').textContent = s.paymentQueue.length;
    $('payQueueCard').classList.toggle('hot', s.mode === 'CONGESTED' && s.paymentQueue.length >= 10);

    renderBanner(s);

    const tiles = [
      ['Customers served', st.customersServed + ' / ' + s.totalCustomers],
      ['Avg customer time', st.avgTotalTimeSec.toFixed(2) + ' s'],
      ['Washers current / peak', st.currentWashers + ' / 6 · ' + st.peakWashers],
      ['Dryers current / peak', st.currentDryers + ' / 4 · ' + st.peakDryers],
      ['Kiosks current / peak', st.currentKiosks + ' / 2 · ' + st.peakKiosks],
      ['Max payment queue', st.maxPaymentQueue],
      ['Washer failures / retries', st.washerFailures + ' / ' + st.washerRetries],
      ['Payment failures / retries', st.paymentFailures + ' / ' + st.paymentRetries],
      ['Avg washer wait', st.avgWasherWaitSec.toFixed(2) + ' s'],
      ['Avg dryer wait', st.avgDryerWaitSec.toFixed(2) + ' s'],
      ['Avg payment wait', st.avgPaymentWaitSec.toFixed(2) + ' s'],
      ['Throughput', st.throughputPerMin.toFixed(1) + ' / min'],
      ['Congestion events', st.congestionEvents],
      ['Owner called', st.ownerCalled ? 'YES' : 'No'],
      ['Runtime', mmss(s.elapsedMs)]
    ];
    $('stats').innerHTML = tiles.map(t => `<div class="stat"><span>${t[0]}</span><b>${t[1]}</b></div>`).join('');

    $('custBody').innerHTML = s.customers.map(c =>
      `<tr><td>Customer ${pad(c.id)}</td><td>${escapeHtml(c.thread)}</td><td>${escapeHtml(c.stage)}</td><td>${escapeHtml(c.resource)}</td>` +
      `<td><span class="s s-${c.status}">${escapeHtml(c.status)}</span></td><td>${secToMmss(c.arrivalSec)}</td><td>${c.totalSec.toFixed(1)}s</td></tr>`
    ).join('');

    if (s.state === 'COMPLETED' && !modalDismissed) showSummary(s);
    if (s.state !== 'COMPLETED') {
      $('modal').classList.add('hidden');
      if (idleLike || active) modalDismissed = false;
    }
  }

  function renderBanner(s) {
    const b = $('banner');
    b.className = 'banner hidden';
    if (s.mode !== 'CONGESTED' || s.state === 'IDLE') return;
    if (s.ownerStatus === 'PENDING') {
      b.className = 'banner pending';
      b.innerHTML = `<span>Both payment kiosks are out of service.</span><span>Payment queue: ${s.paymentQueue.length} / ${s.congestionThreshold}</span>`;
    } else if (s.ownerStatus === 'CALLED') {
      b.className = 'banner alert';
      b.innerHTML = `<span>PAYMENT CONGESTION DETECTED</span><span>${s.congestionThreshold} CUSTOMERS WAITING</span><span>OWNER HAS BEEN CALLED</span>`;
    } else if (s.ownerStatus === 'RESTORED') {
      b.className = 'banner restored';
      b.innerHTML = `<span>Owner repaired both kiosks — payment processing has resumed.</span>`;
    }
  }

  function showSummary(s) {
    const st = s.statistics;
    const items = [
      ['Customers Served', st.customersServed + ' / ' + s.totalCustomers],
      ['Total Simulation Time', mmss(s.elapsedMs)],
      ['Average Customer Time', st.avgTotalTimeSec.toFixed(1) + ' sec'],
      ['Mode Used', s.mode],
      ['Peak Washer Usage', st.peakWashers + ' / 6'],
      ['Peak Dryer Usage', st.peakDryers + ' / 4'],
      ['Washer Failures', st.washerFailures],
      ['Payment Failures', st.paymentFailures],
      ['Total Retries', st.washerRetries + st.paymentRetries],
      ['Maximum Payment Queue', st.maxPaymentQueue]
    ];
    $('modalBody').innerHTML = items.map(i => `<div><span>${i[0]}</span><b>${i[1]}</b></div>`).join('');
    $('modal').classList.remove('hidden');
  }

  function matches(e) {
    switch (filter) {
      case 'customer': return e.customerId > 0;
      case 'washer': return e.resourceType === 'Washer';
      case 'dryer': return e.resourceType === 'Dryer';
      case 'payment': return e.resourceType === 'Kiosk';
      case 'failures': return e.type === 'FAILURE' || e.type === 'CONGESTION';
      default: return true;
    }
  }

  function logRow(e) {
    const res = e.resourceId && e.resourceId !== '-' ? e.resourceId : '';
    return `<div class="log-row t-${e.type}">` +
      `<span class="l-time">${escapeHtml(e.time)}</span>` +
      `<span class="l-thread" title="${escapeHtml(e.threadName)}">${escapeHtml(e.customerId > 0 ? e.threadName : e.threadName.startsWith('http') ? 'System' : e.threadName)}</span>` +
      `<span class="l-type">${escapeHtml(e.type)}</span>` +
      `<span class="l-msg">${escapeHtml(e.message)}</span>` +
      `<span class="l-res">${escapeHtml(res)}</span></div>`;
  }

  function addLog(e) {
    if (e.id <= lastLogId) return;
    lastLogId = e.id;
    logs.push(e);
    if (logs.length > MAX_LOG) logs.shift();
    const box = $('log');
    if (matches(e)) {
      box.insertAdjacentHTML('beforeend', logRow(e));
      while (box.childElementCount > MAX_LOG) box.removeChild(box.firstChild);
      if ($('autoScroll').checked) box.scrollTop = box.scrollHeight;
    }
  }

  function rebuildLog() {
    $('log').innerHTML = logs.filter(matches).map(logRow).join('');
    if ($('autoScroll').checked) $('log').scrollTop = $('log').scrollHeight;
  }

  function clearLog() {
    logs = [];
    lastLogId = 0;
    $('log').innerHTML = '';
  }

  function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>'"]/g, ch => ({ '&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;' }[ch]));
  }

  $('btnStart').onclick = () => {
    modalDismissed = false;
    act(`/api/simulation/start?mode=${$('selMode').value}&failureMode=${$('selFailure').value}`);
  };
  $('btnPause').onclick = () => act('/api/simulation/pause');
  $('btnResume').onclick = () => act('/api/simulation/resume');
  $('btnStop').onclick = () => act('/api/simulation/stop');
  $('btnReset').onclick = () => { modalDismissed = false; act('/api/simulation/reset'); };
  $('modalClose').onclick = () => { modalDismissed = true; $('modal').classList.add('hidden'); };
  $('modalReset').onclick = () => { modalDismissed = false; act('/api/simulation/reset'); };
  $('filters').onclick = ev => {
    const f = ev.target.dataset.f;
    if (!f) return;
    filter = f;
    document.querySelectorAll('.filter').forEach(b => b.classList.toggle('active', b.dataset.f === f));
    rebuildLog();
  };

  function connect() {
    const es = new EventSource('/api/simulation/events');
    es.onopen = () => { $('linkState').textContent = 'connected'; $('linkPill').className = 'feed ok'; };
    es.onerror = () => { $('linkState').textContent = 'reconnecting…'; $('linkPill').className = 'feed offline'; };
    es.addEventListener('snapshot', ev => render(JSON.parse(ev.data)));
    es.addEventListener('log', ev => addLog(JSON.parse(ev.data)));
    es.addEventListener('reset', clearLog);
  }

  Promise.all([call('/api/simulation/state'), call('/api/simulation/log')])
    .then(([state, history]) => { render(state); history.forEach(addLog); })
    .catch(e => toast('Backend not reachable: ' + e.message))
    .finally(connect);
})();