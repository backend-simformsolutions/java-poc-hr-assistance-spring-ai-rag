# HR Policy RAG Assistant — Spring AI + Ollama + PGVector

A complete Retrieval-Augmented Generation (RAG) backend that lets you upload HR policy PDFs and ask questions about them using natural language.

## Architecture

```
                    ┌─────────────┐
                    │  PDF Upload  │
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │ Extract Text │ (PDFBox)
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │  Chunk Text  │ (Recursive character splitting)
                    └──────┬──────┘
                           │
                    ┌──────▼──────────┐
                    │ Generate Embeddings │ (Ollama / nomic-embed-text)
                    └──────┬──────────┘
                           │
                    ┌──────▼──────┐
                    │ Store in DB  │ (PostgreSQL + pgvector)
                    └──────┬──────┘
                           │
           ════════════════════════════════
                           │
                    ┌──────▼──────┐
                    │ User Question│
                    └──────┬──────┘
                           │
                    ┌──────▼──────────┐
                    │ Embed Question   │ (nomic-embed-text)
                    └──────┬──────────┘
                           │
                    ┌──────▼──────────┐
                    │ Similarity Search │ (pgvector cosine)
                    └──────┬──────────┘
                           │
                    ┌──────▼──────┐
                    │ Build Prompt │ (Context + Question)
                    └──────┬──────┘
                           │
                    ┌──────▼──────┐
                    │   Ollama LLM │ (llama3)
                    └──────┬──────┘
                           │
                    ┌──────▼──────────┐
                    │ Grounded Answer  │
                    └─────────────────┘
```

## Prerequisites

### 1. Java 21 and Maven
```bash
java -version    # 21 or later
mvn -version     # 3.9 or later
```

### 2. PostgreSQL with pgvector

The repository ships a `docker-compose.yml` that runs PostgreSQL 17 with pgvector
pre-installed, published on **port 5433**:

```bash
docker compose up -d
```

This creates the `hrbot` database on first start; `schema.sql` enables the `vector` and
`uuid-ossp` extensions on application startup. Verify the container is accepting
connections:

```bash
docker exec -it hrbot-postgres psql -U postgres -d hrbot -c "SELECT version();"
```

> **Using your own PostgreSQL instead?** It must have the pgvector extension available
> — install it from [pgvector releases](https://github.com/pgvector/pgvector/releases)
> (Windows), `brew install pgvector` (macOS), or your package manager (Linux). Then
> create the database and point the application at it with `DB_PORT` (see
> [Configuration Reference](#configuration-reference)).

### 3. Ollama (Local)
1. Ensure Ollama is running:
```bash
ollama --version
```

2. Pull the required models:
```bash
# LLM for chat (8B parameters, ~4.7GB)
ollama pull llama3

# Embedding model for vector generation (137M parameters, ~274MB)
ollama pull nomic-embed-text
```

3. Verify Ollama is running:
```bash
curl http://localhost:11434/api/tags
```

## Quick Start

### 1. Configure Database Credentials (optional)

The defaults match `docker-compose.yml`, so nothing is required to run locally. To point
elsewhere, export environment variables rather than editing `application.yml` — they are
read from the JVM's environment, so they must be exported, not just assigned:

```bash
export DB_HOST=db.internal DB_PORT=5432 DB_NAME=hrbot
export DB_USERNAME=postgres DB_PASSWORD=secret
mvn spring-boot:run
```

```powershell
$env:DB_HOST = "db.internal"; $env:DB_PORT = "5432"
mvn spring-boot:run
```

### 2. Build and Run
```bash
# Build the project
mvn clean package -DskipTests

# Run the application
mvn spring-boot:run
```

Or with the JAR:
```bash
java -jar target/hr-policy-rag-1.0.0.jar
```

### 3. Verify It's Running
```bash
curl http://localhost:8080/actuator/health
```
Expected response — `management.endpoint.health.show-details: always` is set, so
per-component detail is included:
```json
{
  "status": "UP",
  "components": {
    "db":        { "status": "UP", "details": { "database": "PostgreSQL" } },
    "diskSpace": { "status": "UP" },
    "ping":      { "status": "UP" }
  }
}
```

A `db` status of `DOWN` means the pgvector container on port 5433 is unreachable, and
every retrieval call will fail.

## Documents

`Hr Policy/sample-hr-policy.pdf` is a synthetic seven-page handbook covering leave and
time off, remote work, code of conduct, compensation and benefits, performance
management, grievances and exit. Upload it to have something to query:

```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@Hr Policy/sample-hr-policy.pdf"
```

To regenerate it, or after editing its content:

```bash
mvn -q test-compile
mvn -q exec:java -Dexec.classpathScope=test \
  -Dexec.mainClass=com.example.hrbot.GenerateSampleHrPolicyPdf
```

Real policy PDFs are not tracked — see the note at the end of `.gitignore`. Drop your
own into `Hr Policy/` and upload them the same way.

## API Usage

### Upload a PDF Document
```bash
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@/path/to/hr-policy.pdf"
```

**Response:**
```json
{
  "fileName": "hr-policy.pdf",
  "totalCharacters": 45230,
  "totalChunks": 52,
  "message": "Document successfully processed and stored in vector database.",
  "processingTimeMs": 34521
}
```

### Ask a Question
```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"question": "How many sick days do employees get per year?", "topK": 5}'
```

`topK` is optional; omit it to use `app.retrieval.default-top-k`.

**Response:**
```json
{
  "answer": "Employees are entitled to 10 paid sick days per calendar year.",
  "question": "How many sick days do employees get per year?",
  "chunksRetrieved": 5,
  "sources": ["hr-policy.pdf (pages 4, 5)"],
  "retrievedChunks": [
    {
      "content": "Section 4.2 - Sick Leave: All full-time employees are entitled to 10 paid sick days...",
      "similarityScore": 0.89,
      "documentName": "hr-policy.pdf",
      "chunkIndex": 12,
      "metadata": {
        "documentName": "hr-policy.pdf",
        "chunkIndex": 12,
        "totalChunks": 52,
        "contentLength": 964,
        "pageNumber": 4,
        "pageNumberEnd": 4,
        "distance": 0.11
      }
    }
  ],
  "processingTimeMs": 8234
}
```

**`sources` vs `retrievedChunks`.** `sources` is the user-facing citation. Page numbers
are used because they are properties of the source document: stable across re-ingestion
and verifiable by opening the PDF. `retrievedChunks` is diagnostic — `chunkIndex` is an
internal identifier renumbered from zero whenever a document is re-ingested, and
`metadata.pageNumber`/`pageNumberEnd` are the page range the citation was derived from.
Neither should be surfaced to end users.

### Ask a Question (Streaming)
Same request body as `/api/chat`, but the answer arrives token by token as
[Server-Sent Events](https://developer.mozilla.org/en-US/docs/Web/API/Server-sent_events/Using_server-sent_events).
`-N` stops curl buffering the output:

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -d '{"question": "How many annual leave days do I get, and how many can I carry forward?"}'
```

**Response** (`Content-Type: text/event-stream`, every `data` is JSON):
```
event:retrieval
data:{"requestId":"a1b2c3d4","question":"How many annual leave days ...","chunksRetrieved":5,"retrievedChunks":[...]}

event:token
data:{"text":"All full-time employees are entitled to"}

event:token
data:{"text":" 20 days of annual paid leave per calendar year."}

event:done
data:{"sources":["sample-hr-policy.pdf (page 1)"],"processingTimeMs":11850}
```

A stream is always `retrieval`, then `token` events, then exactly one of `done` or
`error`. Missing that final event means the connection dropped.
A `:keep-alive` comment is also written every second until the stream ends. SSE clients
ignore comments; they let the server notice a disconnect during silent stretches, such
as while sources are being decided.

- **Sources come last.** The answer is generated and streamed first. Once it is complete,
  under `model-attributed` a second, structured model call reports which of the
  retrieved sources the answer was taken from; every reported source is verified against
  those supplied, and the resulting pages are sent in `done`. Expect `done` a few seconds
  after the last token. Under `retrieval-scoped` that call is skipped and every retrieved
  page is cited.
- **Fallbacks.** Nothing verified, or the attribution call fails → `sources` lists every
  retrieved page. A refusal → `sources` is empty, and no attribution call is made.
- **Errors.** Validation and retrieval failures happen before streaming starts and
  return the usual JSON error with a 4xx/5xx status, whatever the `Accept` header. A
  failure during generation, or a stream that runs past `app.streaming.timeout` (150s),
  arrives as `event:error` with a `requestId` to quote; text already received is
  incomplete.
- **Disconnects.** If the client goes away mid-answer — during generation or during
  attribution — the request to Ollama is closed and it stops work, so an abandoned answer
  does not hold up the next question. This relies on `reactor-netty-http` being on the
  classpath; the JDK HTTP client does not close the connection on cancel.
- **Browsers.** `EventSource` supports only GET, so read this POST endpoint with
  `fetch()` and `response.body.getReader()`. Behind nginx, disable response buffering
  (`X-Accel-Buffering: no`) or the events arrive all at once.

### Inspect Retrieval Without Calling the Model
```bash
curl "http://localhost:8080/api/debug/search?q=sick%20leave&topK=5"
curl "http://localhost:8080/api/debug/search-raw?q=sick%20leave&threshold=0.3"
curl http://localhost:8080/api/debug/stats
```

`/search` returns the chunks found and the exact prompt that would be sent;
`/search-raw` runs the same query as hand-written SQL, so the two can be compared;
`/stats` reports what is stored and the settings in force. Secure or disable these
outside development — they expose stored content and internal metadata.

### Clear the Vector Store
```bash
curl -X DELETE http://localhost:8080/api/documents/all
```

Destructive, and required before re-ingesting: chunking settings are applied at
ingestion time, so changing `chunk-size` or `chunk-overlap` only takes effect for
documents uploaded afterwards. Re-uploading without clearing stores a second copy of
every chunk.

### Postman

`HR-Policy-RAG-Postman.postman_collection.json` covers all ten endpoints with
per-request notes, negative tests and assertions. Import it and set the `pdfPath`
variable to the PDF you want to ingest.

## PowerShell Curl Commands (Windows)

`-Form` requires PowerShell 7+. On Windows PowerShell 5.1, use the bundled `curl.exe`
for the upload — the other commands work on both.

```powershell
# Upload a PDF (PowerShell 7+)
Invoke-RestMethod -Uri "http://localhost:8080/api/documents/upload" -Method Post -Form @{ file = Get-Item "C:\path\to\hr-policy.pdf" }

# Upload a PDF (Windows PowerShell 5.1)
curl.exe -X POST http://localhost:8080/api/documents/upload -F "file=@C:\path\to\hr-policy.pdf"

# Ask a question
Invoke-RestMethod -Uri "http://localhost:8080/api/chat" -Method Post -ContentType "application/json" -Body '{"question": "What is the leave policy?", "topK": 5}'

# Ask a question, streamed (Invoke-RestMethod waits for the whole body, so use curl.exe)
curl.exe -N -X POST http://localhost:8080/api/chat/stream -H "Content-Type: application/json" -d '{\"question\": \"What is the leave policy?\"}'

# Debug search
Invoke-RestMethod -Uri "http://localhost:8080/api/debug/search?q=leave%20policy&topK=5"

# Stats
Invoke-RestMethod -Uri "http://localhost:8080/api/debug/stats"
```

## Project Structure

```
src/main/java/com/example/hrbot/
├── HrBotApplication.java          # Spring Boot entry point
├── config/
│   └── ChatClientConfig.java      # ChatClient bean configuration
├── controller/
│   ├── DocumentController.java    # PDF upload API
│   ├── ChatController.java        # RAG Q&A API
│   └── DebugController.java       # Debug & inspection APIs
├── service/
│   └── DocumentIngestionService.java  # Ingestion pipeline orchestrator
├── rag/
│   ├── RetrievalService.java      # Vector similarity search
│   └── ChatService.java           # Full RAG pipeline orchestrator
├── ingestion/
│   ├── PdfTextExtractor.java      # PDF → text (PDFBox), recording page offsets
│   ├── TextChunkingService.java   # Text → overlapping chunks
│   └── TextNormaliser.java        # Shared whitespace rules, so offsets stay aligned
├── embeddings/
│   └── EmbeddingService.java      # Text → vector (Ollama nomic-embed-text)
├── repository/
│   └── DocumentChunkRepository.java # Raw SQL vector queries (debug)
├── prompts/
│   └── PromptBuilder.java         # RAG prompt construction
├── dto/
│   ├── ChatRequest.java           # Chat API request
│   ├── ChatResponse.java          # Chat API response
│   ├── ChatStreamEvent.java       # Streaming chat API events
│   ├── SourceAttribution.java     # Structured reply used to attribute a streamed answer
│   ├── CitedAnswer.java           # Structured reply used for model-attributed citations
│   ├── RetrievedChunk.java        # Single chunk with score and page metadata
│   ├── UploadResponse.java        # Upload API response
│   ├── DebugSearchResponse.java   # Debug search response
│   └── ErrorResponse.java         # Standard error format
└── exception/
    ├── DocumentProcessingException.java  # Custom exception
    └── GlobalExceptionHandler.java       # Global error handling

src/main/resources/
├── application.yml                # All configuration
└── schema.sql                     # Enables the pgvector and uuid-ossp extensions

src/test/java/com/example/hrbot/
├── GenerateSampleHrPolicyPdf.java # Builds the synthetic demo handbook
├── dto/RetrievedChunkTest.java
├── ingestion/TextChunkingServiceTest.java
├── ingestion/TextNormaliserTest.java
├── prompts/PromptBuilderTest.java
└── rag/ChatServiceCitationTest.java

docker-compose.yml                 # PostgreSQL 17 + pgvector on port 5433
HR-Policy-RAG-Postman.postman_collection.json   # All 9 endpoints, with notes
```

## Configuration Reference

See `src/main/resources/application.yml` for all configurable properties including
database connection, Ollama models and parameters, chunking, retrieval and logging.

These are overridable by environment variable, so no file edit is needed to point the
application elsewhere:

| Variable | Default |
|---|---|
| `DB_PORT` | `5433` |
| `DB_NAME` | `hrbot` |
| `DB_USERNAME` | `postgres` |
| `DB_PASSWORD` | `postgres` |
| `OLLAMA_BASE_URL` | `http://localhost:11434` |

Four settings are worth calling out:

| Property | Purpose |
|---|---|
| `spring.ai.ollama.chat.options.num-ctx` | Context window. Must be set explicitly — Ollama applies its own default rather than the model's maximum. A prompt that together with `num-predict` exceeds it is truncated from the front, where the role and grounding rules sit, before the retrieved context that follows them. |
| `spring.ai.ollama.chat.options.seed` | Fixes the sampler so identical prompts yield identical answers. Keep it set while evaluating changes; without it, run-to-run variation makes comparisons unreliable. |
| `app.citations.mode` | `model-attributed` (the model reports which sources it used, and every label is validated against the set supplied) or `retrieval-scoped` (cite every page retrieved). Falls back to `retrieval-scoped` automatically if a structured reply cannot be parsed. |
| `app.retrieval.default-top-k` | Chunks placed in the prompt. More context is not automatically better — larger values slow inference and give smaller models more text to keep straight. |

## Testing

```bash
mvn test
```

56 unit tests covering chunk boundary alignment, offset integrity, overlap behaviour,
whitespace-normalisation idempotence, prompt-contract wording, citation metadata, and
citation validation — including invented labels, malformed labels and the fallback path.
They require no database and no model, so they run in CI unchanged.

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `Connection refused: localhost:11434` | Start Ollama: `ollama serve` |
| `model not found: llama3` | Pull the model: `ollama pull llama3` |
| `Connection refused: localhost:5433` | Start the database: `docker compose up -d` |
| `database "hrbot" does not exist` | Recreate the container volume: `docker compose down -v && docker compose up -d` |
| `extension "vector" not available` | Connected to a PostgreSQL without pgvector — check `DB_PORT` points at the container (5433), not a local instance |
| Answers cite no pages, or `chunksRetrieved` is 0 | Nothing ingested yet. Check `GET /api/debug/stats` and upload a document |
| Empty text from PDF | PDF might be scanned (image-based). Use OCR. |
| Low similarity scores | Try different chunking sizes or a different embedding model |

## Tech Stack

| Component | Technology | Purpose |
|-----------|-----------|---------|
| Runtime | Java 21 | Application runtime |
| Framework | Spring Boot 3.4.1 | Web framework + auto-configuration |
| AI Framework | Spring AI 1.0.0 | AI/ML abstractions |
| LLM | Ollama + llama3 | Text generation |
| Embeddings | Ollama + nomic-embed-text | Text → vector conversion |
| Vector DB | PostgreSQL + pgvector | Vector storage & similarity search |
| PDF Parsing | Apache PDFBox 3.0.3 | PDF text extraction |
| Build | Maven | Dependency management & build |

