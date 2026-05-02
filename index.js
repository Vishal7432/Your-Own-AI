// ════════════════════════════════════════════════════════════
//  CONSTANTS & STATE
// ════════════════════════════════════════════════════════════
const API = "http://localhost:8080";

let allItems = []; // all vectors from /items
let pcaPoints = []; // 2D projected points
let hitIds = new Set(); // highlighted item ids
let queryPt = null; // query star position
let hoverItem = null; // hovered item
let pulse = 0; // animation pulse
let selectedAlgo = "hnsw"; // current algo

// ════════════════════════════════════════════════════════════
//  ALGO SELECTOR
// ════════════════════════════════════════════════════════════
function setAlgo(el) {
  document
    .querySelectorAll(".algo")
    .forEach((a) => a.classList.remove("active"));
  el.classList.add("active");

  const text = el.innerText.toLowerCase().trim();
  if (text === "hnsw") selectedAlgo = "hnsw";
  else if (text === "kd") selectedAlgo = "kdtree";
  else selectedAlgo = "bruteforce";

  console.log("Algo changed to:", selectedAlgo);
}

// ════════════════════════════════════════════════════════════
//  TAB SWITCHER
// ════════════════════════════════════════════════════════════
function showTab(i) {
  document
    .querySelectorAll(".tab")
    .forEach((t, n) => t.classList.toggle("active", n === i));
  document
    .querySelectorAll(".tabpane")
    .forEach((p, n) => p.classList.toggle("active", n === i));
}

// ════════════════════════════════════════════════════════════
//  PCA — 768D → 2D
// ════════════════════════════════════════════════════════════
function pca2D(embs) {
  const n = embs.length,
    d = embs[0].length;
  if (n < 2) return embs.map(() => [0, 0]);

  // mean center
  const mean = new Array(d).fill(0);
  for (const e of embs) for (let i = 0; i < d; i++) mean[i] += e[i] / n;
  const X = embs.map((e) => e.map((v, i) => v - mean[i]));

  function powerIter(X, excl) {
    let v = new Array(d).fill(0).map(() => Math.random() - 0.5);
    if (excl) {
      let dot = v.reduce((s, vi, i) => s + vi * excl[i], 0);
      v = v.map((vi, i) => vi - dot * excl[i]);
    }
    let nrm = Math.sqrt(v.reduce((s, vi) => s + vi * vi, 0));
    v = v.map((vi) => vi / nrm);
    for (let it = 0; it < 200; it++) {
      const Xv = X.map((xi) => xi.reduce((s, xij, j) => s + xij * v[j], 0));
      const nv = new Array(d).fill(0);
      for (let k = 0; k < n; k++)
        for (let j = 0; j < d; j++) nv[j] += X[k][j] * Xv[k];
      if (excl) {
        let dot = nv.reduce((s, vi, i) => s + vi * excl[i], 0);
        for (let i = 0; i < d; i++) nv[i] -= dot * excl[i];
      }
      nrm = Math.sqrt(nv.reduce((s, vi) => s + vi * vi, 0));
      if (nrm < 1e-10) break;
      const prev = v.slice();
      v = nv.map((vi) => vi / nrm);
      if (v.reduce((s, vi, i) => s + (vi - prev[i]) ** 2, 0) < 1e-12) break;
    }
    return v;
  }

  const pc1 = powerIter(X, null),
    pc2 = powerIter(X, pc1);
  return X.map((x) => [
    x.reduce((s, v, i) => s + v * pc1[i], 0),
    x.reduce((s, v, i) => s + v * pc2[i], 0),
  ]);
}

// ════════════════════════════════════════════════════════════
//  SCATTER PLOT — CANVAS
// ════════════════════════════════════════════════════════════
const canvas = document.getElementById("graph");
const ctx = canvas.getContext("2d");
let bounds = { minX: -1, maxX: 1, minY: -1, maxY: 1 };

function resizeCanvas() {
  const r = canvas.parentElement.getBoundingClientRect();
  canvas.width = r.width;
  canvas.height = r.height;
}
window.addEventListener("resize", resizeCanvas);
resizeCanvas();

function w2c(wx, wy) {
  const P = 60,
    W = canvas.width,
    H = canvas.height;
  const rx = bounds.maxX - bounds.minX || 1;
  const ry = bounds.maxY - bounds.minY || 1;
  return [
    P + ((wx - bounds.minX) / rx) * (W - 2 * P),
    H - P - ((wy - bounds.minY) / ry) * (H - 2 * P),
  ];
}

// Category colors
const COL = {
  cs: "#00d9ff",
  math: "#b388ff",
  food: "#ffb74d",
  sports: "#69f0ae",
  doc: "#a6e3a1",
  pdf: "#a6e3a1",
  default: "#90a4ae",
};

function drawFrame() {
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  ctx.fillStyle = "#07070f";
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  // Grid
  ctx.strokeStyle = "#0e0e1e";
  ctx.lineWidth = 1;
  for (let i = 0; i <= 8; i++) {
    const tx = 60 + (i / 8) * (canvas.width - 120);
    const ty = 60 + (i / 8) * (canvas.height - 120);
    ctx.beginPath();
    ctx.moveTo(tx, 60);
    ctx.lineTo(tx, canvas.height - 60);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(60, ty);
    ctx.lineTo(canvas.width - 60, ty);
    ctx.stroke();
  }

  // Axis labels
  ctx.fillStyle = "#1a1a38";
  ctx.font = "11px monospace";
  ctx.fillText("PC₁ →", canvas.width / 2 - 30, canvas.height - 15);
  ctx.save();
  ctx.translate(16, canvas.height / 2 + 40);
  ctx.rotate(-Math.PI / 2);
  ctx.fillText("PC₂ →", 0, 0);
  ctx.restore();

  // Title
  ctx.fillStyle = "#151530";
  ctx.font = "12px monospace";
  ctx.fillText("2D PCA Projection  ·  Semantic Vector Space", 70, 24);

  // Lines from query to hits
  if (queryPt && hitIds.size > 0) {
    const [qx, qy] = w2c(queryPt.x, queryPt.y);
    for (const pt of pcaPoints) {
      if (!hitIds.has(pt.item.id)) continue;
      const [px, py] = w2c(pt.x, pt.y);
      ctx.strokeStyle = "rgba(108,99,255,0.2)";
      ctx.lineWidth = 1;
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(qx, qy);
      ctx.lineTo(px, py);
      ctx.stroke();
      ctx.setLineDash([]);
    }
  }

  // Dots
  for (const pt of pcaPoints) {
    const [cx, cy] = w2c(pt.x, pt.y);
    const col = COL[pt.item.category] || COL.default;
    const isHit = hitIds.has(pt.item.id);
    const r = isHit ? 10 : 7;

    // Pulse ring for hits
    if (isHit) {
      const pr = r + 7 + Math.sin(pulse) * 3.5;
      ctx.beginPath();
      ctx.arc(cx, cy, pr, 0, 2 * Math.PI);
      ctx.strokeStyle = col + "55";
      ctx.lineWidth = 1.5;
      ctx.stroke();
    }

    // Glow
    const grd = ctx.createRadialGradient(cx, cy, 0, cx, cy, r * 3);
    grd.addColorStop(0, col + (isHit ? "bb" : "88"));
    grd.addColorStop(1, "transparent");
    ctx.beginPath();
    ctx.arc(cx, cy, r * 3, 0, 2 * Math.PI);
    ctx.fillStyle = grd;
    ctx.fill();

    // Core dot
    ctx.beginPath();
    ctx.arc(cx, cy, r, 0, 2 * Math.PI);
    ctx.fillStyle = col;
    ctx.fill();

    // Hover ring
    if (hoverItem && hoverItem.id === pt.item.id) {
      ctx.beginPath();
      ctx.arc(cx, cy, r + 5, 0, 2 * Math.PI);
      ctx.strokeStyle = col;
      ctx.lineWidth = 1.5;
      ctx.stroke();
    }
  }

  // Query star
  if (queryPt) {
    const [qx, qy] = w2c(queryPt.x, queryPt.y);
    ctx.save();
    ctx.translate(qx, qy);
    ctx.shadowColor = "#fff";
    ctx.shadowBlur = 18;
    ctx.beginPath();
    for (let i = 0; i < 10; i++) {
      const a = (i * Math.PI) / 5 - Math.PI / 2;
      const rr = i % 2 === 0 ? 13 : 5;
      if (i === 0) ctx.moveTo(Math.cos(a) * rr, Math.sin(a) * rr);
      else ctx.lineTo(Math.cos(a) * rr, Math.sin(a) * rr);
    }
    ctx.closePath();
    ctx.fillStyle = "#fff";
    ctx.fill();
    ctx.shadowBlur = 0;
    ctx.restore();
    ctx.fillStyle = "#aaaacc";
    ctx.font = "10px monospace";
    ctx.fillText("query", qx + 16, qy + 4);
  }

  // Empty state
  if (!pcaPoints.length) {
    ctx.fillStyle = "#1a1a38";
    ctx.font = "13px monospace";
    ctx.textAlign = "center";
    ctx.fillText(
      "Ask a question or search to visualize vectors…",
      canvas.width / 2,
      canvas.height / 2,
    );
    ctx.textAlign = "left";
  }

  pulse += 0.05;
  requestAnimationFrame(drawFrame);
}

// Hover tooltip
canvas.addEventListener("mousemove", (e) => {
  const rect = canvas.getBoundingClientRect();
  const mx = e.clientX - rect.left,
    my = e.clientY - rect.top;
  hoverItem = null;
  let best = 18;
  for (const pt of pcaPoints) {
    const [cx, cy] = w2c(pt.x, pt.y);
    const d = Math.hypot(mx - cx, my - cy);
    if (d < best) {
      best = d;
      hoverItem = pt.item;
    }
  }
});
canvas.addEventListener("mouseleave", () => {
  hoverItem = null;
});

// Start animation loop
drawFrame();

// ════════════════════════════════════════════════════════════
//  LOAD ALL ITEMS FROM /items
// ════════════════════════════════════════════════════════════
async function loadItems() {
  try {
    const r = await fetch(API + "/items");
    allItems = await r.json();

    if (allItems.length >= 2) {
      const coords = pca2D(allItems.map((v) => v.embedding));
      pcaPoints = allItems.map((item, i) => ({
        x: coords[i][0],
        y: coords[i][1],
        item,
      }));

      // Update bounds
      let x0 = Infinity,
        x1 = -Infinity,
        y0 = Infinity,
        y1 = -Infinity;
      for (const p of pcaPoints) {
        x0 = Math.min(x0, p.x);
        x1 = Math.max(x1, p.x);
        y0 = Math.min(y0, p.y);
        y1 = Math.max(y1, p.y);
      }
      const px = (x1 - x0) * 0.2 || 0.1,
        py = (y1 - y0) * 0.2 || 0.1;
      bounds = { minX: x0 - px, maxX: x1 + px, minY: y0 - py, maxY: y1 + py };
    }

    console.log("Loaded", allItems.length, "items");
  } catch (e) {
    console.log("loadItems failed:", e);
  }
}

// ════════════════════════════════════════════════════════════
//  UPDATE GRAPH after query
// ════════════════════════════════════════════════════════════
async function updateGraph(question) {
  try {
    // Get search results to find hit indices + query vector
    const searchRes = await fetch(API + "/search", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ query: question, algo: selectedAlgo }),
    });
    const searchData = await searchRes.json();

    // Reload all items (may have changed)
    await loadItems();

    if (allItems.length < 2) return;

    // Find which allItems indices are hits
    hitIds = new Set();
    if (searchData.results) {
      searchData.results.forEach((result) => {
        const idx = allItems.findIndex(
          (item) =>
            item.metadata &&
            result.text &&
            (item.metadata.substring(0, 40) === result.text.substring(0, 40) ||
              result.text.includes(item.metadata.substring(0, 30))),
        );
        if (idx !== -1) hitIds.add(allItems[idx].id);
      });
    }

    // Query point — weighted average of hit positions
    if (hitIds.size > 0) {
      let sx = 0,
        sy = 0,
        sw = 0;
      pcaPoints.forEach((pt, i) => {
        if (hitIds.has(pt.item.id)) {
          const w = 1 / (i + 1);
          sx += pt.x * w;
          sy += pt.y * w;
          sw += w;
        }
      });
      if (sw > 0) {
        queryPt = {
          x: sx / sw + (Math.random() - 0.5) * 0.02,
          y: sy / sw + (Math.random() - 0.5) * 0.02,
        };
      }
    }

    console.log("Graph updated — hits:", hitIds.size, "queryPt:", queryPt);
  } catch (e) {
    console.log("updateGraph failed:", e);
  }
}

// ════════════════════════════════════════════════════════════
//  ASK AI — streaming SSE
// ════════════════════════════════════════════════════════════
document.getElementById("question").addEventListener("keydown", function (e) {
  if (e.key === "Enter" && !e.shiftKey) {
    e.preventDefault();
    askAI();
  }
});

async function askAI() {
  const q = document.getElementById("question").value.trim();
  if (!q) return;

  document.querySelector(".chatq").innerHTML = q;
  document.getElementById("answer").innerHTML = "thinking... ⏳";
  document.getElementById("algoInfo").innerHTML =
    "🔍 Using: <b>" +
    selectedAlgo.toUpperCase() +
    "</b> | ⏳ Retrieving context...";

  const startTime = Date.now();

  // Update graph in background (non-blocking)
  updateGraph(q);

  // SSE streaming call
  const res = await fetch(API + "/ask", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question: q, algo: selectedAlgo }),
  });

  document.getElementById("answer").innerHTML = "";

  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;

    buffer += decoder.decode(value, { stream: true });
    const lines = buffer.split("\n");
    buffer = lines.pop();

    for (const line of lines) {
      if (line.startsWith("data: ")) {
        const token = line.replace("data: ", "");
        if (token === "[DONE]") break;
        document.getElementById("answer").innerHTML += token;
      }
    }
  }

  const timeTaken = Date.now() - startTime;
  document.getElementById("algoInfo").innerHTML =
    "✅ Algo: <b>" +
    selectedAlgo.toUpperCase() +
    "</b> | ⚡ Time: <b>" +
    timeTaken +
    "ms</b>";

  document.getElementById("question").value = "";
}

// ════════════════════════════════════════════════════════════
//  SEARCH — latency + graph update
// ════════════════════════════════════════════════════════════
document.getElementById("searchInput") &&
  document.getElementById("searchInput").addEventListener("keydown", (e) => {
    if (e.key === "Enter") runSearch();
  });

async function runSearch() {
  const q = document.getElementById("searchInput").value.trim();
  if (!q) return;

  document.getElementById("latencyDisplay").innerHTML = "⏳ Searching...";
  document.getElementById("searchResults").innerHTML = "Loading...";
  document.getElementById("algoDisplay").innerHTML =
    selectedAlgo.toUpperCase() + " · searching...";

  const startTime = Date.now();

  try {
    const res = await fetch(API + "/search", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ query: q, algo: selectedAlgo }),
    });

    const data = await res.json();
    const clientTime = Date.now() - startTime;

    // Latency display
    document.getElementById("latencyDisplay").innerHTML = data.latency + "ms";
    document.getElementById("algoDisplay").innerHTML =
      selectedAlgo.toUpperCase() +
      " · cosine · k=5 · client: " +
      clientTime +
      "ms";

    // Results display
    let html = "";
    (data.results || []).forEach((r) => {
      html += `<div class="result" style="margin-bottom:8px;padding:8px;border-radius:6px;border:1px solid var(--border)">
        <span style="color:var(--cyan)">#${r.rank}</span>
        <span style="color:var(--muted);font-size:10px">[${r.category}]</span><br/>
        <span style="font-size:12px">${r.text}</span>
      </div>`;
    });
    document.getElementById("searchResults").innerHTML = html || "No results";

    // Update graph
    await loadItems();
    if (allItems.length >= 2) {
      hitIds = new Set();

      // Match results to allItems
      (data.results || []).forEach((result) => {
        const idx = allItems.findIndex(
          (item) =>
            item.metadata &&
            result.text &&
            (item.metadata.substring(0, 40) === result.text.substring(0, 40) ||
              result.text.includes(item.metadata.substring(0, 30))),
        );
        if (idx !== -1) hitIds.add(allItems[idx].id);
      });

      // Query point from weighted hits
      if (hitIds.size > 0) {
        let sx = 0,
          sy = 0,
          sw = 0;
        pcaPoints.forEach((pt, i) => {
          if (hitIds.has(pt.item.id)) {
            const w = 1 / (i + 1);
            sx += pt.x * w;
            sy += pt.y * w;
            sw += w;
          }
        });
        if (sw > 0) {
          queryPt = {
            x: sx / sw + (Math.random() - 0.5) * 0.02,
            y: sy / sw + (Math.random() - 0.5) * 0.02,
          };
        }
      }
    }
  } catch (e) {
    document.getElementById("latencyDisplay").innerHTML = "Error";
    document.getElementById("searchResults").innerHTML =
      "Server error: " + e.message;
  }
}

// ════════════════════════════════════════════════════════════
//  INSERT TEXT DOCUMENT
// ════════════════════════════════════════════════════════════
async function insertDoc() {
  const doc = document.getElementById("doc").value.trim();
  if (!doc) {
    alert("Kuch text likho!");
    return;
  }

  try {
    const res = await fetch(API + "/insertDoc", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ doc }),
    });
    const data = await res.json();
    console.log(data);
    alert("Document inserted!");
    await loadItems(); // graph refresh
  } catch (e) {
    alert("Error: " + e.message);
  }
}

// ════════════════════════════════════════════════════════════
//  UPLOAD PDF
// ════════════════════════════════════════════════════════════
async function uploadPDF() {
  const file = document.getElementById("pdfFile").files[0];
  if (!file) {
    alert("Pehle PDF select karo!");
    return;
  }

  document.getElementById("pdfStatus").innerHTML = "Uploading... ⏳";

  try {
    const buffer = await file.arrayBuffer();
    const res = await fetch(API + "/insertPDF", {
      method: "POST",
      headers: { "Content-Type": "application/pdf" },
      body: buffer,
    });
    const data = await res.json();

    if (data.status === "ok") {
      document.getElementById("pdfStatus").innerHTML =
        "✅ " + data.chunks + " chunks inserted!";
      await loadItems(); // graph refresh
    } else {
      document.getElementById("pdfStatus").innerHTML =
        "❌ Error: " + data.message;
    }
  } catch (e) {
    document.getElementById("pdfStatus").innerHTML = "❌ Error: " + e.message;
  }
}

// ════════════════════════════════════════════════════════════
//  BOOT — load items on start
// ════════════════════════════════════════════════════════════
loadItems();
