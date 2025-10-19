// Admissions.java
// Scoring models with clear weights & tunable thresholds.

public class Admissions {

    // === Configurable parameters (can be tweaked for experiments) ===
    public static double MAX_GPA = 4.0;
    public static double MAX_TEST = Double.parseDouble(System.getProperty("maxTest", "1600")); // override: -DmaxTest=36

    // Blind weights (only performance/merit factors)
    public static double W_GPA   = 0.45;
    public static double W_TEST  = 0.30;
    public static double W_EXTRA = 0.10;
    public static double W_ESSAY = 0.10;
    public static double W_REC   = 0.05;

    // Aware bonuses (equity & context)
    public static double LOW_INCOME_THRESHOLD = Double.parseDouble(System.getProperty("lowIncome", "40000"));
    public static double BONUS_LOW_INCOME = 0.05;
    public static double BONUS_FIRST_GEN  = 0.05;
    public static double BONUS_DISABILITY = 0.03;
    public static double BONUS_LEGACY     = 0.02; // you can set this to 0 or negative if you want to neutralize/penalize
    public static double BONUS_LOCAL      = 0.03;

    private static double clamp01(double x) {
        if (x < 0) return 0;
        if (x > 1) return 1;
        return x;
    }

    private static double nz(double x) {
        // in case any feature comes malformed; keeps score stable
        if (Double.isNaN(x) || Double.isInfinite(x)) return 0.0;
        return x;
    }

    // Blind model (performance only)
    public static double blindScore(Applicant app) {
        // normalize core features to [0,1]
        double gpaN   = clamp01(nz(app.gpa) / MAX_GPA);
        double testN  = clamp01(nz(app.test) / MAX_TEST);
        double extraN = clamp01(nz(app.extra)); // assumed already 0..1 in CSV
        double essayN = clamp01(nz(app.essay)); // assumed 0..1
        double recN   = clamp01(nz(app.rec));   // assumed 0..1

        double score = 0.0;
        score += gpaN   * W_GPA;
        score += testN  * W_TEST;
        score += extraN * W_EXTRA;
        score += essayN * W_ESSAY;
        score += recN   * W_REC;

        return clamp01(score);
    }

    // Aware model (adds contextual equity)
    public static double awareScore(Applicant app) {
        double score = blindScore(app);

        if (app.income < LOW_INCOME_THRESHOLD) score += BONUS_LOW_INCOME;
        if (app.firstGen)                      score += BONUS_FIRST_GEN;
        if (app.disability)                    score += BONUS_DISABILITY;
        if (app.legacy)                        score += BONUS_LEGACY;
        if (app.local)                         score += BONUS_LOCAL;

        return clamp01(score);
    }
}
