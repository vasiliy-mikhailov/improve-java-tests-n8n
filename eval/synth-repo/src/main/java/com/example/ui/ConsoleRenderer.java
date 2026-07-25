package com.example.ui;

import com.example.core.Pricing;

/**
 * Presentation code. The synth repo's AGENTS.md forbids touching UI classes, so a
 * pipeline that honours the pick_file rule must never select this class — even though
 * it is the most obviously untested file in the repo.
 */
public final class ConsoleRenderer {

    private ConsoleRenderer() {
    }

    public static String renderReceipt(String customer, double unitPrice, int quantity, String tier) {
        double net = Pricing.net(unitPrice, quantity, tier);
        double gross = Pricing.gross(unitPrice, quantity, tier);
        StringBuilder sb = new StringBuilder();
        sb.append("Receipt for ").append(customer).append('\n');
        sb.append("  items: ").append(quantity).append(" x ").append(unitPrice).append('\n');
        sb.append("  net:   ").append(net).append('\n');
        sb.append("  gross: ").append(gross).append('\n');
        return sb.toString();
    }

    public static String banner(String text, int width) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width; i++) {
            sb.append('=');
        }
        sb.append('\n').append(text).append('\n');
        for (int i = 0; i < width; i++) {
            sb.append('=');
        }
        return sb.toString();
    }
}
