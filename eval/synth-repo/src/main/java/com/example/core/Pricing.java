package com.example.core;

/**
 * Branch-heavy pricing rules with NO tests at all: the eval's "0 % coverage,
 * 0 % mutation score" target. Every discount boundary is a ConditionalsBoundary
 * mutant waiting to survive, every arithmetic step a Math mutant.
 */
public final class Pricing {

    public static final double VAT_RATE = 0.2;

    private Pricing() {
    }

    /** Discount percentage for an order, by quantity and customer tier. */
    public static int discountPercent(int quantity, String tier) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        int base;
        if (quantity >= 100) {
            base = 15;
        } else if (quantity >= 50) {
            base = 10;
        } else if (quantity >= 10) {
            base = 5;
        } else {
            base = 0;
        }
        int bonus = 0;
        if ("gold".equalsIgnoreCase(tier)) {
            bonus = 10;
        } else if ("silver".equalsIgnoreCase(tier)) {
            bonus = 5;
        }
        int total = base + bonus;
        return Math.min(total, 20);
    }

    /** Net total after discount, rounded to cents. */
    public static double net(double unitPrice, int quantity, String tier) {
        if (unitPrice < 0) {
            throw new IllegalArgumentException("unitPrice must not be negative");
        }
        double gross = unitPrice * quantity;
        double discount = gross * discountPercent(quantity, tier) / 100.0;
        return round2(gross - discount);
    }

    /** Gross total including VAT. */
    public static double gross(double unitPrice, int quantity, String tier) {
        double net = net(unitPrice, quantity, tier);
        return round2(net * (1 + VAT_RATE));
    }

    /** Free shipping above a threshold, flat fee otherwise, doubled for express. */
    public static double shipping(double orderValue, boolean express) {
        double fee;
        if (orderValue >= 100.0) {
            fee = 0.0;
        } else if (orderValue >= 25.0) {
            fee = 4.95;
        } else {
            fee = 9.95;
        }
        if (express) {
            fee = fee * 2 + 2.5;
        }
        return round2(fee);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
