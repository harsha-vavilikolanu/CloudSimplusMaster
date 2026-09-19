package org.cloudsimplus.examples;

import org.cloudsimplus.allocationpolicies.VmAllocationPolicySimple;
import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Cloud Load Balancing using Reinforcement Learning.
 *
 * TEST CONFIGURATION
 * ------------------
 * Hosts       : 20
 * VMs         : 100
 * Cloudlets   : 1000
 * Runs        : 1
 *
 * Algorithms:
 *   FCFS
 *   Round Robin
 *   Max-Min
 *   Proposed DQN
 *
 * Metrics:
 *   Makespan
 *   Throughput
 *   ARUR
 *   Degree of Imbalance
 *   Energy
 *   Cost
 *
 * Once this configuration completes successfully,
 * restore the final 200/400/600/800/1000 x 25-run benchmark.
 */
public class ExperimentRLMOTSLoadBalancing {

    /* =========================================================
       EXPERIMENT SIZE
       ========================================================= */

    private static final int NUM_HOSTS = 10;

    private static final int NUM_VMS = 20;

    /*
     * Temporary validation run.
     *
     * Final paper benchmark:
     * 200, 400, 600, 800, 1000
     */
    private static final int[] TASK_SCENARIOS = {
    		1000
    };

    private static final int DEFAULT_RUNS = 1;

    private static final long BASE_SEED = 42_000L;

    /* =========================================================
       POWER MODEL
       ========================================================= */

    private static final double POWER_IDLE = 175.0;

    private static final double POWER_MAX = 375.0;

    /* =========================================================
       COST MODEL
       ========================================================= */

    private static final double COST_PER_CPU_SEC = 0.03;

    private static final double COST_PER_RAM_MB_SEC = 0.0005;

    /* =========================================================
       MAIN
       ========================================================= */

    public static void main(String[] args)
            throws IOException {

        final int runs = parseRuns(args);

        System.out.println();
        System.out.println(
                "=============================================================="
        );
        System.out.println(
                " CLOUD LOAD BALANCING USING REINFORCEMENT LEARNING"
        );
        System.out.println(
                "=============================================================="
        );

        System.out.println(
                "CloudSim Plus 8.5.7"
        );

        System.out.println(
                "Hosts       : " + NUM_HOSTS
        );

        System.out.println(
                "VMs         : " + NUM_VMS
        );

        System.out.println(
                "Tasks       : " +
                        formatTaskScenarios()
        );

        System.out.println(
                "Runs        : " + runs
        );

        System.out.println(
                "Algorithms  : FCFS | Round Robin | Max-Min | Proposed DQN"
        );

        System.out.println(
                "=============================================================="
        );

        final Map<String, MetricAccumulator> aggregate =
                new LinkedHashMap<>();

        final Path resultsDir =
                Paths.get("results");

        Files.createDirectories(
                resultsDir
        );

        final Path csvPath =
                resultsDir.resolve(
                        "rlmots_results.csv"
                );

        try (BufferedWriter writer =
                     Files.newBufferedWriter(csvPath)) {

            writer.write(
                    "tasks,run,algorithm,makespan_s," +
                    "throughput,arur,degree_of_imbalance," +
                    "energy_kj,cost"
            );

            writer.newLine();

            for (int taskCount :
                    TASK_SCENARIOS) {

                System.out.println();
                System.out.println(
                        "--------------------------------------------------------------"
                );
                System.out.println(
                        "WORKLOAD SCENARIO: "
                                + taskCount
                                + " CLOUDLETS"
                );
                System.out.println(
                        "--------------------------------------------------------------"
                );

                for (int run = 1;
                     run <= runs;
                     run++) {

                    final long workloadSeed =
                            BASE_SEED
                                    + (long) taskCount
                                    * 10_000L
                                    + run;

                    System.out.println(
                            "Run " +
                                    run +
                                    "/" +
                                    runs
                    );

                    final String[] algorithms = {
                            "FCFS",
                            "RoundRobin",
                            "MaxMin",
                            "Proposed_DQN"
                    };

                    for (String algorithm :
                            algorithms) {

                        System.out.println(
                                "  Starting " +
                                        algorithm +
                                        "..."
                        );

                        final long agentSeed =
                                workloadSeed
                                        + 900_000L;

                        final long wallStart =
                                System.currentTimeMillis();

                        final Result result =
                                runSimulationForScenario(
                                        algorithm,
                                        taskCount,
                                        workloadSeed,
                                        agentSeed
                                );

                        final long wallTime =
                                System.currentTimeMillis()
                                        - wallStart;

                        if (!result.valid()) {

                            System.out.printf(
                                    "  %-14s INVALID (%d/%d) " +
                                    "[%d ms]%n",

                                    algorithm,
                                    result.finishedCount(),
                                    taskCount,
                                    wallTime
                            );

                            continue;
                        }

                        System.out.printf(
                                "  %-14s " +
                                "makespan=%8.4f s " +
                                "throughput=%10.4f " +
                                "ARUR=%8.4f " +
                                "DI=%8.4f " +
                                "energy=%10.4f kJ " +
                                "cost=%10.6f " +
                                "[%d ms]%n",

                                algorithm,
                                result.makespan(),
                                result.throughput(),
                                result.averageUtilization(),
                                result.degreeOfImbalance(),
                                result.energy(),
                                result.cost(),
                                wallTime
                        );

                        writer.write(
                                String.format(
                                        "%d,%d,%s,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f",
                                        taskCount,
                                        run,
                                        algorithm,
                                        result.makespan(),
                                        result.throughput(),
                                        result.averageUtilization(),
                                        result.degreeOfImbalance(),
                                        result.energy(),
                                        result.cost()
                                )
                        );

                        writer.newLine();
                        writer.flush();

                        final String key =
                                taskCount +
                                        "-" +
                                        algorithm;

                        aggregate
                                .computeIfAbsent(
                                        key,
                                        ignored ->
                                                new MetricAccumulator()
                                )
                                .add(result);
                    }
                }
            }
        }

        printSummary(
                aggregate
        );

        System.out.println();
        System.out.println(
                "Raw results saved to:"
        );

        System.out.println(
                csvPath.toAbsolutePath()
        );

        System.out.println();
        System.out.println(
                "=============================================================="
        );
        System.out.println(
                " EXPERIMENT FINISHED"
        );
        System.out.println(
                "=============================================================="
        );
    }

    /* =========================================================
       FORMAT TASK SCENARIOS
       ========================================================= */

    private static String formatTaskScenarios() {

        StringBuilder builder =
                new StringBuilder();

        for (int i = 0;
             i < TASK_SCENARIOS.length;
             i++) {

            if (i > 0) {
                builder.append(", ");
            }

            builder.append(
                    TASK_SCENARIOS[i]
            );
        }

        return builder.toString();
    }

    /* =========================================================
       RUN COUNT
       ========================================================= */

    private static int parseRuns(
            String[] args) {

        if (args.length == 0) {
            return DEFAULT_RUNS;
        }

        try {

            final int runs =
                    Integer.parseInt(
                            args[0].trim()
                    );

            if (runs <= 0) {
                throw new NumberFormatException();
            }

            return runs;

        } catch (NumberFormatException ex) {

            throw new IllegalArgumentException(
                    "First argument must be a positive integer.",
                    ex
            );
        }
    }

    /* =========================================================
       RUN ONE SIMULATION
       ========================================================= */

    private static Result
    runSimulationForScenario(
            String algorithm,
            int numTasks,
            long workloadSeed,
            long agentSeed) {

        /*
         * Completely independent simulation.
         */
        final CloudSimPlus simulation =
                new CloudSimPlus();

        createDatacenter(
                simulation
        );

        final DatacenterBroker broker;

        /* =====================================================
           DQN
           ===================================================== */

        if ("Proposed_DQN"
                .equalsIgnoreCase(algorithm)) {

            final DqnAgent agent =
                    new DqnAgent(
                            DqnState.STATE_DIM,
                            AdvancedDqnBroker.CANDIDATE_COUNT,
                            agentSeed
                    );

            broker =
                    new AdvancedDqnBroker(
                            simulation,
                            "DqnBroker",
                            agent
                    );

        }

        /* =====================================================
           ROUND ROBIN
           ===================================================== */

        else if ("RoundRobin"
                .equalsIgnoreCase(algorithm)) {

            broker =
                    new BaselineBrokers.RoundRobinBroker(
                            simulation,
                            "RoundRobinBroker"
                    );
        }

        /* =====================================================
           MAX-MIN
           ===================================================== */

        else if ("MaxMin"
                .equalsIgnoreCase(algorithm)) {

            broker =
                    new BaselineBrokers.MaxMinBroker(
                            simulation,
                            "MaxMinBroker"
                    );
        }

        /* =====================================================
           FCFS
           ===================================================== */

        else {

            broker =
                    new BaselineBrokers.FcfsBroker(
                            simulation,
                            "FcfsBroker"
                    );
        }

        /*
         * Keep broker alive.
         */
        broker.setShutdownWhenIdle(
                false
        );

        /*
         * Disable automatic VM destruction.
         */
        broker.setVmDestructionDelay(
                -1
        );

        /*
         * Create VMs.
         */
        final List<Vm> vmList =
                createHeterogeneousVms();

        /*
         * Create Cloudlets.
         */
        final List<Cloudlet> cloudletList =
                createDynamicCloudlets(
                        numTasks,
                        workloadSeed
                );

        /*
         * Enable statistics.
         */
        for (Vm vm :
                vmList) {

            vm.enableUtilizationStats();
        }

        /*
         * Submit VMs.
         */
        broker.submitVmList(
                vmList
        );

        /*
         * Submit Cloudlets.
         */
        broker.submitCloudletList(
                cloudletList
        );

        /*
         * Start simulation.
         */
        simulation.start();

        /*
         * Collect completed Cloudlets.
         */
        final List<Cloudlet>
                finishedCloudlets =
                broker.getCloudletFinishedList();

        final int finishedCount =
                finishedCloudlets.size();

        /*
         * Do not use partial results.
         */
        if (finishedCount != numTasks) {

            return Result.invalid(
                    finishedCount
            );
        }

        /*
         * Metrics.
         */
        final double makespan =
                calculateMakespan(
                        finishedCloudlets
                );

        final double throughput =
                makespan > 0.0
                        ? finishedCount / makespan
                        : 0.0;

        final UtilizationMetrics
                utilization =
                calculateUtilizationMetrics(
                        vmList
                );

        final double energy =
                calculateEnergy(
                        utilization.averageUtilization(),
                        makespan
                );

        final double cost =
                calculateCost(
                        utilization.averageUtilization(),
                        makespan
                );

        return new Result(
                true,
                finishedCount,
                makespan,
                throughput,
                utilization.averageUtilization(),
                utilization.degreeOfImbalance(),
                energy,
                cost
        );
    }

    /* =========================================================
       DATACENTER
       ========================================================= */

    private static Datacenter
    createDatacenter(
            CloudSimPlus simulation) {

        final List<Host> hostList =
                new ArrayList<>();

        for (int hostId = 0;
             hostId < NUM_HOSTS;
             hostId++) {

            /*
             * 8 PEs per host.
             */
            final int numberOfPes =
                    8;

            /*
             * 10,000 MIPS per PE.
             */
            final long mipsPerPe =
                    10_000L;

            final List<Pe> peList =
                    new ArrayList<>(
                            numberOfPes
                    );

            for (int peId = 0;
                 peId < numberOfPes;
                 peId++) {

                peList.add(
                        new PeSimple(
                                mipsPerPe
                        )
                );
            }

            /*
             * Host resources.
             */
            final long hostRam =
                    64L * 1024L;

            final long hostBw =
                    1_000_000L;

            final long hostStorage =
                    1_000_000L;

            final Host host =
                    new HostSimple(
                            hostRam,
                            hostBw,
                            hostStorage,
                            peList
                    );

            hostList.add(
                    host
            );
        }

        final Datacenter datacenter =
                new DatacenterSimple(
                        simulation,
                        hostList,
                        new VmAllocationPolicySimple()
                );

        /*
         * Keep scheduling interval reasonably small.
         */
        datacenter.setSchedulingInterval(
                1.0
        );

        return datacenter;
    }

    /* =========================================================
       VMS
       ========================================================= */

    private static List<Vm>
    createHeterogeneousVms() {

        final List<Vm> vmList =
                new ArrayList<>(
                        NUM_VMS
                );

        for (int vmId = 0;
             vmId < NUM_VMS;
             vmId++) {

            final int tier =
                    vmId % 4;

            final long mips;
            final int pes;

            switch (tier) {

                case 0 -> {
                    /*
                     * High tier.
                     */
                    mips = 5_000L;
                    pes = 2;
                }

                case 1 -> {
                    /*
                     * Medium-high tier.
                     */
                    mips = 4_000L;
                    pes = 1;
                }

                case 2 -> {
                    /*
                     * Medium tier.
                     */
                    mips = 3_000L;
                    pes = 1;
                }

                default -> {
                    /*
                     * Low tier.
                     */
                    mips = 2_000L;
                    pes = 1;
                }
            }

            final Vm vm =
                    new VmSimple(
                            vmId,
                            mips,
                            pes
                    );

            /*
             * 8 GB RAM.
             */
            vm.setRam(
                    8L * 1024L
            );

            /*
             * 50,000 Mbps bandwidth.
             */
            vm.setBw(
                    50_000L
            );

            vm.setSize(
                    10_000L
            );

            vmList.add(
                    vm
            );
        }

        return vmList;
    }

    /* =========================================================
       CLOUDLETS
       ========================================================= */

    private static List<Cloudlet>
    createDynamicCloudlets(
            int numTasks,
            long seed) {

        final List<Cloudlet>
                cloudletList =
                new ArrayList<>(
                        numTasks
                );

        final Random random =
                new Random(seed);

        /*
         * CPU utilization.
         */
        final UtilizationModelDynamic
                cpuUtilization =
                new UtilizationModelDynamic(
                        0.8
                );

        /*
         * RAM utilization = 0%.
         */
        final UtilizationModelDynamic
                ramUtilization =
                new UtilizationModelDynamic(
                        0.0
                );

        /*
         * BW utilization = 0%.
         */
        final UtilizationModelDynamic
                bwUtilization =
                new UtilizationModelDynamic(
                        0.0
                );

        for (int i = 0;
             i < numTasks;
             i++) {

            /*
             * 1,000 - 5,000 MI.
             */
            final long length =
                    1_000L
                            + (long) (
                                    random.nextDouble()
                                            * 4_001L
                            );

            final Cloudlet cloudlet =
                    new CloudletSimple(
                            i,
                            length,
                            1
                    );

            cloudlet.setUtilizationModelCpu(
                    cpuUtilization
            );

            cloudlet.setUtilizationModelRam(
                    ramUtilization
            );

            cloudlet.setUtilizationModelBw(
                    bwUtilization
            );

            cloudletList.add(
                    cloudlet
            );
        }

        return cloudletList;
    }

    /* =========================================================
       MAKESPAN
       ========================================================= */

    private static double
    calculateMakespan(
            List<Cloudlet> cloudlets) {

        double makespan = 0.0;

        for (Cloudlet cloudlet :
                cloudlets) {

            makespan =
                    Math.max(
                            makespan,
                            cloudlet.getFinishTime()
                    );
        }

        return makespan;
    }

    /* =========================================================
       ARUR / DI
       ========================================================= */

    private static UtilizationMetrics
    calculateUtilizationMetrics(
            List<Vm> vmList) {

        double totalUtilization =
                0.0;

        double maxUtilization =
                0.0;

        double minUtilization =
                1.0;

        int count = 0;

        for (Vm vm :
                vmList) {

            double utilization;

            try {

                utilization =
                        vm.getCpuUtilizationStats()
                                .getMean();

            } catch (Exception ignored) {

                utilization =
                        vm.getCpuPercentUtilization();
            }

            if (Double.isNaN(
                    utilization)
                    ||
                    Double.isInfinite(
                            utilization)) {

                utilization =
                        0.0;
            }

            utilization =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    utilization
                            )
                    );

            totalUtilization +=
                    utilization;

            maxUtilization =
                    Math.max(
                            maxUtilization,
                            utilization
                    );

            minUtilization =
                    Math.min(
                            minUtilization,
                            utilization
                    );

            count++;
        }

        if (count == 0) {

            return new UtilizationMetrics(
                    0.0,
                    0.0
            );
        }

        final double averageUtilization =
                totalUtilization
                        / count;

        final double degreeOfImbalance =
                averageUtilization > 0.0
                        ?
                        (
                                (
                                        maxUtilization
                                                - minUtilization
                                )
                                / averageUtilization
                        )
                        :
                        0.0;

        return new UtilizationMetrics(
                averageUtilization,
                degreeOfImbalance
        );
    }

    /* =========================================================
       ENERGY
       ========================================================= */

    private static double
    calculateEnergy(
            double arur,
            double makespan) {

        if (makespan <= 0.0) {
            return 0.0;
        }

        final double averagePower =
                POWER_IDLE
                        +
                        (
                                POWER_MAX
                                        - POWER_IDLE
                        ) * arur;

        return averagePower
                * makespan
                / 1000.0;
    }

    /* =========================================================
       COST
       ========================================================= */

    private static double
    calculateCost(
            double arur,
            double makespan) {

        if (makespan <= 0.0) {
            return 0.0;
        }

        final double cpuCostRate =
                arur
                        * COST_PER_CPU_SEC;

        final double ramCostRate =
                COST_PER_RAM_MB_SEC
                        * 8192.0;

        return (
                cpuCostRate
                        + ramCostRate
        )
                * makespan
                * NUM_VMS;
    }

    /* =========================================================
       SUMMARY
       ========================================================= */

    private static void
    printSummary(
            Map<String, MetricAccumulator>
                    aggregate) {

        System.out.println();
        System.out.println(
                "=============================================================="
        );

        System.out.println(
                "RESULTS"
        );

        System.out.println(
                "=============================================================="
        );

        System.out.printf(
                "%-8s | %-14s | %12s | %12s | " +
                "%10s | %10s | %12s | %10s%n",

                "Tasks",
                "Algorithm",
                "Makespan",
                "Throughput",
                "ARUR",
                "DI",
                "Energy(kJ)",
                "Cost"
        );

        System.out.println(
                "--------------------------------------------------------------------------"
        );

        for (Map.Entry<String,
                MetricAccumulator> entry :
                aggregate.entrySet()) {

            final String[] parts =
                    entry.getKey()
                            .split(
                                    "-",
                                    2
                            );

            final int tasks =
                    Integer.parseInt(
                            parts[0]
                    );

            final MetricAccumulator
                    accumulator =
                    entry.getValue();

            System.out.printf(
                    "%-8d | %-14s | " +
                    "%12.4f | " +
                    "%12.4f | " +
                    "%10.4f | " +
                    "%10.4f | " +
                    "%12.4f | " +
                    "%10.6f%n",

                    tasks,
                    parts[1],
                    accumulator.meanMakespan(),
                    accumulator.meanThroughput(),
                    accumulator.meanArur(),
                    accumulator.meanDi(),
                    accumulator.meanEnergy(),
                    accumulator.meanCost()
            );
        }

        System.out.println(
                "=============================================================="
        );
    }

    /* =========================================================
       RESULT
       ========================================================= */

    private record Result(
            boolean valid,
            int finishedCount,
            double makespan,
            double throughput,
            double averageUtilization,
            double degreeOfImbalance,
            double energy,
            double cost) {

        static Result invalid(
                int finishedCount) {

            return new Result(
                    false,
                    finishedCount,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0
            );
        }
    }

    /* =========================================================
       UTILIZATION
       ========================================================= */

    private record UtilizationMetrics(
            double averageUtilization,
            double degreeOfImbalance) {
    }

    /* =========================================================
       ACCUMULATOR
       ========================================================= */

    private static class MetricAccumulator {

        private final List<Double>
                makespans =
                new ArrayList<>();

        private final List<Double>
                throughputs =
                new ArrayList<>();

        private final List<Double>
                arurs =
                new ArrayList<>();

        private final List<Double>
                dis =
                new ArrayList<>();

        private final List<Double>
                energies =
                new ArrayList<>();

        private final List<Double>
                costs =
                new ArrayList<>();

        void add(Result result) {

            makespans.add(
                    result.makespan()
            );

            throughputs.add(
                    result.throughput()
            );

            arurs.add(
                    result.averageUtilization()
            );

            dis.add(
                    result.degreeOfImbalance()
            );

            energies.add(
                    result.energy()
            );

            costs.add(
                    result.cost()
            );
        }

        double meanMakespan() {
            return mean(makespans);
        }

        double meanThroughput() {
            return mean(throughputs);
        }

        double meanArur() {
            return mean(arurs);
        }

        double meanDi() {
            return mean(dis);
        }

        double meanEnergy() {
            return mean(energies);
        }

        double meanCost() {
            return mean(costs);
        }

        private static double mean(
                List<Double> values) {

            if (values.isEmpty()) {
                return 0.0;
            }

            double sum = 0.0;

            for (double value :
                    values) {

                sum += value;
            }

            return sum /
                    values.size();
        }
    }
}