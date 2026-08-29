package com.example.problem9;

import com.sun.net.httpserver.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Tiny local web dashboard, built entirely on the JDK's built-in
 * com.sun.net.httpserver.HttpServer -- no Spring/Javalin/Spark, no
 * external dependency, so `javac`+`java` is all you need.
 *
 * Routes:
 *   GET  /                     -> dashboard (resources/index.html)
 *   GET  /api/transactions     -> JSON list of all transactions + rule/status info
 *   POST /api/explain?id=T0001 -> runs the AI explainer for one transaction
 *   POST /api/resolve?id=T0001&action=auto|approve|reject|manual
 *
 * Run: java -cp out com.example.problem9.Server
 * Then open http://localhost:8080
 */
public class Server {

    private final TransactionStore store;
    private final Path indexHtmlPath;

    public Server(TransactionStore store, Path indexHtmlPath) {
        this.store = store;
        this.indexHtmlPath = indexHtmlPath;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/transactions", this::handleListTransactions);
        server.createContext("/api/explain", this::handleExplain);
        server.createContext("/api/resolve", this::handleResolve);
        server.setExecutor(null); // default executor is fine for a local demo
        server.start();
        System.out.println("Transaction Exception Queue running at http://localhost:" + port);
    }

    // -----------------------------------------------------------------
    // Handlers
    // -----------------------------------------------------------------

    private void handleIndex(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "text/plain", "Not found");
            return;
        }
        byte[] html = Files.readAllBytes(indexHtmlPath);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, html.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(html);
        }
    }

    private void handleListTransactions(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            send(ex, 405, "text/plain", "Method not allowed");
            return;
        }
        List<Object> json = new ArrayList<>();
        for (Transaction t : store.all()) {
            json.add(toJson(t));
        }
        send(ex, 200, "application/json", Json.write(json));
    }

    private void handleExplain(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            send(ex, 405, "text/plain", "Method not allowed");
            return;
        }
        Map<String, String> params = queryParams(ex.getRequestURI().getRawQuery());
        String id = params.get("id");
        try {
            Transaction t = store.explain(id);
            send(ex, 200, "application/json", Json.write(toJson(t)));
        } catch (NoSuchElementException e) {
            send(ex, 404, "application/json", Json.write(Map.of("error", e.getMessage())));
        } catch (Exception e) {
            send(ex, 500, "application/json", Json.write(Map.of("error", String.valueOf(e.getMessage()))));
        }
    }

    private void handleResolve(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("POST")) {
            send(ex, 405, "text/plain", "Method not allowed");
            return;
        }
        Map<String, String> params = queryParams(ex.getRequestURI().getRawQuery());
        String id = params.get("id");
        String action = params.get("action");
        try {
            Transaction t = store.applyAction(id, action);
            send(ex, 200, "application/json", Json.write(toJson(t)));
        } catch (NoSuchElementException e) {
            send(ex, 404, "application/json", Json.write(Map.of("error", e.getMessage())));
        } catch (IllegalStateException | IllegalArgumentException e) {
            send(ex, 400, "application/json", Json.write(Map.of("error", e.getMessage())));
        }
    }

    // -----------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------

    private Map<String, Object> toJson(Transaction t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("transaction_id", t.transactionId);
        m.put("vendor", t.vendor);
        m.put("po_number", t.poNumber);
        m.put("invoice_number", t.invoiceNumber);
        m.put("invoice_amount", t.invoiceAmount);
        m.put("po_amount", t.poAmount);
        m.put("tax_amount", t.taxAmount);
        m.put("expected_tax_amount", t.expectedTaxAmount);
        m.put("invoice_date", t.invoiceDate);
        m.put("is_exception", t.isException);
        m.put("triggered_rules", t.triggeredRulesJoined());
        m.put("reasons", t.reasonsJoined());
        m.put("confidence", t.confidence);
        m.put("tier", t.resolutionTier());
        m.put("resolution_status", t.resolutionStatus);
        m.put("ai_explanation", t.aiExplanation);
        m.put("ai_suggested_resolution", t.aiSuggestedResolution);
        m.put("ai_source", t.aiSource);
        return m;
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> params = new HashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) return params;
        for (String pair : rawQuery.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            params.put(key, value);
        }
        return params;
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        // CORS headers so the dashboard works from any public origin
        ex.getResponseHeaders().add("Content-Type", contentType + "; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // -----------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        Path csv = Paths.get("data", "transactions.csv");
        Path indexHtml = Paths.get("resources", "index.html");
        TransactionStore store = new TransactionStore(csv);
        // Railway (and most PaaS) injects PORT via environment variable.
        // Fall back to CLI arg, then to 8080 for local dev.
        String envPort = System.getenv("PORT");
        int port = envPort != null && !envPort.isBlank()
                ? Integer.parseInt(envPort)
                : (args.length > 0 ? Integer.parseInt(args[0]) : 8080);
        new Server(store, indexHtml).start(port);
    }
}
