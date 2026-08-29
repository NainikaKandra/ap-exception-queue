# Problem 9 — Transaction Exception Queue (Java Prototype)

[![Live Demo](https://img.shields.io/badge/Live%20Demo-railway.app-6B47F5?style=for-the-badge&logo=railway&logoColor=white)](https://ap-exception-queue.up.railway.app)
[![GitHub](https://img.shields.io/badge/GitHub-NainikaKandra%2Fap--exception--queue-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/NainikaKandra/ap-exception-queue)

> 🔗 **Live app:** [https://ap-exception-queue.up.railway.app](https://ap-exception-queue.up.railway.app)

Synthetic transactions are checked against deterministic rules, flagged
exceptions get an AI-generated explanation and suggested resolution, and
a confidence score decides whether a transaction can be auto-resolved,
needs human approval, or goes to manual review.

## Why this looks different from a Python/Streamlit version

Java has no Streamlit equivalent, so instead of a Python process
rendering a UI, this is a **small local web app**: a plain Java backend
serves a JSON API, and a single static HTML/JS page (no build step, no
npm) renders the dashboard and calls that API. The whole thing compiles
with just `javac` — no Maven, no Gradle, no external libraries — because:

- JSON parsing/writing is a ~150-line hand-written class (`Json.java`).
  Real projects would use Jackson/Gson; this prototype avoids that
  dependency entirely so it builds anywhere a JDK is installed.
- The HTTP server is `com.sun.net.httpserver.HttpServer`, which ships
  with the JDK (used instead of Spring/Javalin/Spark for the same reason).
- AI calls use `java.net.http.HttpClient`, also JDK-built-in.

This keeps the "you must be able to explain your code later" ground rule
easy to honor — there's no framework magic to gloss over.

## Pipeline

```
data/transactions.csv
       |
Rules.java (deterministic exception rules)
       |
Flagged transaction queue
       |
Server.java (JSON API) + resources/index.html (dashboard UI)
       |
AiExplainer.java (explanation + suggested resolution)
       |
Confidence threshold
       |
Auto-Resolve / Human Approval / Manual Review
       |
Updated transaction status (in-app only)
```

## Assumptions

1. Transaction data is synthetic and stored locally in CSV format
   (`data/transactions.csv`, produced by `DataGenerator.java`). No real
   financial data is used anywhere.
2. Exceptions are detected using deterministic rules: duplicate invoices,
   amount mismatch (invoice vs. PO), tax mismatch (recorded vs. expected
   tax), and missing purchase-order references.
3. The AI model (`AiExplainer.java`) is used only to generate a plain-
   English explanation and a suggested resolution. It does not compute
   the confidence score and is not the financial decision-maker — that
   role stays with the deterministic rules and, where needed, a human.
4. Transactions with confidence >= 90% are eligible for automatic
   resolution.
5. Transactions with confidence between 70% and 89% require a human to
   click Approve or Reject in the dashboard.
6. Transactions below 70% confidence are routed to manual review only —
   no one-click resolution is offered for these.
7. "Auto-resolution" in this prototype means updating the transaction's
   status in this app's in-memory state (and, on request, the CSV). It
   does not call, write to, or otherwise trigger any real financial
   system, ledger, or payment rail.

## How confidence is computed

Confidence comes entirely from `Rules.java`, not from the AI model. Each
triggered rule carries its own confidence score reflecting how safe that
specific pattern is to resolve automatically:

| Rule | Confidence |
|---|---|
| Duplicate invoice | 96% |
| Amount mismatch ≤ 5% | 92% |
| Tax mismatch ≤ 10% | 90% |
| Amount mismatch ≤ 15% | 78% |
| Tax mismatch > 10% | 70% |
| Missing PO reference | 60% |
| Amount mismatch > 15% | 55% |

If a transaction triggers multiple rules, the overall confidence is the
**minimum** across triggered rules — the weakest signal caps the score.
The server also re-checks the tier before applying any resolve action,
so the UI can't force an action the rules don't allow.

## AI provider

`AiExplainer.java` checks for an API key in this order:

1. `ANTHROPIC_API_KEY` env var → calls Claude (`claude-3-5-haiku-latest`)
2. `OPENAI_API_KEY` env var → calls OpenAI (`gpt-4o-mini`)
3. Neither set → a local, template-based explanation (no network call,
   no cost)

The app is **fully demoable with zero API keys**. If you want live AI-generated
text, export one of the above environment variables before running.

## Setup & run

Requires a JDK (21 used here; anything 17+ should work since only
records and switch expressions are used beyond basic Java).

```bash
# from the project root (problem9-java/)

# 1. compile
mkdir -p out
javac -d out $(find src -name "*.java")

# 2. (optional) generate a fresh batch of synthetic data — a sample is
#    already checked in at data/transactions.csv, and the server will
#    generate one automatically if it's missing
java -cp out com.example.problem9.DataGenerator

# 3. (optional) enable live AI explanations
export ANTHROPIC_API_KEY=sk-ant-...
# or
export OPENAI_API_KEY=sk-...

# 4. run the server (must be run from the project root so it can find
#    data/ and resources/)
java -cp out com.example.problem9.Server 8080
```

Then open **http://localhost:8080** in a browser.

## API endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/` | Dashboard (`resources/index.html`) |
| GET | `/api/transactions` | JSON list of all transactions with rule results + status |
| POST | `/api/explain?id=T0007` | Runs the AI explainer for one transaction, caches the result |
| POST | `/api/resolve?id=T0007&action=auto\|approve\|reject\|manual` | Applies a workflow action (server validates it against the confidence tier) |

## File overview

| File | Purpose |
|---|---|
| `src/.../Transaction.java` | Data model for one transaction + rule/AI/status fields |
| `src/.../CsvUtil.java` | Minimal CSV read/write |
| `src/.../DataGenerator.java` | Creates synthetic `data/transactions.csv` |
| `src/.../Rules.java` | Deterministic exception detection + confidence scoring |
| `src/.../Json.java` | Hand-written JSON parser/writer (no external dependency) |
| `src/.../AiExplainer.java` | Explanation + suggested resolution (Claude / OpenAI / local fallback) |
| `src/.../TransactionStore.java` | In-memory queue + tier-validated resolve actions |
| `src/.../Server.java` | JDK-only HTTP server + JSON API |
| `resources/index.html` | Dashboard UI (vanilla HTML/CSS/JS, no build step) |
| `data/transactions.csv` | Sample synthetic dataset |

## Exception types in the sample data

| Rows | Exception type | Notes |
|---|---|---|
| T0001–T0006 | None (clean) | Baseline, no rules trigger |
| T0007, T0008 | Duplicate invoice | Same vendor + invoice + amount twice |
| T0009 | Amount mismatch (small, ~3%) | Confidence 92% → Auto-Resolve |
| T0010 | Amount mismatch (medium, ~12%) | Confidence 78% → Human Approval |
| T0011 | Amount mismatch (large, ~25%) | Confidence 55% → Manual Review |
| T0012 | Tax mismatch (50% of expected) | Confidence 70% → Human Approval |
| T0013 | Tax mismatch (50% over expected) | Confidence 70% → Human Approval |
| T0014 | Tax mismatch (100% over expected) | Confidence 70% → Human Approval |
| T0015–T0017 | Missing PO reference | Confidence 60% → Manual Review |
| T0018 | Missing PO + amount mismatch | Combined; confidence min(60,55) = 55% → Manual Review |

## Known limitations (by design, for this prototype)

- **In-memory state only** — resolution status resets when the server
  restarts (no database); the CSV on disk is not rewritten automatically
  after a resolve action.
- **Fixed tolerances** — amount/tax mismatch thresholds are simple fixed
  percentages (1% for amount, 2% for tax), not vendor- or category-specific.
- **No authentication/roles** — anyone hitting the API can click Approve.
- **Confidence tiers are illustrative** — thresholds chosen for a clear
  demo, not derived from historical data.
- **Single-user/single-process** — the `synchronized` in
  `TransactionStore` is enough for a local demo, not a concurrent
  multi-user backend.
- **No CSV rewrite on resolve** — status changes live in the JVM only;
  a server restart reloads original CSV and resets all statuses.
