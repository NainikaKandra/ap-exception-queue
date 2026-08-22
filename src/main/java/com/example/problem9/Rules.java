package com.example.problem9;

import java.util.*;

/**
 * Deterministic exception-detection rules (Assumption 2).
 *
 * Design choice, same as the Python version of this prototype: confidence
 * scores come ONLY from these rules, never from the AI model. The AI
 * (AiExplainer.java) is used purely to explain/suggest a resolution in
 * plain language (Assumption 3), so the auto-resolution decision stays
 * fully auditable and reproducible from code.
 */
public class Rules {

    /** One rule's verdict: did it trigger, why, and how confident is it. */
    private record RuleOutcome(boolean triggered, String reason, int confidence) {
    }

    private static RuleOutcome checkDuplicateInvoice(List<Transaction> all, Transaction row) {
        long matches = all.stream()
                .filter(t -> t.vendor.equals(row.vendor)
                        && t.invoiceNumber.equals(row.invoiceNumber)
                        && t.invoiceAmount == row.invoiceAmount)
                .count();
        if (matches > 1) {
            return new RuleOutcome(true,
                    "Duplicate invoice: same vendor, invoice number, and amount appear more than once.",
                    96);
        }
        return new RuleOutcome(false, "", 100);
    }

    private static RuleOutcome checkAmountMismatch(Transaction row) {
        double poAmount = row.poAmount;
        double invoiceAmount = row.invoiceAmount;
        if (poAmount == 0) return new RuleOutcome(false, "", 100);

        double diffPct = Math.abs(invoiceAmount - poAmount) / poAmount;
        double tolerance = 0.01;
        if (diffPct <= tolerance) return new RuleOutcome(false, "", 100);

        String reason = String.format(
                "Amount mismatch: invoice $%,.2f vs PO $%,.2f (%.1f%% difference).",
                invoiceAmount, poAmount, diffPct * 100);

        if (diffPct <= 0.05) return new RuleOutcome(true, reason, 92);
        if (diffPct <= 0.15) return new RuleOutcome(true, reason, 78);
        return new RuleOutcome(true, reason, 55);
    }

    private static RuleOutcome checkTaxMismatch(Transaction row) {
        double expected = row.expectedTaxAmount;
        double actual = row.taxAmount;
        if (expected == 0) return new RuleOutcome(false, "", 100);

        double diffPct = Math.abs(actual - expected) / expected;
        double tolerance = 0.02;
        if (diffPct <= tolerance) return new RuleOutcome(false, "", 100);

        String reason = String.format(
                "Tax mismatch: recorded $%,.2f vs expected $%,.2f (%.1f%% difference).",
                actual, expected, diffPct * 100);

        if (diffPct <= 0.10) return new RuleOutcome(true, reason, 90);
        return new RuleOutcome(true, reason, 70);
    }

    private static RuleOutcome checkMissingPo(Transaction row) {
        String po = row.poNumber == null ? "" : row.poNumber.trim();
        if (po.isEmpty()) {
            return new RuleOutcome(true,
                    "Missing PO reference: no purchase order to validate this invoice against.",
                    60);
        }
        return new RuleOutcome(false, "", 100);
    }

    /** Runs every rule against one transaction and fills in its result fields in place. */
    public static void evaluate(List<Transaction> all, Transaction row) {
        Map<String, RuleOutcome> checks = new LinkedHashMap<>();
        checks.put("duplicate_invoice", checkDuplicateInvoice(all, row));
        checks.put("amount_mismatch", checkAmountMismatch(row));
        checks.put("tax_mismatch", checkTaxMismatch(row));
        checks.put("missing_po", checkMissingPo(row));

        row.triggeredRules.clear();
        row.reasons.clear();
        List<Integer> confidences = new ArrayList<>();

        for (Map.Entry<String, RuleOutcome> e : checks.entrySet()) {
            RuleOutcome outcome = e.getValue();
            if (outcome.triggered()) {
                row.triggeredRules.add(e.getKey());
                row.reasons.add(outcome.reason());
                confidences.add(outcome.confidence());
            }
        }

        row.isException = !row.triggeredRules.isEmpty();
        // Most conservative: the weakest signal caps the overall confidence.
        row.confidence = confidences.isEmpty() ? 100 : Collections.min(confidences);
    }

    /** Runs {@link #evaluate} over every transaction in the list. */
    public static void evaluateAll(List<Transaction> all) {
        for (Transaction t : all) {
            evaluate(all, t);
        }
    }
}
