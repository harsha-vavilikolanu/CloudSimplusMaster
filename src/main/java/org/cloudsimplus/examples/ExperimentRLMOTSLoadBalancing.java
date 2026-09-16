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
 * Paper-aligned experimental harness.
 *
 * It follows the comparison table in the supplied Word document:
 * CloudSim Plus 8.5.7, 100 heterogeneous VMs, 200/400/600/800/1000
 * cloudlets, DQN vs FCFS/Round Robin/Max-Min, and multiple independent runs.
 *
 * Run with an optional first argument to change the number of independent runs:
 *   mvnw -q exec:java -Dexec.mainClass=org.cloudsimplus.examples.ExperimentRLMOTSLoadBalancing -Dexec.args="25"
 */
public class ExperimentRLMOTSLoadBalancing {

    private static final int NUM_HOSTS = 20;
    private static final int NUM_VMS = 100;

    private static final int[] TASK_SCENARIOS = {
            200,
            400,
            600,
            800,
            1000
    };

    private static final int DEFAULT_RUNS = 25;
    private static final long BASE_SEED = 42_000L;

    private static final double POWER_IDLE = 175.0;
    private static final double POWER_MAX = 375.0;
    private static final double COST_PER_CPU_SEC = 0.03;
    private static final double COST_PER_RAM_MB_SEC = 0.0005;

    public static void main(String[] args) throws IOException {
        final int runs = parseRuns(args);

        System.out.println("==========================================================================");
        System.out.println(" CLOUD LOAD BALANCING USING REINFORCEMENT LEARNING");
        System.out.println(" CloudSim Plus 8.5.7 | 100 heterogeneous VMs | " + runs + " independent runs");
        System.out.println(" Algorithms: FCFS | Round Robin | Max-Min | Proposed DQN");
        System.out.println(" Metrics: Makespan | Throughput | ARUR | DI | Energy | Cost");
        System.out.println("==========================================================================");

        final Map<String, MetricAccumulator> aggregate = new LinkedHashMap<>();
        final Path resultsDir = Paths.get("results");
        Files.createDirectories(resultsDir);
        final Path csvPath = resultsDir.resolve("rlmots_results.csv");

        try (BufferedWriter writer = Files.newBufferedWriter(csvPath)) {
            writer.write("tasks,run,algorithm,makespan_s,throughput,arur,degree_of_imbalance,energy_kj,cost");
            writer.newLine();

            for (int taskCount : TASK_SCENARIOS) {
                System.out.println();
                System.out.println("--------------------------------------------------------------------------");
                System.out.println("WORKLOAD SCENARIO: " + taskCount + " CLOUDLETS");
                System.out.println("--------------------------------------------------------------------------");

                for (int run = 1; run <= runs; run++) {
                    final long workloadSeed = BASE_SEED
                            + (long) taskCount * 10_000L
                            + run;

                    System.out.printf("Run %d/%d%n", run, runs);

                    final String[] algorithms = {
                            "FCFS",
                            "RoundRobin",
                            "MaxMin",
                            "Proposed_DQN"
                    };

                    for (String algorithm : algorithms) {
                        final long agentSeed = workloadSeed + 900_000L;
                        final Result result = runSimulationForScenario(
                                algorithm,
                                taskCount,
                                workloadSeed,
                                agentSeed);

                        if (!result.valid()) {
                            System.out.printf(
                                    "  %-14s INVALID (%d/%d cloudlets)%n",
                                    algorithm,
                                    result.finishedCount(),
                                    taskCount);
                            continue;
                        }

                        System.out.printf(
                                "  %-14s makespan=%8.3f s  throughput=%10.6f  ARUR=%6.4f  DI=%6.4f  energy=%10.3f kJ  cost=%9.3f%n",
                                algorithm,
                                result.makespan(),
                                result.throughput(),
                                result.averageUtilization(),
                                result.degreeOfImbalance(),
                                result.energy(),
                                result.cost());

                        writer.write(String.format(
                                "%d,%d,%s,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f",
                                taskCount,
                                run,
                                algorithm,
                                result.makespan(),
                                result.throughput(),
                                result.averageUtilization(),
                                result.degreeOfImbalance(),
                                result.energy(),
                                result.cost()));
                        writer.newLine();
                        writer.flush();

                        final String key = taskCount + "-" + algorithm;
                        aggregate.computeIfAbsent(key, ignored -> new MetricAccumulator())
                                .add(result);
                    }
                }
            }
        }

        printSummary(aggregate);

        System.out.println();
        System.out.println("Raw per-run results saved to: " + csvPath.toAbsolutePath());
        System.out.println("Use these raw runs for statistical testing and plots; do not copy paper values into the experiment.");
        System.out.println("==========================================================================");
    }

    private static int parseRuns(String[] args) {
        if (args.length == 0) {
            return DEFAULT_RUNS;
        }

        try {
            final int runs = Integer.parseInt(args[0].trim());
            if (runs <= 0) {
                throw new NumberFormatException("runs must be positive");
            }
            return runs;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(
                    "First argument must be a positive integer run count, e.g. 25",
                    ex);
        }
    }

    private static Result runSimulationForScenario(
            String algorithm,
            int numTasks,
            long workloadSeed,
            long agentSeed) {

        final CloudSimPlus simulation = new CloudSimPlus();
        createDatacenter(simulation);

        final DatacenterBroker broker;

        if ("Proposed_DQN".equalsIgnoreCase(algorithm)) {
            final DqnAgent agent = new DqnAgent(
                    DqnState.STATE_DIM,
                    AdvancedDqnBroker.CANDIDATE_COUNT,
                    agentSeed);

            broker = new AdvancedDqnBroker(
                    simulation,
                    "DqnBroker",
                    agent);
        } else if ("RoundRobin".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.RoundRobinBroker(
                    simulation,
                    "RoundRobinBroker");
        } else if ("MaxMin".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.MaxMinBroker(
                    simulation,
                    "MaxMinBroker");
        } else {
            broker = new BaselineBrokers.FcfsBroker(
                    simulation,
                    "FcfsBroker");
        }

        broker.setShutdownWhenIdle(false);
        broker.setVmDestructionDelay(-1);

        final List<Vm> vmList = createHeterogeneousVms();
        final List<Cloudlet> cloudletList = createDynamicCloudlets(
                numTasks,
                workloadSeed);

        for (Vm vm : vmList) {
            vm.enableUtilizationStats();
        }

        broker.submitVmList(vmList);
        broker.submitCloudletList(cloudletList);
        simulation.start();

        final List<Cloudlet> finishedCloudlets = broker.getCloudletFinishedList();
        final int finishedCount = finishedCloudlets.size();

        if (finishedCount != numTasks) {
            return Result.invalid(finishedCount);
        }

        final double makespan = calculateMakespan(finishedCloudlets);
        final double throughput = makespan > 0.0
                ? finishedCount / makespan
                : 0.0;

        final UtilizationMetrics utilization = calculateUtilizationMetrics(vmList);
        final double energy = calculateEnergy(utilization.averageUtilization(), makespan);
        final double cost = calculateCost(utilization.averageUtilization(), makespan);

        return new Result(
                true,
                finishedCount,
                makespan,
                throughput,
                utilization.averageUtilization(),
                utilization.degreeOfImbalance(),
                energy,
                cost);
    }

    private static void printSummary(
            Map<String, MetricAccumulator> aggregate) {

        System.out.println();
        System.out.println("==========================================================================");
        System.out.println("AGGREGATED RESULTS (MEAN ± STD)");
        System.out.println("==========================================================================");
        System.out.printf(
                "%-8s | %-14s | %18s | %18s | %10s | %10s | %14s | %12s%n",
                "Tasks",
                "Algorithm",
                "Makespan(s)",
                "Throughput",
                "ARUR",
                "DI",
                "Energy(kJ)",
                "Cost($)");
        System.out.println("---------------------------------------------------------------------------------------------------------------");

        for (Map.Entry<String, MetricAccumulator> entry : aggregate.entrySet()) {
            final String[] parts = entry.getKey().split("-", 2);
            final int tasks = Integer.parseInt(parts[0]);
            final MetricAccumulator a = entry.getValue();

            System.out.printf(
                    "%-8d | %-14s | %8.3f ± %-7.3f | %8.6f ± %-7.6f | %10.4f | %10.4f | %7.3f ± %-7.3f | %6.3f ± %-5.3f%n",
                    tasks,
                    parts[1],
                    a.meanMakespan(),
                    a.stdMakespan(),
                    a.meanThroughput(),
                    a.stdThroughput(),
                    a.meanArur(),
                    a.meanDi(),
                    a.meanEnergy(),
                    a.stdEnergy(),
                    a.meanCost(),
                    a.stdCost());
        }

        System.out.println("==========================================================================");
    }

    private static double calculateMakespan(List<Cloudlet> cloudlets) {
        double makespan = 0.0;
        for (Cloudlet cloudlet : cloudlets) {
            makespan = Math.max(makespan, cloudlet.getFinishTime());
        }
        return makespan;
    }

    private static UtilizationMetrics calculateUtilizationMetrics(List<Vm> vmList) {
        double totalUtilization = 0.0;
        double maxUtilization = 0.0;
        double minUtilization = 1.0;

        int count = 0;
        for (Vm vm : vmList) {
            double utilization;

            try {
                utilization = vm.getCpuUtilizationStats().getMean();
            } catch (Exception ignored) {
                utilization = vm.getCpuPercentUtilization();
            }

            if (Double.isNaN(utilization) || Double.isInfinite(utilization)) {
                utilization = 0.0;
            }

            utilization = Math.max(0.0, Math.min(1.0, utilization));

            totalUtilization += utilization;
            maxUtilization = Math.max(maxUtilization, utilization);
            minUtilization = Math.min(minUtilization, utilization);
            count++;
        }

        if (count == 0) {
            return new UtilizationMetrics(0.0, 0.0);
        }

        final double averageUtilization = totalUtilization / count;
        final double degreeOfImbalance = averageUtilization > 0.0
                ? (maxUtilization - minUtilization) / averageUtilization
                : 0.0;

        return new UtilizationMetrics(
                averageUtilization,
                degreeOfImbalance);
    }

    private static double calculateEnergy(
            double arur,
            double makespan) {
        if (makespan <= 0.0) {
            return 0.0;
        }

        final double averagePower = POWER_IDLE
                + (POWER_MAX - POWER_IDLE) * arur;

        return averagePower * makespan / 1000.0;
    }

    private static double calculateCost(
            double arur,
            double makespan) {
        if (makespan <= 0.0) {
            return 0.0;
        }

        final double cpuCostRate = arur * COST_PER_CPU_SEC;
        final double ramCostRate = COST_PER_RAM_MB_SEC * 1024.0;

        return (cpuCostRate + ramCostRate) * makespan * NUM_VMS;
    }

    private static Datacenter createDatacenter(CloudSimPlus simulation) {
        final List<Host> hostList = new ArrayList<>();

        for (int hostId = 0; hostId < NUM_HOSTS; hostId++) {
            final List<Pe> peList = new ArrayList<>();
            final int numberOfPes = 8;
            final long mipsPerPe = 25_000L;

            for (int peId = 0; peId < numberOfPes; peId++) {
                peList.add(new PeSimple(mipsPerPe));
            }

            final long hostRam = 512L * 1024L;
            final long hostBw = 1_000_000L;
            final long hostStorage = 4_000_000L;

            hostList.add(new HostSimple(
                    hostRam,
                    hostBw,
                    hostStorage,
                    peList));
        }

        final Datacenter datacenter = new DatacenterSimple(
                simulation,
                hostList,
                new VmAllocationPolicySimple());

        datacenter.setSchedulingInterval(0.1);
        return datacenter;
    }

    private static List<Vm> createHeterogeneousVms() {
        final List<Vm> vmList = new ArrayList<>(NUM_VMS);

        for (int vmId = 0; vmId < NUM_VMS; vmId++) {
            final int tier = vmId % 4;

            final long mips;
            final int pes;

            switch (tier) {
                case 0 -> {
                    mips = 10_000L;
                    pes = 2;
                }
                case 1 -> {
                    mips = 7_500L;
                    pes = 1;
                }
                case 2 -> {
                    mips = 5_000L;
                    pes = 1;
                }
                default -> {
                    mips = 2_500L;
                    pes = 1;
                }
            }

            final Vm vm = new VmSimple(vmId, mips, pes);
            vm.setRam(16_384L);
            vm.setBw(50_000L);
            vm.setSize(10_000L);
            vmList.add(vm);
        }

        return vmList;
    }

    private static List<Cloudlet> createDynamicCloudlets(
            int numTasks,
            long seed) {

        final List<Cloudlet> cloudletList = new ArrayList<>(numTasks);
        final Random random = new Random(seed);

        final UtilizationModelDynamic cpuUtilization = new UtilizationModelDynamic(0.8);
        final UtilizationModelDynamic ramUtilization = new UtilizationModelDynamic(0.05);
        final UtilizationModelDynamic bwUtilization = new UtilizationModelDynamic(0.05);

        for (int i = 0; i < numTasks; i++) {
            final long length = 10_000L
                    + (long) (random.nextDouble() * 790_001L);

            final Cloudlet cloudlet = new CloudletSimple(i, length, 1);

            cloudlet.setUtilizationModelCpu(cpuUtilization);
            cloudlet.setUtilizationModelRam(ramUtilization);
            cloudlet.setUtilizationModelBw(bwUtilization);

            cloudlet.setSubmissionDelay((i / 100.0) * 0.5);
            cloudletList.add(cloudlet);
        }

        return cloudletList;
    }

    private record Result(
            boolean valid,
            int finishedCount,
            double makespan,
            double throughput,
            double averageUtilization,
            double degreeOfImbalance,
            double energy,
            double cost) {

        static Result invalid(int finishedCount) {
            return new Result(
                    false,
                    finishedCount,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0);
        }
    }

    private record UtilizationMetrics(
            double averageUtilization,
            double degreeOfImbalance) {
    }

    private static class MetricAccumulator {
        private int count;

        private double sumMakespan;
        private double sumMakespanSquared;

        private double sumThroughput;
        private double sumThroughputSquared;

        private double sumArur;
        private double sumDi;

        private double sumEnergy;
        private double sumEnergySquared;

        private double sumCost;
        private double sumCostSquared;

        void add(Result result) {
            count++;

            sumMakespan += result.makespan();
            sumMakespanSquared += result.makespan() * result.makespan();

            sumThroughput += result.throughput();
            sumThroughputSquared += result.throughput() * result.throughput();

            sumArur += result.averageUtilization();
            sumDi += result.degreeOfImbalance();

            sumEnergy += result.energy();
            sumEnergySquared += result.energy() * result.energy();

            sumCost += result.cost();
            sumCostSquared += result.cost() * result.cost();
        }

        double meanMakespan() {
            return count == 0 ? 0.0 : sumMakespan / count;
        }

        double stdMakespan() {
            return standardDeviation(sumMakespan, sumMakespanSquared);
        }

        double meanThroughput() {
            return count == 0 ? 0.0 : sumThroughput / count;
        }

        double stdThroughput() {
            return standardDeviation(sumThroughput, sumThroughputSquared);
        }

        double meanArur() {
            return count == 0 ? 0.0 : sumArur / count;
        }

        double meanDi() {
            return count == 0 ? 0.0 : sumDi / count;
        }

        double meanEnergy() {
            return count == 0 ? 0.0 : sumEnergy / count;
        }

        double stdEnergy() {
            return standardDeviation(sumEnergy, sumEnergySquared);
        }

        double meanCost() {
            return count == 0 ? 0.0 : sumCost / count;
        }

        double stdCost() {
            return standardDeviation(sumCost, sumCostSquared);
        }

        private double standardDeviation(
                double sum,
                double squaredSum) {
            if (count < 2) {
                return 0.0;
            }

            final double mean = sum / count;
            final double variance = Math.max(
                    0.0,
                    (squaredSum - count * mean * mean) / (count - 1));

            return Math.sqrt(variance);
        }
    }
}
