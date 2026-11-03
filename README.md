# Your Own AI

Java-based semantic search and document Q&A using Ollama embeddings and PDFBox.

## Requirements

- Java 17 or newer
- Maven 3.9 or newer
- [Ollama](https://ollama.com/) with the models used by this project

## Run locally

1. Start Ollama and download the required models:

   ```text
   ollama pull nomic-embed-text
   ollama pull tinyllama
   ```

2. From the project root, run:

   ```text
   mvn compile exec:java
   ```

   On Windows, `run.bat` runs the same command.

The API listens on `http://localhost:8080`. Vector data is stored in
`vector_data.json`; API JSON requests must be JSON objects (for example,
`{"query":"semantic search","algo":"bruteforce"}`).

## API endpoints

- `POST /search` — semantic search; accepts `query` and optional `algo`
  (`bruteforce`, `kdtree-exact`, or `hnsw-experimental`).
- `POST /benchmark` — compare exact and approximate search strategies using one embedded
  query; accepts `query` and optional `k` (1–100) and `repetitions` (1–20).
- `POST /ask` — streamed answer using a retrieved context.
- `POST /insertDoc` — embed and save a text document.
- `POST /insertPDF` — upload a PDF as the raw request body.
- `GET /items` — list stored vectors.
- `POST /doc/search` and `POST /doc/ask` — document retrieval and Q&A.

## Search algorithms and benchmark

- **Brute force** scores every stored vector and returns exact cosine-similarity
  top-k results. It is the reference implementation.
- **KD-tree exact** indexes normalized vectors and uses exact branch-and-bound
  search; it returns the same cosine-similarity ranking as brute force. Its
  index is rebuilt for each request, and high-dimensional embeddings can make
  tree pruning ineffective.
- **HNSW experimental** builds a layered, navigable small-world graph with
  randomized node levels, greedy upper-layer traversal, and bounded
  `efConstruction`/`efSearch` candidate queues. It is an approximate nearest
  neighbor implementation for learning and comparison, not a production-ready
  vector database. Its graph is rebuilt per request, so build costs are included
  in search timings. Parameters are `M=8`, `efConstruction=64`, and
  `efSearch=max(32, 4*k)` (capped by dataset size). The level generator uses a
  fixed seed to make local comparisons repeatable.

The benchmark warms each algorithm, then reports average and minimum time over
the requested repetitions, plus recall@k against the brute-force reference.
Search timings include KD-tree/HNSW index construction and exclude query
embedding time; embedding latency and HNSW parameters are reported separately.
Results depend on the local dataset and machine, so compare them on
representative data rather than treating a small sample as a general performance
claim.