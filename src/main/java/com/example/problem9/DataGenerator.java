package com.example.problem9;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Creates a small synthetic transactions.csv (Assumption 1: synthetic
 * data only, stored locally). Mirrors generate_data.py from the Python
 * version of this prototype so the same rule/confidence outcomes show up
 * in both.
 */
public class DataGenerator {

    private static final String[] VENDORS = {
            "Acme Supplies", "Globex Logistics", "Initech Parts",
            "Umbrella Distribution", "Wayne Materials"
    };
    private static final double TAX_RATE = 0.08;

    public static List<Transaction> generate(long seed) {
        Random rnd = new Random(seed);
        List<Transaction> rows = new ArrayList<>();
        LocalDate start = LocalDate.of(2026, 6, 1);
        int[] counter = {1};

        // 1) Clean transactions
        for (int i = 0; i < 6; i++) {
            double amount = round2(200 + rnd.nextDouble() * 4800);
            rows.add(makeRow(counter, VENDORS[rnd.nextInt(VENDORS.length)],
                    "PO-" + (1000 + counter[0]), "INV-" + (2000 + counter[0]),
                    amount, amount, round2(amount * TAX_RATE),
                    start.plusDays(rnd.nextInt(61))));
        }

        // 2) Duplicate invoice: same vendor/invoice number/amount twice
        {
            String vendor = VENDORS[rnd.nextInt(VENDORS.length)];
            double amount = round2(500 + rnd.nextDouble() * 2500);
            String po = "PO-" + (1000 + counter[0]);
            String inv = "INV-" + (2000 + counter[0]);
            LocalDate d = start.plusDays(10);
            rows.add(makeRow(counter, vendor, po, inv, amount, amount, round2(amount * TAX_RATE), d));
            rows.add(makeRow(counter, vendor, po, inv, amount, amount, round2(amount * TAX_RATE), d));
        }

        // 3) Amount mismatch: small / medium / large
        for (double pctDiff : new double[]{0.03, 0.12, 0.25}) {
            String vendor = VENDORS[rnd.nextInt(VENDORS.length)];
            double poAmount = round2(500 + rnd.nextDouble() * 3500);
            double invoiceAmount = round2(poAmount * (1 + pctDiff));
            rows.add(makeRow(counter, vendor, "PO-" + (1000 + counter[0]),
                    "INV-" + (2000 + counter[0]), invoiceAmount, poAmount,
                    round2(invoiceAmount * TAX_RATE), start.plusDays(rnd.nextInt(61))));
        }

        // 4) Tax mismatch
        double[] taxMultipliers = {0.5, 1.5, 2.0};
        for (double mult : taxMultipliers) {
            String vendor = VENDORS[rnd.nextInt(VENDORS.length)];
            double amount = round2(300 + rnd.nextDouble() * 2200);
            double wrongTax = round2(amount * TAX_RATE * mult);
            rows.add(makeRow(counter, vendor, "PO-" + (1000 + counter[0]),
                    "INV-" + (2000 + counter[0]), amount, amount, wrongTax,
                    start.plusDays(rnd.nextInt(61))));
        }

        // 5) Missing PO reference
        for (int i = 0; i < 3; i++) {
            String vendor = VENDORS[rnd.nextInt(VENDORS.length)];
            double amount = round2(300 + rnd.nextDouble() * 2700);
            rows.add(makeRow(counter, vendor, "", "INV-" + (2000 + counter[0]),
                    amount, amount, round2(amount * TAX_RATE), start.plusDays(rnd.nextInt(61))));
        }

        // 6) Combined exception: missing PO + amount mismatch
        {
            String vendor = VENDORS[rnd.nextInt(VENDORS.length)];
            double poAmount = round2(500 + rnd.nextDouble() * 1500);
            double invoiceAmount = round2(poAmount * 1.3);
            rows.add(makeRow(counter, vendor, "", "INV-" + (2000 + counter[0]),
                    invoiceAmount, poAmount, round2(invoiceAmount * TAX_RATE), start.plusDays(20)));
        }

        return rows;
    }

    private static Transaction makeRow(int[] counter, String vendor, String po, String inv,
                                        double invoiceAmount, double poAmount, double taxAmount,
                                        LocalDate date) {
        Transaction t = new Transaction();
        t.transactionId = String.format("T%04d", counter[0]);
        t.vendor = vendor;
        t.poNumber = po;
        t.invoiceNumber = inv;
        t.invoiceAmount = invoiceAmount;
        t.poAmount = poAmount;
        t.taxAmount = taxAmount;
        t.expectedTaxAmount = round2(invoiceAmount * TAX_RATE);
        t.invoiceDate = date.toString();
        counter[0]++;
        return t;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /** CLI entry point: `java DataGenerator` writes data/transactions.csv */
    public static void main(String[] args) throws Exception {
        List<Transaction> rows = generate(42);
        Path outDir = Paths.get("data");
        Files.createDirectories(outDir);
        Path out = outDir.resolve("transactions.csv");
        CsvUtil.writeTransactions(out, rows);
        System.out.println("Wrote " + rows.size() + " synthetic transactions to " + out);
    }
}
