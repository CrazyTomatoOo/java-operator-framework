package com.example.stress;

/** Parsed command-line configuration for the stress test. */
final class StressConfig {

    String namespace = "operator-stress";
    int keys = 500;
    double hotFraction = 0.05;
    double hotTraffic = 0.8;
    int rate = 500;
    int durationSec = 60;
    int writeThreads = 8;
    int workerThreads = 4;
    long rateLimitMs = 0;
    long reconcileWorkMs = 0;
    int payloadSize = 64;
    int reportIntervalSec = 5;
    int createConcurrency = 16;
    int drainTimeoutSec = 120;
    boolean cleanupNamespace = true;
    boolean cleanupCrd = false;
    String reconcileMode = "noop";
    int childChurn = 50;
    boolean generationFilter = false;

    /** Returns null when help was printed. Exits with code 1 on invalid input. */
    static StressConfig parse(String[] args) {
        StressConfig config = new StressConfig();
        for (int i = 0; i < args.length; i++) {
            String name = args[i];
            String value = null;
            int equals = name.indexOf('=');
            if (equals > 0) {
                value = name.substring(equals + 1);
                name = name.substring(0, equals);
            }
            if (name.equals("-h") || name.equals("--help")) {
                printUsage();
                return null;
            }
            if (name.equals("--generation-filter") && value == null) {
                config.generationFilter = true;
                continue;
            }
            if (value == null) {
                if (i + 1 >= args.length) {
                    System.err.println("Missing value for " + name);
                    printUsage();
                    System.exit(1);
                }
                value = args[++i];
            }
            try {
                switch (name) {
                    case "--namespace" -> config.namespace = value;
                    case "--keys" -> config.keys = Integer.parseInt(value);
                    case "--hot-fraction" -> config.hotFraction = Double.parseDouble(value);
                    case "--hot-traffic" -> config.hotTraffic = Double.parseDouble(value);
                    case "--rate" -> config.rate = Integer.parseInt(value);
                    case "--duration-sec" -> config.durationSec = Integer.parseInt(value);
                    case "--write-threads" -> config.writeThreads = Integer.parseInt(value);
                    case "--worker-threads" -> config.workerThreads = Integer.parseInt(value);
                    case "--rate-limit-ms" -> config.rateLimitMs = Long.parseLong(value);
                    case "--reconcile-work-ms" -> config.reconcileWorkMs = Long.parseLong(value);
                    case "--payload-size" -> config.payloadSize = Integer.parseInt(value);
                    case "--report-interval-sec" -> config.reportIntervalSec = Integer.parseInt(value);
                    case "--create-concurrency" -> config.createConcurrency = Integer.parseInt(value);
                    case "--drain-timeout-sec" -> config.drainTimeoutSec = Integer.parseInt(value);
                    case "--cleanup-namespace" -> config.cleanupNamespace = Boolean.parseBoolean(value);
                    case "--cleanup-crd" -> config.cleanupCrd = Boolean.parseBoolean(value);
                    case "--reconcile-mode" -> config.reconcileMode = value;
                    case "--child-churn" -> config.childChurn = Integer.parseInt(value);
                    case "--generation-filter" -> config.generationFilter = Boolean.parseBoolean(value);
                    default -> {
                        System.err.println("Unknown option: " + name);
                        printUsage();
                        System.exit(1);
                    }
                }
            } catch (NumberFormatException exception) {
                System.err.println("Invalid number for " + name + ": " + value);
                System.exit(1);
            }
        }
        if (config.keys < 1 || config.rate < 1 || config.durationSec < 1 || config.writeThreads < 1
                || config.workerThreads < 1) {
            System.err.println("keys, rate, duration-sec, write-threads and worker-threads must be >= 1");
            System.exit(1);
        }
        if (config.hotFraction < 0 || config.hotFraction > 1 || config.hotTraffic < 0 || config.hotTraffic > 1) {
            System.err.println("hot-fraction and hot-traffic must be within [0, 1]");
            System.exit(1);
        }
        if (!config.reconcileMode.equals("noop") && !config.reconcileMode.equals("crud")) {
            System.err.println("reconcile-mode must be 'noop' or 'crud'");
            System.exit(1);
        }
        if (config.childChurn < 0) {
            System.err.println("child-churn must be >= 0 (0 disables churn deletes)");
            System.exit(1);
        }
        return config;
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java -jar operator-stress-test.jar [options]
                  --namespace NAME          target namespace (default operator-stress)
                  --keys N                  number of StressTestResource instances (default 500)
                  --hot-fraction F          fraction of keys considered hot (default 0.05)
                  --hot-traffic F           fraction of updates targeting hot keys (default 0.8)
                  --rate N                  target update ops/sec in steady phase (default 500)
                  --duration-sec N          steady phase duration in seconds (default 60)
                  --write-threads N         load generator writer threads (default 8)
                  --worker-threads N        operator reconcile worker threads (default 4)
                  --rate-limit-ms N         framework per-key rate limit interval (default 0 = disabled)
                  --reconcile-work-ms N     simulated reconcile work per event (default 0)
                  --payload-size N          spec.payload string length in bytes (default 64)
                  --report-interval-sec N   report cadence in seconds (default 5)
                  --create-concurrency N    create-phase parallelism (default 16)
                  --drain-timeout-sec N     max wait for create-phase drain (default 120)
                  --cleanup-namespace B     delete namespace at the end (default true)
                  --cleanup-crd B           delete the CRD at the end (default false)
                  --reconcile-mode M        'noop' (metrics only, default) or 'crud' (manage a child
                                            ConfigMap per CR: query, create, update, churn delete,
                                            status writeback against the API server)
                  --child-churn N           crud mode: delete the child ConfigMap once per key every Nth
                                            seq (default 50, 0 disables)
                  --generation-filter [B]   drop same-generation update events (status echoes) at the
                                            source (default false; bare flag means true)
                """);
    }

    int hotKeyCount() {
        return Math.max(1, (int) Math.round(keys * hotFraction));
    }

    @Override
    public String toString() {
        return "namespace=" + namespace
                + ", keys=" + keys
                + ", hotKeys=" + hotKeyCount() + " (traffic " + (long) (hotTraffic * 100) + "%)"
                + ", rate=" + rate + "/s"
                + ", duration=" + durationSec + "s"
                + ", writeThreads=" + writeThreads
                + ", workerThreads=" + workerThreads
                + ", rateLimitMs=" + rateLimitMs
                + ", reconcileWorkMs=" + reconcileWorkMs
                + ", payloadSize=" + payloadSize
                + ", reconcileMode=" + reconcileMode
                + (reconcileMode.equals("crud") ? ", childChurn=" + childChurn : "")
                + (generationFilter ? ", generationFilter=true" : "");
    }
}
