package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;
import org.cloudsimplus.vms.Vm;

import java.util.List;

public class AdvancedDqnBroker
        extends DatacenterBrokerSimple {

    private static final double POWER_IDLE =
            175.0;

    private static final double POWER_MAX =
            375.0;

    private static final double COST_PER_CPU_SEC =
            0.03;

    private static final double COST_PER_RAM_MB_SEC =
            0.0005;

    private static final double MAX_REMAINING_WORKLOAD =
            5_000_000.0;

    private final DqnAgent dqnAgent;

    private DqnState lastState;

    private int lastAction = 0;

    private Cloudlet lastCloudlet;

    public AdvancedDqnBroker(
            CloudSimPlus simulation,
            String name,
            DqnAgent agent) {

        super(simulation, name);

        this.dqnAgent = agent;

        /*
         * Important:
         * Keep the broker alive until the simulation
         * has no work left.
         */
        setShutdownWhenIdle(false);

        /*
         * Do not destroy VMs automatically.
         */
        setVmDestructionDelay(-1);

        setVmMapper(
                this::mapTaskWithDqn);
    }

    private Vm mapTaskWithDqn(
            Cloudlet cloudlet) {

        List<Vm> vmList =
                getVmExecList();

        if (vmList.isEmpty()) {
            return Vm.NULL;
        }

        DqnState currentState =
                observeState(
                        vmList,
                        cloudlet);

        /*
         * Reward previous decision.
         */
        if (lastState != null
                && lastCloudlet != null) {

            double reward =
                    calculateReward(
                            lastState,
                            currentState);

            boolean done =
                    getCloudletWaitingList()
                            .isEmpty();

            dqnAgent.recordTransition(
                    lastState,
                    lastAction,
                    reward,
                    currentState,
                    done);
        }

        /*
         * DQN chooses an action.
         */
        int action =
                dqnAgent.selectAction(
                        currentState);

        int preferredIndex =
                Math.floorMod(
                        action,
                        vmList.size());

        /*
         * Use the DQN choice together with
         * actual scheduler workload.
         */
        Vm selectedVm =
                chooseLeastLoadedVm(
                        vmList,
                        preferredIndex,
                        cloudlet);

        lastState =
                currentState;

        lastAction =
                vmList.indexOf(
                        selectedVm);

        if (lastAction < 0) {
            lastAction = 0;
        }

        lastCloudlet =
                cloudlet;

        return selectedVm;
    }

    private Vm chooseLeastLoadedVm(
            List<Vm> vmList,
            int preferredIndex,
            Cloudlet incomingCloudlet) {

        Vm preferredVm =
                vmList.get(preferredIndex);

        double preferredWorkload =
                getVmRemainingWorkload(
                        preferredVm);

        double bestScore =
                Double.MAX_VALUE;

        Vm bestVm =
                preferredVm;

        double maxWorkload =
                1.0;

        for (Vm vm : vmList) {

            maxWorkload =
                    Math.max(
                            maxWorkload,
                            getVmRemainingWorkload(vm));
        }

        for (int i = 0;
             i < vmList.size();
             i++) {

            Vm vm =
                    vmList.get(i);

            double workload =
                    getVmRemainingWorkload(vm);

            double normalizedWorkload =
                    Math.min(
                            1.0,
                            workload
                                    / maxWorkload);

            double utilization =
                    safeUtilization(vm);

            /*
             * DQN preference bonus.
             */
            double dqnBonus =
                    i == preferredIndex
                            ? 1.0
                            : 0.0;

            /*
             * Incoming task workload.
             */
            double incomingLength =
                    incomingCloudlet == null
                            ? 0.0
                            : incomingCloudlet.getLength();

            double projectedWorkload =
                    workload
                            + incomingLength;

            double normalizedProjected =
                    Math.min(
                            1.0,
                            projectedWorkload
                                    / MAX_REMAINING_WORKLOAD);

            /*
             * Final load-aware score.
             *
             * Lower is better.
             */
            double score =
                    0.50
                            * normalizedProjected
                    + 0.35
                            * utilization
                    - 0.15
                            * dqnBonus;

            /*
             * Penalize heavily loaded VMs.
             */
            if (workload > 5_000_000.0) {
                score += 2.0;
            }

            /*
             * Small preference for the VM
             * selected by the DQN.
             */
            if (vm == preferredVm) {
                score -= 0.05;
            }

            if (score < bestScore) {

                bestScore =
                        score;

                bestVm =
                        vm;
            }
        }

        /*
         * Avoid unused variable warning.
         */
        if (preferredWorkload < 0) {
            return preferredVm;
        }

        return bestVm;
    }

    private DqnState observeState(
            List<Vm> vmList,
            Cloudlet incomingCloudlet) {

        if (vmList.isEmpty()) {

            return new DqnState(
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0,
                    0.0);
        }

        double totalUtilization =
                0.0;

        double[] utilizations =
                new double[vmList.size()];

        double totalRemainingWorkload =
                0.0;

        int index = 0;

        for (Vm vm : vmList) {

            double utilization =
                    safeUtilization(vm);

            utilizations[index++] =
                    utilization;

            totalUtilization +=
                    utilization;

            totalRemainingWorkload +=
                    getVmRemainingWorkload(vm);
        }

        double meanUtilization =
                totalUtilization
                        / vmList.size();

        double variance =
                0.0;

        for (double utilization :
                utilizations) {

            variance +=
                    Math.pow(
                            utilization
                                    - meanUtilization,
                            2);
        }

        double stdDev =
                Math.sqrt(
                        variance
                                / vmList.size());

        double waiting =
                getCloudletWaitingList()
                        .size();

        double queueLoad =
                Math.min(
                        1.0,
                        waiting / 200.0);

        double incomingLength =
                incomingCloudlet == null
                        ? 0.0
                        : incomingCloudlet.getLength();

        double remainingWorkload =
                totalRemainingWorkload
                        + incomingLength;

        double normalizedRemainingWorkload =
                Math.min(
                        1.0,
                        remainingWorkload
                                / MAX_REMAINING_WORKLOAD);

        double currentPower =
                POWER_IDLE
                        + (POWER_MAX - POWER_IDLE)
                        * meanUtilization;

        double normalizedEnergy =
                Math.min(
                        1.0,
                        currentPower
                                / POWER_MAX);

        double cpuCost =
                meanUtilization
                        * COST_PER_CPU_SEC;

        double ramCost =
                COST_PER_RAM_MB_SEC
                        * 1024.0;

        double currentCostRate =
                cpuCost
                        + ramCost;

        double normalizedCost =
                Math.min(
                        1.0,
                        currentCostRate
                                / 5.0);

        return new DqnState(
                meanUtilization,
                stdDev,
                queueLoad,
                normalizedRemainingWorkload,
                normalizedEnergy,
                normalizedCost);
    }

    /*
     * Actual scheduler-state workload.
     *
     * Executing:
     * remaining length
     *
     * Waiting:
     * complete length
     */
    private double getVmRemainingWorkload(
            Vm vm) {

        if (vm == null) {
            return 0.0;
        }

        CloudletScheduler scheduler =
                vm.getCloudletScheduler();

        if (scheduler == null) {
            return 0.0;
        }

        double workload =
                0.0;

        /*
         * Executing Cloudlets.
         */
        for (CloudletExecution execution :
                scheduler.getCloudletExecList()) {

            if (execution == null) {
                continue;
            }

            double remaining =
                    execution
                            .getRemainingCloudletLength();

            if (remaining > 0.0) {
                workload +=
                        remaining;
            }
        }

        /*
         * Waiting Cloudlets.
         */
        for (CloudletExecution execution :
                scheduler.getCloudletWaitingList()) {

            if (execution == null) {
                continue;
            }

            workload +=
                    execution.getCloudletLength();
        }

        return workload;
    }

    private double safeUtilization(
            Vm vm) {

        if (vm == null) {
            return 0.0;
        }

        double utilization;

        try {

            utilization =
                    vm.getCpuPercentUtilization();

        } catch (Exception e) {

            utilization = 0.0;
        }

        if (Double.isNaN(utilization)
                || Double.isInfinite(utilization)) {

            return 0.0;
        }

        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        utilization));
    }

    private double calculateReward(
            DqnState previous,
            DqnState current) {

        double previousQoS =
                previous.getFeature(2)
                        + previous.getFeature(3);

        double currentQoS =
                current.getFeature(2)
                        + current.getFeature(3);

        double qosImprovement =
                previousQoS
                        - currentQoS;

        double energyImprovement =
                previous.getFeature(4)
                        - current.getFeature(4);

        double costImprovement =
                previous.getFeature(5)
                        - current.getFeature(5);

        double imbalanceImprovement =
                previous.getFeature(1)
                        - current.getFeature(1);

        double reward =
                0.40
                        * qosImprovement
                + 0.25
                        * energyImprovement
                + 0.20
                        * costImprovement
                + 0.15
                        * imbalanceImprovement;

        return reward;
    }
}