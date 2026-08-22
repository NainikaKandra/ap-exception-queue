package com.example.problem9;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/**
 * Turns a flagged transaction + rule-based reasons into a plain-English
 * explanation and a suggested resolution (Assumption 3: AI explains and
 * suggests, it does NOT set the confidence score or make the final call
 * -- that's Rules.java + the human/threshold workflow in the app).
 *
 * Provider selection (checked in this order):
 *   1. ANTHROPIC_API_KEY env var set -> Claude (claude-3-5-haiku-latest)
 *   2. OPENAI_API_KEY env var set    -> OpenAI (gpt-4o-mini)
 *   3. Neither present               -> local template-based explanation
 *
 * The local fallback exists so the whole prototype runs and is demoable
 * with zero API keys / zero cost, and uses only java.net.http (JDK
 * built-in) -- no external HTTP or JSON library required.
 */
public class AiExplainer {

    private static final String SYSTEM_PROMPT =
            "You are an accounts-payable assistant. You are given a flagged " +
            "transaction and the deterministic rule(s) that flagged it. " +
            "Write a short, plain-English explanation (1-2 sentences) of why " +
            "this was flagged, and a short suggested resolution (1-2 sentences) " +
            "for a finance reviewer. Do not invent facts not present in the " +
            "data. Do not state a confidence score yourself -- that is " +
            "computed separately by deterministic rules. Respond ONLY as " +
            "JSON: {\"explanation\": \"...\", \"suggested_resolution\": \"...\"}";

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public record Explanation(String explanation, String suggestedResolution, String source) {
    }

    public Explanation explain(Transaction t) {
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");
        String openaiKey = System.getenv("OPENAI_API_KEY");

        if (anthropicKey != null && !anthropicKey.isBlank()) {
            try {
                return callAnthropic(t, anthropicKey);
            } catch (Exception e) {
                Explanation fb = localFallback(t);
                return new Explanation(fb.explanation(), fb.suggestedResolution(),
                        "local_fallback (anthropic error: " + e.getMessage() + ")");
            }
        }

        if (openaiKey != null && !openaiKey.isBlank()) {
            try {
                return callOpenAi(t, openaiKey);
            } catch (Exception e) {
                Explanation fb = localFallback(t);
                return new Explanation(fb.explanation(), fb.suggestedResolution(),
                        "local_fallback (openai error: " + e.getMessage() + ")");
            }
        }

        Explanation fb = localFallback(t);
        return new Explanation(fb.explanation(), fb.suggestedResolution(),
                "local_fallback (no API key configured)");
    }

    private String userPrompt(Transaction t) {
        return "Transaction: transaction_id=" + t.transactionId
                + ", vendor=" + t.vendor
                + ", invoice_amount=" + t.invoiceAmount
                + ", po_amount=" + t.poAmount
                + ", tax_amount=" + t.taxAmount
                + ", expected_tax_amount=" + t.expectedTaxAmount
                + ", po_number=" + t.poNumber
                + "\nTriggered rules: " + t.triggeredRulesJoined()
                + "\nRule reasons: " + t.reasonsJoined()
                + "\nRule-based confidence: " + t.confidence;
    }

    // -----------------------------------------------------------------
    // Anthropic
    // -----------------------------------------------------------------

    private Explanation callAnthropic(Transaction t, String apiKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "claude-3-5-haiku-latest");
        body.put("max_tokens", 300);
        body.put("system", SYSTEM_PROMPT);
        body.put("messages", List.of(Map.of("role", "user", "content", userPrompt(t))));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(response.body());
        @SuppressWarnings("unchecked")
        List<Object> content = (List<Object>) parsed.get("content");
        StringBuilder text = new StringBuilder();
        for (Object block : content) {
            @SuppressWarnings("unchecked")
            Map<String, Object> b = (Map<String, Object>) block;
            if ("text".equals(b.get("type"))) {
                text.append(b.get("text"));
            }
        }

        return parseModelJson(text.toString(), "anthropic");
    }

    // -----------------------------------------------------------------
    // OpenAI
    // -----------------------------------------------------------------

    private Explanation callOpenAi(Transaction t, String apiKey) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "gpt-4o-mini");
        body.put("messages", List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", userPrompt(t))
        ));
        body.put("response_format", Map.of("type", "json_object"));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + response.statusCode() + ": " + response.body());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = (Map<String, Object>) Json.parse(response.body());
        @SuppressWarnings("unchecked")
        List<Object> choices = (List<Object>) parsed.get("choices");
        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) choices.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) first.get("message");
        String text = (String) message.get("content");

        return parseModelJson(text, "openai");
    }

    // -----------------------------------------------------------------
    // Shared helpers
    // -----------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Explanation parseModelJson(String text, String source) {
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        }
        Map<String, Object> obj = (Map<String, Object>) Json.parse(cleaned);
        return new Explanation(
                String.valueOf(obj.get("explanation")),
                String.valueOf(obj.get("suggested_resolution")),
                source
        );
    }

    /** Deterministic, template-based explanation. No network/API needed. */
    private Explanation localFallback(Transaction t) {
        String rules = t.triggeredRulesJoined();
        String reasons = t.reasonsJoined();
        int confidence = t.confidence;

        String explanation = "Flagged by rule(s) [" + rules + "]. " + reasons;

        String suggestion;
        if (rules.contains("duplicate_invoice")) {
            suggestion = "Reject or void the duplicate invoice; keep only one payment for this invoice number.";
        } else if (rules.contains("amount_mismatch")) {
            suggestion = "Compare invoice line items against the PO and confirm with the vendor before adjusting or approving the payment amount.";
        } else if (rules.contains("tax_mismatch")) {
            suggestion = "Recalculate tax at the standard rate and request a corrected invoice from the vendor if the discrepancy persists.";
        } else if (rules.contains("missing_po")) {
            suggestion = "Request the purchase order number from the requester/vendor before approving payment.";
        } else {
            suggestion = "Review transaction details manually.";
        }

        if (confidence >= 90) {
            suggestion += " Low risk -- eligible for automatic resolution.";
        } else if (confidence >= 70) {
            suggestion += " Moderate risk -- route to a human approver.";
        } else {
            suggestion += " Low confidence -- send to manual review.";
        }

        return new Explanation(explanation, suggestion, "local_fallback");
    }
}
