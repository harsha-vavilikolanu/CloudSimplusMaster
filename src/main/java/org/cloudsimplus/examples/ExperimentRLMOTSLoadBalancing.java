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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ExperimentRLMOTSLoadBalancing {

    private static final int NUM_HOSTS = 10;
    private static final int NUM_VMS = 20;
    private static final int[] TASK_SCENARIOS = { 500 };
    private static final int DEFAULT_RUNS = 25;
    private static final long BASE_SEED = 42_000L;

    private static final double POWER_IDLE = 175.0;
    private static final double POWER_MAX = 375.0;
    private static final double COST_PER_CPU_SEC = 0.03;
    private static final double COST_PER_RAM_MB_SEC = 0.0005;

    public static void main(String[] args) throws IOException {
        
        org.cloudsimplus.util.Log.setLevel(ch.qos.logback.classic.Level.ERROR);
        final int runs = parseRuns(args);

        System.out.println("==============================================================");
        System.out.println(" CLOUD LOAD BALANCING USING REINFORCEMENT LEARNING (ACADEMIC)");
        System.out.println("==============================================================");
        System.out.println("Tasks       : " + formatTaskScenarios());
        System.out.println("Algorithms  : FCFS | RoundRobin | MET | MCT | MinMin | MaxMin");
        System.out.println("              Sufferage | PSO | GA | ACO | Proposed_DQN");
        System.out.println("==============================================================");

        // Instantiate DQN Agent with 4 discrete meta-actions
        final DqnAgent sharedDqnAgent = new DqnAgent(DqnState.STATE_DIM, 4, BASE_SEED);

        /*
         * ============================================================
         * PHASE 1: OFFLINE REINFORCEMENT LEARNING TRAINING
         * The agent trains over 30 simulated episodes to learn the 
         * workload-dependent switching policy.
         * ============================================================
         */
        System.out.println("\n[PHASE 1] Running Offline RL Training (30 Episodes)...");
        sharedDqnAgent.setTrainingMode(true);
        for (int e = 1; e <= 30; e++) {
            long trainSeed = BASE_SEED + e * 500L;
            runSimulationForScenario("Proposed_DQN", TASK_SCENARIOS[0], trainSeed, trainSeed, sharedDqnAgent);
        }
        
        // Lock exploration for pure policy evaluation
        sharedDqnAgent.setTrainingMode(false);
        System.out.println("[PHASE 1] Training Complete. Evaluating on 25 Independent Seeds...\n");

        /*
         * ============================================================
         * PHASE 2: RIGOROUS EVALUATION (25 Runs)
         * ============================================================
         */
        final Map<String, MetricAccumulator> aggregate = new LinkedHashMap<>();
        final Path resultsDir = Paths.get("results");
        Files.createDirectories(resultsDir);
        final Path csvPath = resultsDir.resolve("rlmots_results.csv");

        try (BufferedWriter writer = Files.newBufferedWriter(csvPath)) {
            writer.write("tasks,run,algorithm,makespan_s,throughput,arur,degree_of_imbalance,energy_kj,cost");
            writer.newLine();

            for (int taskCount : TASK_SCENARIOS) {
                for (int run = 1; run <= runs; run++) {
                    final long workloadSeed = BASE_SEED + (long) taskCount * 10_000L + run;
                    System.out.println("Run " + run + "/" + runs);

                    final String[] algorithms = {
                            "FCFS", "RoundRobin", "MET", "MCT", 
                            "MinMin", "MaxMin", "Sufferage", 
                            "PSO", "GA", "ACO", "Proposed_DQN"
                    };

                    for (String algorithm : algorithms) {
                        final long heuristicSeed = workloadSeed + 900_000L;
                        final long wallStart = System.currentTimeMillis();

                        final Result result = runSimulationForScenario(algorithm, taskCount, workloadSeed, heuristicSeed, sharedDqnAgent);
                        final long wallTime = System.currentTimeMillis() - wallStart;

                        if (!result.valid()) {
                            System.out.printf("  %-14s INVALID (%d/%d) [%d ms]%n", algorithm, result.finishedCount(), taskCount, wallTime);
                            continue;
                        }

                        System.out.printf("  %-14s makespan=%8.4f s throughput=%10.4f ARUR=%8.4f DI=%8.4f energy=%10.4f kJ cost=%10.6f [%d ms]%n",
                                algorithm, result.makespan(), result.throughput(), result.averageUtilization(),
                                result.degreeOfImbalance(), result.energy(), result.cost(), wallTime);

                        writer.write(String.format("%d,%d,%s,%.8f,%.8f,%.8f,%.8f,%.8f,%.8f",
                                taskCount, run, algorithm, result.makespan(), result.throughput(),
                                result.averageUtilization(), result.degreeOfImbalance(), result.energy(), result.cost()));
                        writer.newLine();
                        writer.flush();

                        aggregate.computeIfAbsent(taskCount + "-" + algorithm, ignored -> new MetricAccumulator()).add(result);
                        System.gc();
                    }
                }
            }
        }
        printSummary(aggregate);
    }

    private static String formatTaskScenarios() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < TASK_SCENARIOS.length; i++) {
            if (i > 0) builder.append(", ");
            builder.append(TASK_SCENARIOS[i]);
        }
        return builder.toString();
    }

    private static int parseRuns(String[] args) {
        if (args.length == 0) return DEFAULT_RUNS;
        try {
            final int runs = Integer.parseInt(args[0].trim());
            if (runs <= 0) throw new NumberFormatException();
            return runs;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("First argument must be a positive integer.", ex);
        }
    }

    private static Result runSimulationForScenario(String algorithm, int numTasks, long workloadSeed, long heuristicSeed, DqnAgent sharedDqnAgent) {
        final CloudSimPlus simulation = new CloudSimPlus();
        createDatacenter(simulation);

        final DatacenterBroker broker;

        if ("Proposed_DQN".equalsIgnoreCase(algorithm)) {
            broker = new AdvancedDqnBroker(simulation, "DqnBroker", sharedDqnAgent);
        } else if ("RoundRobin".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.RoundRobinBroker(simulation, "RoundRobinBroker");
        } else if ("MET".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.MetBroker(simulation, "MetBroker");
        } else if ("MCT".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.MctBroker(simulation, "MctBroker");
        } else if ("MinMin".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.MinMinBroker(simulation, "MinMinBroker");
        } else if ("MaxMin".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.MaxMinBroker(simulation, "MaxMinBroker");
        } else if ("Sufferage".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.SufferageBroker(simulation, "SufferageBroker");
        } else if ("PSO".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.PsoBroker(simulation, "PsoBroker", heuristicSeed);
        } else if ("GA".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.GaBroker(simulation, "GaBroker", heuristicSeed);
        } else if ("ACO".equalsIgnoreCase(algorithm)) {
            broker = new BaselineBrokers.AcoBroker(simulation, "AcoBroker", heuristicSeed);
        } else {
            broker = new BaselineBrokers.FcfsBroker(simulation, "FcfsBroker");
        }

        broker.setShutdownWhenIdle(false);
        broker.setVmDestructionDelay(-1);

        final List<Vm> vmList = createHeterogeneousVms();
        final List<Cloudlet> cloudletList = createDynamicCloudlets(numTasks, workloadSeed);

        /*
         * STANDARD, UNTOUCHED HEURISTIC QUEUE PREPARATION
         * Both MaxMin and Proposed_DQN receive the exact same LPT (descending) queue.
         */
        if ("MinMin".equalsIgnoreCase(algorithm)) {
            cloudletList.sort(Comparator.comparingDouble(Cloudlet::getLength));
        } else if ("MaxMin".equalsIgnoreCase(algorithm) || "Proposed_DQN".equalsIgnoreCase(algorithm)) {
            cloudletList.sort(Comparator.comparingDouble(Cloudlet::getLength).reversed());
        }

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
        final double throughput = makespan > 0.0 ? finishedCount / makespan : 0.0;
        final UtilizationMetrics utilization = calculateUtilizationMetrics(vmList);
        final double energy = calculateEnergy(utilization.averageUtilization(), makespan);
        final double cost = calculateCost(utilization.averageUtilization(), makespan);

        return new Result(true, finishedCount, makespan, throughput, utilization.averageUtilization(),
                utilization.degreeOfImbalance(), energy, cost);
    }

    private static Datacenter createDatacenter(CloudSimPlus simulation) {
        final List<Host> hostList = new ArrayList<>();
        for (int hostId = 0; hostId < NUM_HOSTS; hostId++) {
            final List<Pe> peList = new ArrayList<>(8);
            for (int peId = 0; peId < 8; peId++) {
                peList.add(new PeSimple(10_000L));
            }
            hostList.add(new HostSimple(64L * 1024L, 1_000_000L, 1_000_000L, peList));
        }
        Datacenter datacenter = new DatacenterSimple(simulation, hostList, new VmAllocationPolicySimple());
        datacenter.setSchedulingInterval(1.0);
        return datacenter;
    }

    private static List<Vm> createHeterogeneousVms() {
        final List<Vm> vmList = new ArrayList<>(NUM_VMS);
        for (int vmId = 0; vmId < NUM_VMS; vmId++) {
            final int tier = vmId % 4;
            final long mips = switch (tier) {
                case 0 -> 5_000L;
                case 1 -> 4_000L;
                case 2 -> 3_000L;
                default -> 2_000L;
            };
            final int pes = tier == 0 ? 2 : 1;

            final Vm vm = new VmSimple(vmId, mips, pes);
            vm.setRam(8L * 1024L);
            vm.setBw(50_000L);
            vm.setSize(10_000L);
            vmList.add(vm);
        }
        return vmList;
    }

    private static List<Cloudlet> createDynamicCloudlets(int numTasks, long seed) {
        final List<Cloudlet> cloudletList = new ArrayList<>(numTasks);
        final Random random = new Random(seed);
        final UtilizationModelDynamic cpuUtilization = new UtilizationModelDynamic(0.8);
        final UtilizationModelDynamic ramUtilization = new UtilizationModelDynamic(0.0);
        final UtilizationModelDynamic bwUtilization = new UtilizationModelDynamic(0.0);

        for (int i = 0; i < numTasks; i++) {
            final long length = 1_000L + (long) (random.nextDouble() * 4_001L);
            final Cloudlet cloudlet = new CloudletSimple(i, length, 1);
            cloudlet.setUtilizationModelCpu(cpuUtilization);
            cloudlet.setUtilizationModelRam(ramUtilization);
            cloudlet.setUtilizationModelBw(bwUtilization);
            cloudletList.add(cloudlet);
        }
        return cloudletList;
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
            if (Double.isNaN(utilization) || Double.isInfinite(utilization)) utilization = 0.0;
            utilization = Math.max(0.0, Math.min(1.0, utilization));

            totalUtilization += utilization;
            maxUtilization = Math.max(maxUtilization, utilization);
            minUtilization = Math.min(minUtilization, utilization);
            count++;
        }

        if (count == 0) return new UtilizationMetrics(0.0, 0.0);
        final double averageUtilization = totalUtilization / count;
        final double degreeOfImbalance = averageUtilization > 0.0 ? ((maxUtilization - minUtilization) / averageUtilization) : 0.0;
        return new UtilizationMetrics(averageUtilization, degreeOfImbalance);
    }

    private static double calculateEnergy(double arur, double makespan) {
        if (makespan <= 0.0) return 0.0;
        final double averagePower = POWER_IDLE + (POWER_MAX - POWER_IDLE) * arur;
        return averagePower * makespan / 1000.0;
    }

    private static double calculateCost(double arur, double makespan) {
        if (makespan <= 0.0) return 0.0;
        final double cpuCostRate = arur * COST_PER_CPU_SEC;
        final double ramCostRate = COST_PER_RAM_MB_SEC * 8192.0;
        return (cpuCostRate + ramCostRate) * makespan * NUM_VMS;
    }

    private static void printSummary(Map<String, MetricAccumulator> aggregate) {
        System.out.println("\n==============================================================");
        System.out.println("FINAL AGGREGATED RESULTS (Averaged over 25 runs)");
        System.out.println("==============================================================");
        System.out.printf("%-8s | %-14s | %12s | %12s | %10s | %10s | %12s | %10s%n",
                "Tasks", "Algorithm", "Makespan", "Throughput", "ARUR", "DI", "Energy(kJ)", "Cost");
        System.out.println("----------------------------------------------------------------------------------------------------");

        for (Map.Entry<String, MetricAccumulator> entry : aggregate.entrySet()) {
            final String[] parts = entry.getKey().split("-", 2);
            final int tasks = Integer.parseInt(parts[0]);
            final MetricAccumulator acc = entry.getValue();

            System.out.printf("%-8d | %-14s | %12.4f | %12.4f | %10.4f | %10.4f | %12.4f | %10.6f%n",
                    tasks, parts[1], acc.meanMakespan(), acc.meanThroughput(),
                    acc.meanArur(), acc.meanDi(), acc.meanEnergy(), acc.meanCost());
        }
        System.out.println("==============================================================");
    }

    private record Result(boolean valid, int finishedCount, double makespan, double throughput,
                          double averageUtilization, double degreeOfImbalance, double energy, double cost) {
        static Result invalid(int finishedCount) {
            return new Result(false, finishedCount, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }
    }

    private record UtilizationMetrics(double averageUtilization, double degreeOfImbalance) {}

    private static class MetricAccumulator {
        private final List<Double> makespans = new ArrayList<>();
        private final List<Double> throughputs = new ArrayList<>();
        private final List<Double> arurs = new ArrayList<>();
        private final List<Double> dis = new ArrayList<>();
        private final List<Double> energies = new ArrayList<>();
        private final List<Double> costs = new ArrayList<>();

        void add(Result result) {
            makespans.add(result.makespan());
            throughputs.add(result.throughput());
            arurs.add(result.averageUtilization());
            dis.add(result.degreeOfImbalance());
            energies.add(result.energy());
            costs.add(result.cost());
        }

        double meanMakespan() { return mean(makespans); }
        double meanThroughput() { return mean(throughputs); }
        double meanArur() { return mean(arurs); }
        double meanDi() { return mean(dis); }
        double meanEnergy() { return mean(energies); }
        double meanCost() { return mean(costs); }

        private static double mean(List<Double> values) {
            if (values.isEmpty()) return 0.0;
            double sum = 0.0;
            for (double value : values) sum += value;
            return sum / values.size();
        }
    }
}