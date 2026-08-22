package com.example.problem9;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain data holder for one transaction plus the results of running it
 * through the deterministic rules (see Rules.java) and, optionally, the
 * AI explanation (see AiExplainer.java).
 *
 * Kept as a simple mutable POJO on purpose -- no framework, no annotations,
 * easy to read top to bottom.
 */
public class Transaction {

    // --- raw transaction fields (mirrors data/transactions.csv) ---
    public String transactionId;
    public String vendor;
    public String poNumber;
    public String invoiceNumber;
    public double invoiceAmount;
    public double poAmount;
    public double taxAmount;
    public double expectedTaxAmount;
    public String invoiceDate;

    // --- rule engine output (Assumption 2) ---
    public boolean isException = false;
    public List<String> triggeredRules = new ArrayList<>();
    public List<String> reasons = new ArrayList<>();
    public int confidence = 100; // 100 = not an exception / fully confident

    // --- workflow state (Assumptions 4-7) ---
    // One of: Open, Auto-Resolved, Resolved (Approved), Manual Review,
    // Resolved (Manual)
    public String resolutionStatus = "Open";

    // --- AI explanation cache (Assumption 3) ---
    public String aiExplanation;
    public String aiSuggestedResolution;
    public String aiSource; // "anthropic" | "openai" | "local_fallback..."

    /** Confidence tier -> routing decision. Mirrors Assumptions 4-6. */
    public String resolutionTier() {
        if (confidence >= 90) return "Auto-Resolve";
        if (confidence >= 70) return "Human Approval";
        return "Manual Review";
    }

    public String reasonsJoined() {
        return String.join(" | ", reasons);
    }

    public String triggeredRulesJoined() {
        return String.join(", ", triggeredRules);
    }
}
