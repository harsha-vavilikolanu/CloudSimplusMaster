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

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class ExperimentRLMOTSLoadBalancing {

    private static final int NUM_HOSTS =
            20;

    private static final int NUM_VMS =
            100;

    private static final int[] TASK_SCENARIOS = {
            200,
            400,
            600,
            800,
            1000
    };

    private static final long RANDOM_SEED =
            42L;

    private static final double POWER_IDLE =
            175.0;

    private static final double POWER_MAX =
            375.0;

    private static final double COST_PER_CPU_SEC =
            0.03;

    private static final double COST_PER_RAM_MB_SEC =
            0.0005;

    public static void main(
            String[] args) {

        System.out.println(
                "==========================================================================");

        System.out.println(
                " DYNAMIC CLOUD LOAD BALANCING & MULTI-OBJECTIVE TASK SCHEDULING");

        System.out.println(
                " CloudSim Plus 8.5.7 - Double-DQN Experimental Testbed");

        System.out.println(
                " Algorithms: FCFS | Round Robin | Max-Min | Proposed Double-DQN");

        System.out.println(
                "==========================================================================");

        for (int taskCount :
                TASK_SCENARIOS) {

            System.out.println();

            System.out.println(
                    "--------------------------------------------------------------------------");

            System.out.printf(
                    "WORKLOAD SCENARIO: %d CLOUDLETS%n",
                    taskCount);

            System.out.println(
                    "--------------------------------------------------------------------------");

            printHeader();

            runSimulationForScenario(
                    "FCFS",
                    taskCount);

            runSimulationForScenario(
                    "RoundRobin",
                    taskCount);

            runSimulationForScenario(
                    "MaxMin",
                    taskCount);

            runSimulationForScenario(
                    "Proposed_DQN",
                    taskCount);
        }

        System.out.println();

        System.out.println(
                "==========================================================================");

        System.out.println(
                " ALL SIMULATION EXPERIMENTS COMPLETED");

        System.out.println(
                "==========================================================================");
    }

    private static void printHeader() {

        System.out.printf(
                "%-15s | %-14s | %-14s | %-8s | %-8s | %-14s | %-12s%n",
                "Algorithm",
                "Makespan(s)",
                "Throughput",
                "ARUR",
                "DI",
                "Energy(kJ)",
                "Cost($)");

        System.out.println(
                "--------------------------------------------------------------------------");
    }

    private static void runSimulationForScenario(
            String algorithm,
            int numTasks) {

        System.out.println();

        System.out.println(
                "Running "
                        + algorithm
                        + " with "
                        + numTasks
                        + " Cloudlets...");

        /*
         * ============================================================
         * CREATE SIMULATION
         * ============================================================
         */

        CloudSimPlus simulation =
                new CloudSimPlus();

        /*
         * ============================================================
         * CREATE DATACENTER
         * ============================================================
         */

        createDatacenter(
                simulation);

        /*
         * ============================================================
         * CREATE BROKER
         * ============================================================
         */

        DatacenterBroker broker;

        if ("Proposed_DQN"
                .equalsIgnoreCase(algorithm)) {

            DqnAgent agent =
                    new DqnAgent(
                            DqnState.STATE_DIM,
                            NUM_VMS,
                            RANDOM_SEED
                                    + numTasks);

            broker =
                    new AdvancedDqnBroker(
                            simulation,
                            "DqnBroker",
                            agent);

        } else if ("RoundRobin"
                .equalsIgnoreCase(algorithm)) {

            broker =
                    new BaselineBrokers.RoundRobinBroker(
                            simulation,
                            "RoundRobinBroker");

        } else if ("MaxMin"
                .equalsIgnoreCase(algorithm)) {

            broker =
                    new BaselineBrokers.MaxMinBroker(
                            simulation,
                            "MaxMinBroker");

        } else {

            broker =
                    new BaselineBrokers.FcfsBroker(
                            simulation,
                            "FcfsBroker");
        }

        /*
         * ============================================================
         * IMPORTANT BROKER LIFECYCLE SETTINGS
         * ============================================================
         */

        broker.setShutdownWhenIdle(false);

        broker.setVmDestructionDelay(-1);

        /*
         * ============================================================
         * CREATE VMS
         * ============================================================
         */

        List<Vm> vmList =
                createHeterogeneousVms();

        /*
         * ============================================================
         * CREATE CLOUDLETS
         * ============================================================
         */

        List<Cloudlet> cloudletList =
                createDynamicCloudlets(
                        numTasks);

        /*
         * ============================================================
         * ENABLE UTILIZATION STATISTICS
         * ============================================================
         */

        for (Vm vm : vmList) {

            vm.enableUtilizationStats();
        }

        /*
         * ============================================================
         * SUBMIT VMS
         * ============================================================
         */

        broker.submitVmList(
                vmList);

        /*
         * ============================================================
         * SUBMIT CLOUDLETS
         * ============================================================
         */

        broker.submitCloudletList(
                cloudletList);

        /*
         * ============================================================
         * START SIMULATION
         * ============================================================
         */

        simulation.start();

        /*
         * ============================================================
         * GET FINISHED CLOUDLETS
         * ============================================================
         */

        List<Cloudlet> finishedCloudlets =
                broker.getCloudletFinishedList();

        int finishedCount =
                finishedCloudlets.size();

        /*
         * ============================================================
         * STRICT VALIDATION
         * ============================================================
         */

        if (finishedCount != numTasks) {

            System.out.println();

            System.out.println(
                    "==========================================================================");

            System.out.println(
                    "INVALID EXPERIMENT RESULT");

            System.out.println(
                    algorithm
                            + " completed "
                            + finishedCount
                            + " / "
                            + numTasks
                            + " Cloudlets.");

            System.out.println(
                    "Result discarded because not all Cloudlets completed.");

            System.out.println(
                    "==========================================================================");

            return;
        }

        System.out.println(
                "SUCCESS: "
                        + algorithm
                        + " completed all "
                        + numTasks
                        + " Cloudlets.");

        /*
         * ============================================================
         * MAKESPAN
         * ============================================================
         */

        double makespan =
                calculateMakespan(
                        finishedCloudlets);

        /*
         * ============================================================
         * THROUGHPUT
         * ============================================================
         */

        double throughput =
                makespan > 0.0
                        ? finishedCount
                                / makespan
                        : 0.0;

        /*
         * ============================================================
         * UTILIZATION
         * ============================================================
         */

        UtilizationMetrics metrics =
                calculateUtilizationMetrics(
                        vmList);

        /*
         * ============================================================
         * ENERGY
         * ============================================================
         */

        double energy =
                calculateEnergy(
                        metrics.averageUtilization,
                        makespan);

        /*
         * ============================================================
         * COST
         * ============================================================
         */

        double cost =
                calculateCost(
                        metrics.averageUtilization,
                        makespan);

        /*
         * ============================================================
         * RESULT
         * ============================================================
         */

        System.out.printf(
                "%-15s | %-14.3f | %-14.6f | %-8.4f | %-8.4f | %-14.3f | %-12.3f%n",
                algorithm,
                makespan,
                throughput,
                metrics.averageUtilization,
                metrics.degreeOfImbalance,
                energy,
                cost);
    }

    private static double calculateMakespan(
            List<Cloudlet> cloudlets) {

        double makespan =
                0.0;

        for (Cloudlet cloudlet :
                cloudlets) {

            makespan =
                    Math.max(
                            makespan,
                            cloudlet.getFinishTime());
        }

        return makespan;
    }

    private static UtilizationMetrics
    calculateUtilizationMetrics(
            List<Vm> vmList) {

        double totalUtilization =
                0.0;

        double maxUtilization =
                0.0;

        double minUtilization =
                1.0;

        int count =
                0;

        for (Vm vm :
                vmList) {

            double utilization;

            try {

                utilization =
                        vm.getCpuUtilizationStats()
                                .getMean();

            } catch (Exception e) {

                utilization =
                        vm.getCpuPercentUtilization();
            }

            if (Double.isNaN(utilization)
                    || Double.isInfinite(utilization)) {

                utilization = 0.0;
            }

            utilization =
                    Math.max(
                            0.0,
                            Math.min(
                                    1.0,
                                    utilization));

            totalUtilization +=
                    utilization;

            maxUtilization =
                    Math.max(
                            maxUtilization,
                            utilization);

            minUtilization =
                    Math.min(
                            minUtilization,
                            utilization);

            count++;
        }

        if (count == 0) {

            return new UtilizationMetrics(
                    0.0,
                    0.0);
        }

        double averageUtilization =
                totalUtilization
                        / count;

        double degreeOfImbalance =
                averageUtilization > 0.0
                        ? (maxUtilization
                                - minUtilization)
                                / averageUtilization
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

        double averagePower =
                POWER_IDLE
                        + (POWER_MAX
                                - POWER_IDLE)
                                * arur;

        return averagePower
                * makespan
                / 1000.0;
    }

    private static double calculateCost(
            double arur,
            double makespan) {

        if (makespan <= 0.0) {
            return 0.0;
        }

        double cpuCostRate =
                arur
                        * COST_PER_CPU_SEC;

        double ramCostRate =
                COST_PER_RAM_MB_SEC
                        * 1024.0;

        return (cpuCostRate
                + ramCostRate)
                * makespan
                * NUM_VMS;
    }

    /*
     * ============================================================
     * CREATE DATACENTER
     * ============================================================
     */

    private static Datacenter createDatacenter(
            CloudSimPlus simulation) {

        List<Host> hostList =
                new ArrayList<>();

        for (int hostId = 0;
             hostId < NUM_HOSTS;
             hostId++) {

            List<Pe> peList =
                    new ArrayList<>();

            int numberOfPes =
                    8;

            long mipsPerPe =
                    25_000L;

            for (int peId = 0;
                 peId < numberOfPes;
                 peId++) {

                peList.add(
                        new PeSimple(
                                mipsPerPe));
            }

            long hostRam =
                    512L * 1024L;

            long hostBw =
                    1_000_000L;

            long hostStorage =
                    4_000_000L;

            Host host =
                    new HostSimple(
                            hostRam,
                            hostBw,
                            hostStorage,
                            peList);

            hostList.add(host);
        }

        Datacenter datacenter =
                new DatacenterSimple(
                        simulation,
                        hostList,
                        new VmAllocationPolicySimple());

        /*
         * IMPORTANT:
         * Schedule VM/Cloudlet processing
         * every 0.1 second.
         */
        datacenter.setSchedulingInterval(
                0.1);

        return datacenter;
    }

    /*
     * ============================================================
     * CREATE HETEROGENEOUS VMS
     * ============================================================
     */

    private static List<Vm>
    createHeterogeneousVms() {

        List<Vm> vmList =
                new ArrayList<>(
                        NUM_VMS);

        for (int vmId = 0;
             vmId < NUM_VMS;
             vmId++) {

            int tier =
                    vmId % 4;

            long mips;
            int pes;

            switch (tier) {

                case 0:
                    mips = 10_000L;
                    pes = 2;
                    break;

                case 1:
                    mips = 7_500L;
                    pes = 1;
                    break;

                case 2:
                    mips = 5_000L;
                    pes = 1;
                    break;

                default:
                    mips = 2_500L;
                    pes = 1;
                    break;
            }

            long ram =
                    16_384L;

            long bw =
                    50_000L;

            long size =
                    10_000L;

            Vm vm =
                    new VmSimple(
                            vmId,
                            mips,
                            pes);

            vm.setRam(ram);
            vm.setBw(bw);
            vm.setSize(size);

            vmList.add(vm);
        }

        return vmList;
    }

    /*
     * ============================================================
     * CREATE DYNAMIC CLOUDLETS
     * ============================================================
     */

    private static List<Cloudlet>
    createDynamicCloudlets(
            int numTasks) {

        List<Cloudlet> cloudletList =
                new ArrayList<>(
                        numTasks);

        Random random =
                new Random(
                        RANDOM_SEED
                                + numTasks);

        UtilizationModelDynamic cpuUtilization =
                new UtilizationModelDynamic(
                        0.8);

        UtilizationModelDynamic ramUtilization =
                new UtilizationModelDynamic(
                        0.05);

        UtilizationModelDynamic bwUtilization =
                new UtilizationModelDynamic(
                        0.05);

        for (int i = 0;
             i < numTasks;
             i++) {

            /*
             * Cloudlet length:
             *
             * 10,000 to 800,000 MI.
             */
            long length =
                    10_000L
                            + (long)
                            (random.nextDouble()
                                    * 790_001L);

            Cloudlet cloudlet =
                    new CloudletSimple(
                            i,
                            length,
                            1);

            cloudlet
                    .setUtilizationModelCpu(
                            cpuUtilization);

            cloudlet
                    .setUtilizationModelRam(
                            ramUtilization);

            cloudlet
                    .setUtilizationModelBw(
                            bwUtilization);

            /*
             * Dynamic submission delay.
             */
            cloudlet.setSubmissionDelay(
                    (i / 100.0) * 0.5);

            cloudletList.add(
                    cloudlet);
        }

        return cloudletList;
    }

    private static class UtilizationMetrics {

        private final double averageUtilization;

        private final double degreeOfImbalance;

        private UtilizationMetrics(
                double averageUtilization,
                double degreeOfImbalance) {

            this.averageUtilization =
                    averageUtilization;

            this.degreeOfImbalance =
                    degreeOfImbalance;
        }
    }
}