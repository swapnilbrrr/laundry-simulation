(() => {
  const $ = id => document.getElementById(id);
  const pad = n => String(n).padStart(2, "0");
  const mmss = ms => { const s = Math.floor(ms / 1000); return pad(Math.floor(s / 60)) + ":" + pad(s % 60); };
  const help = {
    NORMAL: "Normal mode follows the required timing and shared resources.",
    CONGESTED: "Congested mode starts with both payment kiosks offline; the owner is called at 30 waiting customers."
  };
  let seen = new Set();

  async function post(url) {
    const r = await fetch(url, {method:"POST"});
    if (!r.ok) { const d = await r.json().catch(() => ({})); throw new Error(d.error || "Request failed"); }
    return r.json();
  }

  function card(m) {
    const c = m.customerId > 0 ? "Customer #" + pad(m.customerId) : "Unassigned";
    const pct = Math.round(m.progress * 100);
    const eta = m.state === "IN_USE" ? " · " + m.remainingSec.toFixed(1) + "s" : "";
    return "<div class="machine " + m.state + ""><b>" + m.name.replace("-", " ") + "</b><small>" + m.stateLabel + eta + "</small><small>" + c + "</small><i style="width:" + pct + "%"></i></div>";
  }

  function step(label, value, cls) {
    return "<div class="step " + (cls || "") + ""><small>" + label + "</small><b>" + value + "</b></div>";
  }

  function render(s) {
    const x = s.statistics, p = s.pipeline;
    $("state").textContent = s.state;
    $("clock").textContent = mmss(s.elapsedMs);
    $("modeHelp").textContent = help[s.mode];
    $("mode").disabled = !["IDLE","STOPPED"].includes(s.state);
    $("failure").disabled = !["IDLE","STOPPED"].includes(s.state);
    $("start").disabled = !["IDLE","STOPPED"].includes(s.state);
    $("pause").disabled = s.state !== "RUNNING";
    $("resume").disabled = s.state !== "PAUSED";
    $("stop").disabled = !["RUNNING","PAUSED"].includes(s.state);
    $("reset").disabled = ["STARTING","STOPPING"].includes(s.state);

    $("headline").textContent = s.state === "IDLE" ? "Ready to run" : s.mode + " · " + s.state.toLowerCase();
    $("subtitle").textContent = x.customersServed + " of " + s.totalCustomers + " customers completed the full cycle.";
    $("served").textContent = x.customersServed + " / " + s.totalCustomers;
    $("active").textContent = x.customersInSystem;
    $("avg").textContent = x.avgTotalTimeSec.toFixed(1) + " s";
    $("fail").textContent = x.washerFailures + " / " + x.paymentFailures;

    $("flow").innerHTML = step("Arrived", p.arrived) +
      step("Wash queue", p.washQueue, "queue") + step("Washing", p.washing, "work") +
      step("Dry queue", p.dryQueue, "queue") + step("Drying", p.drying, "work") +
      step("Payment queue", p.paymentQueue, "queue") + step("Payment", p.paying, "work") +
      step("Exit", p.completed, "done");

    $("resources").innerHTML =
      "<div class="resource"><div class="resource-title"><span>Washers</span><b>" + x.currentWashers + " / 6</b></div><div class="machine-grid">" + s.washers.map(card).join("") + "</div></div>" +
      "<div class="resource"><div class="resource-title"><span>Dryers</span><b>" + x.currentDryers + " / 4</b></div><div class="machine-grid dry">" + s.dryers.map(card).join("") + "</div></div>" +
      "<div class="resource"><div class="resource-title"><span>Payment kiosks</span><b>" + x.currentKiosks + " / 2</b></div><div class="machine-grid kiosks">" + s.kiosks.map(card).join("") + "</div></div>";

    const q = (name,n,max) => "<div class="row"><span>" + name + "</span><b>" + n + "</b></div><div class="bar"><i style="width:" + Math.min(100,n / max * 100) + "%"></i></div>";
    $("queues").innerHTML = q("Wash", s.washerQueue.length, 12) + q("Dry", s.dryerQueue.length, 12) + q("Payment", s.paymentQueue.length, s.congestionThreshold);

    const owner = $("owner");
    if (s.mode !== "CONGESTED") { owner.textContent = "Owner: not required"; owner.className = "owner"; }
    else if (s.ownerStatus === "PENDING") { owner.textContent = "Owner: waiting for " + s.congestionThreshold + " customers"; owner.className = "owner alert"; }
    else if (s.ownerStatus === "CALLED") { owner.textContent = "Owner: called · kiosks awaiting repair"; owner.className = "owner alert"; }
    else { owner.textContent = "Owner: repairs complete · payment resumed"; owner.className = "owner good"; }

    $("telemetry").innerHTML =
      "<div><small>Peak washers</small><b>" + x.peakWashers + " / 6</b></div>" +
      "<div><small>Peak dryers</small><b>" + x.peakDryers + " / 4</b></div>" +
      "<div><small>Peak kiosks</small><b>" + x.peakKiosks + " / 2</b></div>" +
      "<div><small>Max payment queue</small><b>" + x.maxPaymentQueue + "</b></div>" +
      "<div><small>Washer retries</small><b>" + x.washerRetries + "</b></div>" +
      "<div><small>Payment retries</small><b>" + x.paymentRetries + "</b></div>";
  }

  function log(e) {
    if (seen.has(e.id)) return;
    seen.add(e.id);
    const row = document.createElement("div");
    row.className = "event " + e.type;
    row.innerHTML = "<span class="muted">" + e.time + "</span><span class="thread" title="" + e.threadName + "">" + e.threadName + "</span><b>" + e.type + "</b><span>" + e.message + "</span>";
    $("log").appendChild(row);
    while ($("log").children.length > 90) $("log").removeChild($("log").firstChild);
    $("log").scrollTop = $("log").scrollHeight;
  }

  function toast(msg) {
    $("toast").textContent = msg;
    $("toast").classList.remove("hidden");
    clearTimeout(window.__toast);
    window.__toast = setTimeout(() => $("toast").classList.add("hidden"), 2500);
  }

  $("start").onclick = async () => { try { await post("/api/simulation/start?mode=" + $("mode").value + "&failureMode=" + $("failure").value); } catch(e) { toast(e.message); } };
  $("pause").onclick = async () => { try { await post("/api/simulation/pause"); } catch(e) { toast(e.message); } };
  $("resume").onclick = async () => { try { await post("/api/simulation/resume"); } catch(e) { toast(e.message); } };
  $("stop").onclick = async () => { try { await post("/api/simulation/stop"); } catch(e) { toast(e.message); } };
  $("reset").onclick = async () => { try { seen = new Set(); $("log").innerHTML = ""; await post("/api/simulation/reset"); } catch(e) { toast(e.message); } };

  fetch("/api/simulation/state").then(r => r.json()).then(render).catch(() => toast("Backend not reachable"));
  fetch("/api/simulation/log").then(r => r.json()).then(list => list.forEach(log));
  const es = new EventSource("/api/simulation/events");
  es.onopen = () => { $("connection").textContent = "connected"; $("connection").className = "pill ok"; };
  es.onerror = () => { $("connection").textContent = "reconnecting"; $("connection").className = "pill"; };
  es.addEventListener("snapshot", e => render(JSON.parse(e.data)));
  es.addEventListener("log", e => log(JSON.parse(e.data)));
  es.addEventListener("reset", () => { seen = new Set(); $("log").innerHTML = ""; });
})();
