package org.fog.test.perfeval;

import java.util.*;

public class AdaptiveOffloadingSimulation {

    // ─────────────────────────────────────────────
    //  SCALE CONTROL  ← change these two values
    // ─────────────────────────────────────────────

    /** Total number of IoT nodes in the network */
    static final int NUM_NODES    = 90;


    static final int HOTSPOT_STEP = 5;

    // ─────────────────────────────────────────────
    //  ALGORITHM PARAMETERS
    // ─────────────────────────────────────────────

    static final double OFFLOAD_THRESHOLD  = 0.80;
    static final double EWMA_ALPHA         = 0.40;

    /**
     * Only offload when queue depth >= QUEUE_GATE.
     * Prevents false-alarm offloading when EWMA is high but node is free.
     */
    static final int    QUEUE_GATE         = 1;

    /**
     * Time window for assignment-pressure tracking (ms).
     * Tasks offloaded to a neighbor within this window add to its pressure score.
     */
    static final double PRESSURE_WINDOW_MS = 400.0;

    /** Tasks in window that count as "fully pressured" */
    static final double MAX_RECENT         = 8.0;

    // ─────────────────────────────────────────────
    //  TASK & NETWORK PARAMETERS
    // ─────────────────────────────────────────────

    static final int    TASK_MI_MIN        = 500;
    static final int    TASK_MI_MAX        = 2000;
    static final int    MAX_QUEUE          = 8;
    static final double SIM_DURATION_MS    = 10_000;
    static final double TX_DELAY_MS        = 5.0;

    // ─────────────────────────────────────────────
    //  NODE SPECS (generated, not hardcoded)
    // ─────────────────────────────────────────────

    /** MIPS per node — generated based on role */
    static final Map<String, Integer> NODE_MIPS        = new LinkedHashMap<>();
    /** Mean task inter-arrival time per node (ms) */
    static final Map<String, Double>  ARRIVAL_INTERVAL = new LinkedHashMap<>();
    /** One-hop neighbor map — ring + chord topology */
    static final Map<String, List<String>> NEIGHBOR_MAP = new LinkedHashMap<>();

    static {
        // Use a fixed seed so node specs are deterministic across all strategy runs
        Random specRng = new Random(7);
        for (int i = 0; i < NUM_NODES; i++) {
            String name = nodeName(i);
            if (i % HOTSPOT_STEP == 0) {
                // Hotspot: slow CPU, fast arrivals → builds backlog → triggers offloading
                NODE_MIPS.put(name, 4500 + specRng.nextInt(1001));     // 4500–5500 MIPS
                ARRIVAL_INTERVAL.put(name, 160.0);
            } else {
                // Light: fast CPU, slow arrivals → spare capacity for offloaded tasks
                NODE_MIPS.put(name, 11000 + specRng.nextInt(3001));    // 11000–14000 MIPS
                ARRIVAL_INTERVAL.put(name, 700.0 + specRng.nextDouble() * 300.0);
            }
        }

        // Build ring + chord topology
        for (int i = 0; i < NUM_NODES; i++) {
            Set<Integer> nbSet = new TreeSet<>();
            for (int skip : new int[]{1, 2, 3}) {
                nbSet.add((i + skip + NUM_NODES) % NUM_NODES);
                nbSet.add((i - skip + NUM_NODES) % NUM_NODES);
            }
            nbSet.remove(i); // never self-neighbor
            List<String> nbList = new ArrayList<>();
            for (int j : nbSet) nbList.add(nodeName(j));
            NEIGHBOR_MAP.put(nodeName(i), nbList);
        }
    }

    static String nodeName(int i) { return String.format("Node%02d", i); }

    enum Strategy { LOCAL_ONLY, RANDOM, ADAPTIVE }

    // ─────────────────────────────────────────────
    //  MAIN
    // ─────────────────────────────────────────────

    public static void main(String[] args) {
        System.out.println("=======================================================");
        System.out.println("  One-Hop Adaptive Task Offloading Simulation  v9");
        System.out.printf ("  %d nodes  |  %d hotspots  |  %d light nodes%n",
            NUM_NODES,
            NUM_NODES / HOTSPOT_STEP,
            NUM_NODES - NUM_NODES / HOTSPOT_STEP);
        System.out.println("=======================================================\n");
        printTopologySummary();

        Map<Strategy, SimResult> results = new EnumMap<>(Strategy.class);
        for (Strategy s : Strategy.values()) {
            System.out.println("--- Running strategy: " + s + " ---");
            SimResult r = runSimulation(s);
            results.put(s, r);
            r.print();
            System.out.println();
        }
        printComparison(results);
    }

    // ─────────────────────────────────────────────
    //  SIMULATION ENGINE
    // ─────────────────────────────────────────────

    static SimResult runSimulation(Strategy strategy) {
        List<String> nodeNames = new ArrayList<>(NODE_MIPS.keySet());
        int N = nodeNames.size();

        Map<String, Double>                ewmaLoad     = new HashMap<>();
        Map<String, Double>                lastArrival  = new HashMap<>();
        Map<String, Double>                nextFreeTime = new HashMap<>();
        Map<String, PriorityQueue<Double>> taskHeap     = new HashMap<>();
        Map<String, List<Double>>          recentAssign = new HashMap<>();

        for (String n : nodeNames) {
            ewmaLoad.put(n, 0.0);
            lastArrival.put(n, 0.0);
            nextFreeTime.put(n, 0.0);
            taskHeap.put(n, new PriorityQueue<>());
            recentAssign.put(n, new ArrayList<>());
        }

        SimResult result = new SimResult(N);
        Random rng = new Random(42); // fixed seed: same tasks across strategies

        List<TaskEvent> events = generateTaskEvents(rng);
        events.sort(Comparator.comparingDouble(t -> t.arrivalTime));

        for (TaskEvent task : events) {
            double now    = task.arrivalTime;
            String origin = task.originNode;

            result.totalTasks++;

            // Drain completed tasks from all heaps
            for (String n : nodeNames)
                drainCompleted(taskHeap.get(n), now);

            // Update EWMA load for origin node
            double dt      = now - lastArrival.get(origin);
            double execEst = (task.mi / (double) NODE_MIPS.get(origin)) * 1000.0;
            double instant = Math.min(1.0, dt > 0 ? execEst / dt : 1.0);
            double myLoad  = EWMA_ALPHA * instant + (1 - EWMA_ALPHA) * ewmaLoad.get(origin);
            ewmaLoad.put(origin, myLoad);
            lastArrival.put(origin, now);

            String executor = decide(origin, strategy, now, myLoad,
                                     taskHeap, ewmaLoad, recentAssign, rng);

            if (executor == null) { result.droppedTasks++; continue; }

            if (!executor.equals(origin))
                recentAssign.get(executor).add(now);

            double txDelay    = executor.equals(origin) ? 0.0 : TX_DELAY_MS;
            double startTime  = Math.max(now + txDelay, nextFreeTime.get(executor));
            double computeMs  = (task.mi / (double) NODE_MIPS.get(executor)) * 1000.0;
            double finishTime = startTime + computeMs;

            nextFreeTime.put(executor, finishTime);
            taskHeap.get(executor).add(finishTime);

            result.totalLatency += (finishTime - now);
            result.nodeTaskCounts[nodeNames.indexOf(executor)]++;
            result.nodeBusyMs[nodeNames.indexOf(executor)] += computeMs;
            if (!executor.equals(origin)) result.offloadedExecutions++;
        }

        result.computeLoadVariance();
        return result;
    }

    // ─────────────────────────────────────────────
    //  DECISION ALGORITHM
    // ─────────────────────────────────────────────

    static String decide(String origin, Strategy strategy,
                         double now, double myLoad,
                         Map<String, PriorityQueue<Double>> taskHeap,
                         Map<String, Double>     ewmaLoad,
                         Map<String, List<Double>> recentAssign,
                         Random rng) {

        int myQueue = taskHeap.get(origin).size();

        switch (strategy) {

            // ── Baseline 1: Always local ───────────────────────────────
            case LOCAL_ONLY:
                return myQueue < MAX_QUEUE ? origin : null;

            // ── Baseline 2: Random offload when overloaded ─────────────
            case RANDOM: {
                if (myLoad > OFFLOAD_THRESHOLD) {
                    List<String> cands = new ArrayList<>();
                    for (String nb : NEIGHBOR_MAP.get(origin))
                        if (taskHeap.get(nb).size() < MAX_QUEUE) cands.add(nb);
                    if (!cands.isEmpty())
                        return cands.get(rng.nextInt(cands.size()));
                }
                return myQueue < MAX_QUEUE ? origin : null;
            }

            // ── Proposed: Adaptive one-hop offloading ──────────────────
            case ADAPTIVE: {
                /*
                 * ALGORITHM:
                 *
                 * Dual-condition gate — offload only when:
                 *   (1) ewmaLoad > OFFLOAD_THRESHOLD  (overloaded)
                 *   (2) queueDepth >= QUEUE_GATE       (confirmed, not false alarm)
                 *
                 * Neighbor score = 0.6 * ewmaLoad(nb)
                 *                + 0.4 * assignmentPressure(nb)
                 *
                 * assignmentPressure = min(1, recent_offloads / MAX_RECENT)
                 * where recent = tasks offloaded in last PRESSURE_WINDOW_MS ms
                 *
                 * This prevents funneling: fast nodes that look idle via EWMA
                 * are penalised if they recently received many tasks.
                 *
                 * One-hop only. O(degree) = O(6) per decision.
                 */
                boolean overloaded = myLoad > OFFLOAD_THRESHOLD;
                boolean queuing    = myQueue >= QUEUE_GATE;

                if (!overloaded || !queuing)
                    return myQueue < MAX_QUEUE ? origin : null;

                // Genuine congestion — scan one-hop neighbors
                String bestNeighbor = null;
                double bestScore    = myLoad; // only offload if strictly better

                for (String nb : NEIGHBOR_MAP.get(origin)) {
                    if (taskHeap.get(nb).size() >= MAX_QUEUE) continue;
                    recentAssign.get(nb).removeIf(t -> now - t > PRESSURE_WINDOW_MS);
                    double pressure = Math.min(1.0,
                        recentAssign.get(nb).size() / MAX_RECENT);
                    double score = 0.6 * ewmaLoad.get(nb) + 0.4 * pressure;
                    if (score < bestScore) { bestScore = score; bestNeighbor = nb; }
                }

                if (bestNeighbor != null) return bestNeighbor;
                return myQueue < MAX_QUEUE ? origin : null;
            }

            default: return origin;
        }
    }

    // ─────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────

    static void drainCompleted(PriorityQueue<Double> heap, double now) {
        while (!heap.isEmpty() && heap.peek() <= now) heap.poll();
    }

    static List<TaskEvent> generateTaskEvents(Random rng) {
        List<TaskEvent> events = new ArrayList<>();
        for (Map.Entry<String, Double> entry : ARRIVAL_INTERVAL.entrySet()) {
            String node     = entry.getKey();
            double interval = entry.getValue();
            double time     = rng.nextDouble() * interval;
            while (time < SIM_DURATION_MS) {
                int mi = TASK_MI_MIN + rng.nextInt(TASK_MI_MAX - TASK_MI_MIN + 1);
                events.add(new TaskEvent(node, time, mi));
                double jitter = rng.nextGaussian() * interval * 0.10;
                time += Math.max(interval * 0.5, interval + jitter);
            }
        }
        return events;
    }

    // ─────────────────────────────────────────────
    //  OUTPUT
    // ─────────────────────────────────────────────

    static void printTopologySummary() {
        System.out.println("Topology: Ring + Chord (biconnected, 6 neighbors per node)");
        System.out.printf ("Placement: hotspot every %d nodes (positions 0,%d,%d,...)\n\n",
            HOTSPOT_STEP, HOTSPOT_STEP, HOTSPOT_STEP*2);

        // Print first few hotspot and light nodes as examples
        System.out.println("Sample node specs:");
        System.out.printf("  %-8s %7s %14s %12s  %s%n",
            "Node", "MIPS", "Arrival (ms)", "Avg exec ms", "Role");
        System.out.println("  " + "-".repeat(68));
        int printed = 0;
        for (String n : NODE_MIPS.keySet()) {
            if (printed >= 8) { System.out.println("  ... (+" + (NUM_NODES-8) + " more nodes)"); break; }
            int    mips    = NODE_MIPS.get(n);
            double intv    = ARRIVAL_INTERVAL.get(n);
            double avgExec = 1250.0 / mips * 1000.0;
            boolean isHot  = intv < 300;
            System.out.printf("  %-8s %7d %14.0f %12.1f  %s%n",
                n, mips, intv, avgExec,
                isHot ? "HOTSPOT  exec>arrival → overloads"
                      : "light    exec<<arrival → offload target");
            printed++;
        }

        int nHot   = NUM_NODES / HOTSPOT_STEP;
        int nLight = NUM_NODES - nHot;
        System.out.printf("%nNetwork: %d hotspots + %d light nodes%n", nHot, nLight);
        System.out.printf("EWMA α=%.2f | Threshold=%.0f%% | QueueGate=%d | " +
                          "PressureWin=%.0fms | MaxQ=%d | MI=[%d–%d] | Sim=%.0fs%n%n",
            EWMA_ALPHA, OFFLOAD_THRESHOLD*100, QUEUE_GATE,
            PRESSURE_WINDOW_MS, MAX_QUEUE,
            TASK_MI_MIN, TASK_MI_MAX, SIM_DURATION_MS/1000);
    }

    static void printComparison(Map<Strategy, SimResult> results) {
        System.out.println("=======================================================");
        System.out.println("                 STRATEGY COMPARISON");
        System.out.println("=======================================================");
        System.out.printf("%-14s %13s %11s %10s %11s%n",
            "Strategy", "Avg Latency", "Drop Rate", "Task Var", "Offload%");
        System.out.println("-------------------------------------------------------");
        for (Strategy s : Strategy.values()) {
            SimResult r = results.get(s);
            System.out.printf("%-14s %11.1f ms %10.2f%% %10.2f %10.2f%%%n",
                s, r.avgLatency(), r.dropRate(), r.loadVariance, r.offloadRate());
        }
        System.out.println("=======================================================");

        SimResult ad  = results.get(Strategy.ADAPTIVE);
        SimResult loc = results.get(Strategy.LOCAL_ONLY);
        SimResult ran = results.get(Strategy.RANDOM);

        System.out.println("\n✅ Adaptive vs Local-Only:");
        printImprov("Latency",   loc.avgLatency(), ad.avgLatency());
        printImprov("Drop rate", loc.dropRate(),    ad.dropRate());
        printImprov("Task var",  loc.loadVariance,  ad.loadVariance);

        System.out.println("\n✅ Adaptive vs Random:");
        printImprov("Latency",   ran.avgLatency(), ad.avgLatency());
        printImprov("Drop rate", ran.dropRate(),    ad.dropRate());
        printImprov("Task var",  ran.loadVariance,  ad.loadVariance);

        System.out.println("\n[+% = improvement | -% = worse]");
    }

    static void printImprov(String label, double base, double ours) {
        if (base == 0) {
            System.out.printf("   %-12s  n/a (baseline = 0)%n", label+":"); return;
        }
        double pct = (base - ours) / base * 100.0;
        String tag = pct > 1.0 ? "↑ better" : pct < -1.0 ? "↓ worse" : "≈ similar";
        System.out.printf("   %-12s %+6.1f%%  %s%n", label+":", pct, tag);
    }

    // ─────────────────────────────────────────────
    //  DATA CLASSES
    // ─────────────────────────────────────────────

    static class TaskEvent {
        final String originNode; final double arrivalTime; final int mi;
        TaskEvent(String o, double t, int m) { originNode=o; arrivalTime=t; mi=m; }
    }

    static class SimResult {
        int      totalTasks=0, droppedTasks=0, offloadedExecutions=0;
        double   totalLatency=0, loadVariance=0;
        int[]    nodeTaskCounts;
        double[] nodeBusyMs;

        SimResult(int n) { nodeTaskCounts=new int[n]; nodeBusyMs=new double[n]; }

        double avgLatency()  { int c=totalTasks-droppedTasks; return c>0?totalLatency/c:0; }
        double dropRate()    { return totalTasks>0?(double)droppedTasks/totalTasks*100:0; }
        double offloadRate() { int c=totalTasks-droppedTasks; return c>0?(double)offloadedExecutions/c*100:0; }

        void computeLoadVariance() {
            // Task COUNT variance (not busy-ms).
            // In heterogeneous networks, busy-ms is biased by node speed.
            // Task count variance correctly measures load distribution fairness.
            double mean = 0;
            for (int c : nodeTaskCounts) mean += c;
            mean /= nodeTaskCounts.length;
            double v = 0;
            for (int c : nodeTaskCounts) v += Math.pow(c - mean, 2);
            loadVariance = v / nodeTaskCounts.length;
        }

        void print() {
            // Summary stats only (90 nodes is too many to list individually)
            int min=Integer.MAX_VALUE, max=0; double sum=0;
            for (int c : nodeTaskCounts) {
                if (c<min) min=c; if (c>max) max=c; sum+=c;
            }
            double mean = sum / nodeTaskCounts.length;
            System.out.printf("  Total tasks    : %d%n", totalTasks);
            System.out.printf("  Dropped tasks  : %d  (%.2f%%)%n", droppedTasks, dropRate());
            System.out.printf("  Avg latency    : %.1f ms%n", avgLatency());
            System.out.printf("  Task variance  : %.2f%n", loadVariance);
            System.out.printf("  Offloaded      : %d  (%.2f%%)%n",
                offloadedExecutions, offloadRate());
            System.out.printf("  Task dist.     : min=%d  max=%d  mean=%.1f per node%n",
                min, max, mean);
        }
    }
}