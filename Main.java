// Main.java
// Reads CSV, computes scores, ranks, decisions, and fairness summaries.

import java.io.*;
import java.util.*;
import java.util.stream.Collectors;

public class Main {

    // Parses a CSV line, accounting for quotes and commas
    private static String[] parseCSVLine(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;

        for (char c : line.toCharArray()) {
            if (c == '"') inQuotes = !inQuotes;
            else if ((c == ',' || c == '\t') && !inQuotes) {
                result.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString().trim());
        return result.toArray(new String[0]);
    }

    // Reads all applicants from a CSV file
    public static List<Applicant> readApplicants(String filename) {
        List<Applicant> applicants = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            br.readLine(); // skip header

            while ((line = br.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                String[] p = parseCSVLine(line);
                if (p.length < 14) continue;

                try {
                    String name = p[0];
                    int age = Integer.parseInt(p[1]);
                    String geography = p[2];
                    String ethnicity = p[3];
                    double income = Double.parseDouble(p[4].replace("$", "").replace(",", ""));
                    boolean legacy = p[5].equalsIgnoreCase("Yes");
                    boolean local = p[6].equalsIgnoreCase("Yes");
                    double gpa = Double.parseDouble(p[7]);
                    int test = Integer.parseInt(p[8]);
                    double extra = Double.parseDouble(p[9]);
                    double essay = Double.parseDouble(p[10]);
                    double rec = Double.parseDouble(p[11]);
                    boolean firstGen = p[12].equalsIgnoreCase("Yes");
                    boolean disability = p[13].equalsIgnoreCase("Yes");

                    applicants.add(new Applicant(name, age, geography, ethnicity, income,
                            legacy, local, gpa, test, extra, essay, rec, firstGen, disability));

                } catch (Exception e) {
                    System.out.println("Skipping malformed row: " + line);
                }
            }

        } catch (IOException e) {
            System.out.println("Error reading file: " + e.getMessage());
        }

        return applicants;
    }

    private static class Row {
        Applicant a;
        double blind, aware;
        String blindDecision, awareDecision;
        Row(Applicant a, double blind, double aware, String bd, String ad) {
            this.a=a; this.blind=blind; this.aware=aware; this.blindDecision=bd; this.awareDecision=ad;
        }
    }

    public static void main(String[] args) {
        // Allow custom cutoff via args[0], default 0.82 as in the original
        double cutoff = 0.82;
        if (args.length >= 1) {
            try { cutoff = Double.parseDouble(args[0]); } catch (Exception ignored) {}
        }

        List<Applicant> applicants = readApplicants("applicants.csv");
        if (applicants.isEmpty()) {
            System.out.println("No applicants found. Check CSV format or path.");
            return;
        }

        // Compute scores and decisions
        List<Row> rows = new ArrayList<>();
        for (Applicant app : applicants) {
            double blind = Admissions.blindScore(app);
            double aware = Admissions.awareScore(app);
            String blindDecision = (blind >= cutoff) ? "Admitted" : "Rejected";
            String awareDecision = (aware >= cutoff) ? "Admitted" : "Rejected";
            rows.add(new Row(app, blind, aware, blindDecision, awareDecision));
        }

        // Rank by each model
        List<Row> byBlind = new ArrayList<>(rows);
        byBlind.sort((r1, r2) -> Double.compare(r2.blind, r1.blind));
        List<Row> byAware = new ArrayList<>(rows);
        byAware.sort((r1, r2) -> Double.compare(r2.aware, r1.aware));

        // Map name -> rank for quick comparison
        Map<String,Integer> rankBlind = new HashMap<>();
        Map<String,Integer> rankAware = new HashMap<>();
        for (int i=0;i<byBlind.size();i++) rankBlind.put(byBlind.get(i).a.name, i+1);
        for (int i=0;i<byAware.size();i++) rankAware.put(byAware.get(i).a.name, i+1);

        System.out.println("=== Admissions Results (cutoff = " + cutoff + ") ===");
        System.out.printf("%-15s | %6s | %6s | %8s | %8s | %6s | %6s | %s%n",
                "Name","Blind","Aware","B.Dec","A.Dec","BRank","ARank","ΔRank");
        for (Row r : rows) {
            int rb = rankBlind.get(r.a.name);
            int ra = rankAware.get(r.a.name);
            int dRank = rb - ra; // positive => improved in Aware
            System.out.printf("%-15s | %6.2f | %6.2f | %8s | %8s | %6d | %6d | %+d%n",
                    r.a.name, r.blind, r.aware, r.blindDecision, r.awareDecision, rb, ra, dRank);
        }

        // Where decisions differ
        System.out.println("\n=== Disagreement (Blind vs Aware) ===");
        long flipsUp = rows.stream().filter(r -> r.blindDecision.equals("Rejected") && r.awareDecision.equals("Admitted")).count();
        long flipsDown = rows.stream().filter(r -> r.blindDecision.equals("Admitted") && r.awareDecision.equals("Rejected")).count();
        System.out.println("Rejected→Admitted (Aware uplift): " + flipsUp);
        System.out.println("Admitted→Rejected (Aware downshift): " + flipsDown);

        // Fairness summary: admission rate by groups
        summarizeGroup(rows, cutoff, "Low income",   r -> r.a.income < Admissions.LOW_INCOME_THRESHOLD);
        summarizeGroup(rows, cutoff, "First-gen",    r -> r.a.firstGen);
        summarizeGroup(rows, cutoff, "Disability",   r -> r.a.disability);
        summarizeGroup(rows, cutoff, "Legacy",       r -> r.a.legacy);
        summarizeGroup(rows, cutoff, "Local",        r -> r.a.local);
    }

    interface Pred { boolean test(Row r); }

    private static void summarizeGroup(List<Row> rows, double cutoff, String label, Pred pred) {
        List<Row> in  = rows.stream().filter(pred::test).collect(Collectors.toList());
        List<Row> out = rows.stream().filter(r -> !pred.test(r)).collect(Collectors.toList());

        double blindIn  = rate(in,  r -> r.blind >= cutoff);
        double blindOut = rate(out, r -> r.blind >= cutoff);
        double awareIn  = rate(in,  r -> r.aware >= cutoff);
        double awareOut = rate(out, r -> r.aware >= cutoff);

        System.out.printf("%n=== Group: %s ===%n", label);
        System.out.printf("Blind  admit rate | In-group: %.1f%%  vs  Out-group: %.1f%%%n", blindIn*100, blindOut*100);
        System.out.printf("Aware  admit rate | In-group: %.1f%%  vs  Out-group: %.1f%%%n", awareIn*100, awareOut*100);
    }

    private static double rate(List<Row> rows, java.util.function.Predicate<Row> p) {
        if (rows.isEmpty()) return 0.0;
        long cnt = rows.stream().filter(p).count();
        return (double) cnt / rows.size();
    }
}
