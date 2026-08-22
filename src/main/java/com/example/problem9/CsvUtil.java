package com.example.problem9;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Minimal CSV reader/writer. Deliberately not a generic library -- just
 * enough to read/write the fixed transaction schema used in this
 * prototype, with basic double-quote escaping for the "reasons" field
 * which may contain commas.
 */
public class CsvUtil {

    public static final String[] HEADER = {
            "transaction_id", "vendor", "po_number", "invoice_number",
            "invoice_amount", "po_amount", "tax_amount", "expected_tax_amount",
            "invoice_date"
    };

    public static List<Transaction> readTransactions(Path path) throws IOException {
        List<Transaction> result = new ArrayList<>();
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        if (lines.isEmpty()) return result;

        String[] header = splitCsvLine(lines.get(0));
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < header.length; i++) col.put(header[i], i);

        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            String[] fields = splitCsvLine(lines.get(i));
            Transaction t = new Transaction();
            t.transactionId = fields[col.get("transaction_id")];
            t.vendor = fields[col.get("vendor")];
            t.poNumber = fields[col.get("po_number")];
            t.invoiceNumber = fields[col.get("invoice_number")];
            t.invoiceAmount = Double.parseDouble(fields[col.get("invoice_amount")]);
            t.poAmount = Double.parseDouble(fields[col.get("po_amount")]);
            t.taxAmount = Double.parseDouble(fields[col.get("tax_amount")]);
            t.expectedTaxAmount = Double.parseDouble(fields[col.get("expected_tax_amount")]);
            t.invoiceDate = fields[col.get("invoice_date")];
            result.add(t);
        }
        return result;
    }

    public static void writeTransactions(Path path, List<Transaction> transactions) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            w.write(String.join(",", HEADER));
            w.newLine();
            for (Transaction t : transactions) {
                w.write(String.join(",",
                        t.transactionId,
                        csvEscape(t.vendor),
                        csvEscape(t.poNumber),
                        t.invoiceNumber,
                        String.valueOf(t.invoiceAmount),
                        String.valueOf(t.poAmount),
                        String.valueOf(t.taxAmount),
                        String.valueOf(t.expectedTaxAmount),
                        t.invoiceDate
                ));
                w.newLine();
            }
        }
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Very small CSV line splitter that respects double-quoted fields. */
    private static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else {
                if (c == '"') {
                    inQuotes = true;
                } else if (c == ',') {
                    fields.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
        }
        fields.add(cur.toString());
        return fields.toArray(new String[0]);
    }
}
