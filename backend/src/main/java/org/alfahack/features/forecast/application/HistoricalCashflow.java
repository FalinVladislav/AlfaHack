package org.alfahack.features.forecast.application;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Descriptive trend/seasonality extrapolation; residuals are not calibrated
 * quantiles.
 */
final class HistoricalCashflow {

    private final YearMonth first;
    private final int count;
    private final double[] income;
    private final double[] expenses;
    private final double[] season = new double[12];
    private final double spread;

    HistoricalCashflow(
        List<Map<String, Object>> months,
        double embeddedDebt
    ) {
        first = YearMonth.parse(months.getFirst().get("month").toString());
        count = months.size();
        income = fit(months.stream().mapToDouble(m -> n(m.get("income"))).toArray());
        expenses = fit(months.stream().mapToDouble(m -> Math.max(0, n(m.get("expenses")) - embeddedDebt)).toArray());
        var samples = new int[12];
        if (count >= 24) {
            for (int i = 0; i < count; i++) {
                int slot = first.plusMonths(i).getMonthValue() - 1;
                season[slot] += n(months.get(i).get("income")) - (income[0] + income[1] * i);
                samples[slot]++;
            }
            for (int slot = 0; slot < 12; slot++)
                season[slot] /= samples[slot];
        }
        double max = 0;
        double mean = months.stream().mapToDouble(m -> n(m.get("income"))).average().orElseThrow();
        for (int i = 0; i < count; i++)
            max = Math.max(max, Math.abs(n(months.get(i).get("income")) - income(first.plusMonths(i))));
        spread = max / mean;
    }

    private static double n(Object value) {
        return Double.parseDouble(value.toString());
    }

    private static double[] fit(double[] values) {
        double mid = (values.length - 1) / 2.0, mean = java.util.Arrays.stream(values).average().orElseThrow();
        double numerator = 0, denominator = 0;
        for (int i = 0; i < values.length; i++) {
            numerator += (i - mid) * (values[i] - mean);
            denominator += (i - mid) * (i - mid);
        }
        double slope = numerator / denominator;
        return new double[]{mean - slope * mid, slope};
    }

    double income(YearMonth month) {
        return Math.max(0,
            income[0] + income[1] * ChronoUnit.MONTHS.between(first, month) + season[month.getMonthValue() - 1]);
    }

    double expenses(YearMonth month) {
        return Math.max(0, expenses[0] + expenses[1] * ChronoUnit.MONTHS.between(first, month));
    }

    double spread() {
        return spread;
    }

    boolean seasonal() {
        return count >= 24;
    }
}
