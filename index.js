function setAlgo(el) {
  document
    .querySelectorAll(".algo")
    .forEach((a) => a.classList.remove("active"));
  el.classList.add("active");
}

///  this is methods used in ask AI
document.getElementById("question").addEventListener("keydown", function (e) {
  if (e.key === "Enter") askAI();
});
async function askAI() {
  console.log("Button clicked");
  let q = document.getElementById("question").value;
  if (!q.trim()) return;

  document.querySelector(".chatq").innerHTML = q;
  document.getElementById("answer").innerHTML = "thinking... ⏳";

  // ✅ Active algo read karo
  let algo = "bruteforce";
  document.querySelectorAll(".algo").forEach((el) => {
    if (el.classList.contains("active")) {
      let text = el.innerText.toLowerCase().trim();
      if (text === "hnsw") algo = "hnsw";
      else if (text === "kd") algo = "kdtree";
      else algo = "bruteforce";
    }
  });
  console.log("Selected algo:", algo);

  document.getElementById("algoInfo").innerHTML =
    "🔍 Using: <b>" + algo.toUpperCase() + "</b> | ⏳ Searching...";

  let startTime = Date.now();

  // ✅ SSE se stream receive karo
  const res = await fetch("http://localhost:8080/ask", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ question: q, algo: algo }), // algo bhejo
  });
  // ui answer clean
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

    for (let line of lines) {
      if (line.startsWith("data: ")) {
        let token = line.replace("data: ", "");
        if (token === "[DONE]") break;

        // ✅ Word by word append karo
        document.getElementById("answer").innerHTML += token;
      }
    }
  }
  let timeTaken = Date.now() - startTime;
  document.getElementById("algoInfo").innerHTML =
    "✅ Algo: <b>" +
    algo.toUpperCase() +
    "</b> | ⚡ Time: <b>" +
    timeTaken +
    "ms</b>";

  document.getElementById("question").value = "";
}

// document insert methods
async function insertDoc() {
  let doc = document.getElementById("doc").value;
  console.log(doc);

  let res = await fetch("http://localhost:8080/insertDoc", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ doc: doc }),
  });

  let data = await res.json();

  console.log(data);

  alert("Document inserted!");
}

// this method or function basicaly use for PDF Upload
async function uploadPDF() {
  let file = document.getElementById("pdfFile").files[0];
  if (!file) {
    alert("Pehle PDF select karo!");
    return;
  }

  document.getElementById("pdfStatus").innerHTML = "Uploading... ⏳";

  let formData = file.arrayBuffer().then(async (buffer) => {
    let res = await fetch("http://localhost:8080/insertPDF", {
      method: "POST",
      headers: {
        "Content-Type": "application/pdf",
      },
      body: buffer,
    });

    let data = await res.json();

    if (data.status === "ok") {
      document.getElementById("pdfStatus").innerHTML =
        "✅ " + data.chunks + " chunks inserted!";
    } else {
      document.getElementById("pdfStatus").innerHTML =
        "❌ Error: " + data.message;
    }
  });
}

/// this hethods call basically use to for Search Latency... ...
async function runSearch() {
  let q = document.getElementById("searchInput").value;
  if (!q.trim()) return;

  // ✅ Get active algo (same as askAI)
  let algo = "bruteforce";
  document.querySelectorAll(".algo").forEach((el) => {
    if (el.classList.contains("active")) {
      let text = el.innerText.toLowerCase().trim();
      if (text === "hnsw") algo = "hnsw";
      else if (text === "kd") algo = "kdtree";
      else algo = "bruteforce";
    }
  });

  document.getElementById("latencyDisplay").innerHTML = "⏳ Searching...";
  document.getElementById("searchResults").innerHTML = "Loading...";

  let startTime = Date.now();

  try {
    let res = await fetch("http://localhost:8080/search", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ query: q, algo: algo }),
    });
    console.log("Search response received");

    let data = await res.json();
    let clientTime = Date.now() - startTime;
    console.log("Search results:", data);

    // Latency dikhao
    document.getElementById("latencyDisplay").innerHTML = data.latency + "ms";
    document.getElementById("algoDisplay").innerHTML =
      algo.toUpperCase() + " · cosine · k=5 · client: " + clientTime + "ms";

    //  Results dikhao
    let html = "";
    data.results.forEach((r) => {
      html += `
                <div class="result" style="
                    margin-bottom: 8px;
                    padding: 8px;
                    border-radius: 6px;
                    border: 1px solid var(--border);
                ">
                    <span style="color:var(--cyan)">#${r.rank}</span> 
                    <span style="color:var(--muted);font-size:10px">[${r.category}]</span>
                    <br/>
                    <span style="font-size:12px">${r.text}</span>
                </div>
            `;
    });

    document.getElementById("searchResults").innerHTML = html;
  } catch (e) {
    document.getElementById("latencyDisplay").innerHTML = "Error";
    document.getElementById("searchResults").innerHTML =
      "Server error: " + e.message;
  }
}

// ✅ Enter key se bhi search ho
document.getElementById("searchInput") &&
  document
    .getElementById("searchInput")
    .addEventListener("keydown", function (e) {
      if (e.key === "Enter") runSearch();
    });

function showTab(i) {
  document
    .querySelectorAll(".tab")
    .forEach((t, n) => t.classList.toggle("active", n === i));
  document
    .querySelectorAll(".tabpane")
    .forEach((p, n) => p.classList.toggle("active", n === i));
}
const c = document.getElementById("graph");
const ctx = c.getContext("2d");
function resize() {
  c.width = c.clientWidth;
  c.height = c.clientHeight;
  draw();
}

function draw() {
  ctx.clearRect(0, 0, c.width, c.height);
  for (let i = 0; i < 12; i++) {
    ctx.strokeStyle = "#1d1d35";
    ctx.beginPath();
    ctx.moveTo((i * c.width) / 12, 0);
    ctx.lineTo((i * c.width) / 12, c.height);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(0, (i * c.height) / 12);
    ctx.lineTo(c.width, (i * c.height) / 12);
    ctx.stroke();
  }
  ctx.fillStyle = "#2f2f50";
  ctx.font = "18px monospace";
  ctx.fillText("Semantic Vector Space (PCA)", 40, 40);
}
resize();
window.onresize = resize;
