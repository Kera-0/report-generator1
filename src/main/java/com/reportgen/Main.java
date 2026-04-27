package com.reportgen;

public class Main {
    public static void main(String[] args) {
        int exitCode = run(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: report-generator <config.json> <input.xlsx> <output.html>");
            return 1;
        }

        try {
            new ReportGenerator().generate(args[0], args[1], args[2]);
            System.out.println("Report generated: " + args[2]);
            return 0;
        } catch (Exception e) {
            System.err.println("Failed to generate report: " + e.getMessage());
            return 2;
        }
    }
}
