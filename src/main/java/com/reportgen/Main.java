package com.reportgen;

public class Main {
    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: report-generator <config.json> <input.xlsx> <output.html>");
            System.exit(1);
        }
        System.out.println("Report Generator MVP — TODO: wire pipeline");
    }
}
