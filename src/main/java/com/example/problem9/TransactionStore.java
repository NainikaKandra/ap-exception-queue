package com.example.problem9;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Simple in-memory store for the transaction queue. Loads from
 * data/transactions.csv at startup (generating it first if missing),
 * runs the rule engine once, and keeps everything in a List guarded by
 * a single lock -- this is a single-user local prototype, not a
 * multi-user backend, so this is intentionally simple (Assumption 7:
 * status changes only ever affect this in-app state).
 */
public class TransactionStore {

    private final Path csvPath;
    private final List<Transaction> transactions = new ArrayList<>();
    private final Object lock = new Object();
    private final AiExplainer aiExplainer = new AiExplainer();

    public TransactionStore(Path csvPath) throws IOException {
        this.csvPath = csvPath;
        if (!Files.exists(csvPath)) {
            Files.createDirectories(csvPath.getParent());
            CsvUtil.writeTransactions(csvPath, DataGenerator.generate(42));
        }
        reload();
    }

    public void reload() throws IOException {
        synchronized (lock) {
            transactions.clear();
            transactions.addAll(CsvUtil.readTransactions(csvPath));
            Rules.evaluateAll(transactions);
        }
    }

    public List<Transaction> all() {
        synchronized (lock) {
            return new ArrayList<>(transactions);
        }
    }

    public Optional<Transaction> find(String id) {
        synchronized (lock) {
            return transactions.stream().filter(t -> t.transactionId.equals(id)).findFirst();
        }
    }

    /** Calls the AI explainer for one transaction and caches the result on it. */
    public Transaction explain(String id) {
        synchronized (lock) {
            Transaction t = transactions.stream()
                    .filter(x -> x.transactionId.equals(id)).findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Unknown transaction: " + id));
            AiExplainer.Explanation ex = aiExplainer.explain(t);
            t.aiExplanation = ex.explanation();
            t.aiSuggestedResolution = ex.suggestedResolution();
            t.aiSource = ex.source();
            return t;
        }
    }

    /**
     * Applies a workflow action to a transaction, re-checking the
     * confidence tier server-side so the UI can't force an action the
     * rules don't allow (e.g. auto-resolving a low-confidence item).
     */
    public Transaction applyAction(String id, String action) {
        synchronized (lock) {
            Transaction t = transactions.stream()
                    .filter(x -> x.transactionId.equals(id)).findFirst()
                    .orElseThrow(() -> new NoSuchElementException("Unknown transaction: " + id));

            String tier = t.resolutionTier();

            switch (action) {
                case "auto" -> {
                    if (!tier.equals("Auto-Resolve")) {
                        throw new IllegalStateException("Confidence too low for auto-resolve.");
                    }
                    t.resolutionStatus = "Auto-Resolved";
                }
                case "approve" -> {
                    if (!tier.equals("Human Approval")) {
                        throw new IllegalStateException("This transaction is not in the Human Approval tier.");
                    }
                    t.resolutionStatus = "Resolved (Approved)";
                }
                case "reject" -> {
                    if (!tier.equals("Human Approval")) {
                        throw new IllegalStateException("This transaction is not in the Human Approval tier.");
                    }
                    t.resolutionStatus = "Manual Review";
                }
                case "manual" -> t.resolutionStatus = "Resolved (Manual)";
                default -> throw new IllegalArgumentException("Unknown action: " + action);
            }
            return t;
        }
    }
}
