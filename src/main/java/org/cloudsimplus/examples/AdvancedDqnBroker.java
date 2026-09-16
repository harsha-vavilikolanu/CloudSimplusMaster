package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;
import org.cloudsimplus.vms.Vm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Candidate-conditioned DQN broker for dynamic cloud load balancing.
 *
 * The broker observes the real scheduler state, builds a set of good VM
 * candidates and lets the DQN choose the candidate rank.
 *
 * Executing Cloudlets contribute remaining workload and waiting Cloudlets
 * contribute their complete length.
 */
public class AdvancedDqnBroker extends DatacenterBrokerSimple {

    private static final double POWER_IDLE = 175.0;
    private static final double POWER_MAX = 375.0;

    private static final double COST_PER_CPU_SEC = 0.03;
    private static final double COST_PER_RAM_MB_SEC = 0.0005;

    private static final double MAX_QUEUE_LENGTH = 1_000.0;
    private static final double MAX_SYSTEM_WORKLOAD = 80_000_000.0;
    private static final double MAX_COST_RATE = 1.0;

    /** DQN action space: choose one of the best candidate VM ranks. */
    public static final int CANDIDATE_COUNT = 16;

    private final DqnAgent dqnAgent;

    private DqnState lastState;
    private int lastAction;
    private Cloudlet lastCloudlet;

    public AdvancedDqnBroker(
            CloudSimPlus simulation,
            String name,
            DqnAgent agent) {

        super(simulation, name);

        this.dqnAgent = agent;
        this.lastAction = 0;

        /*
         * Keep the broker alive until the simulation has no work left.
         */
        setShutdownWhenIdle(false);

        /*
         * IMPORTANT:
         * null disables automatic destruction of idle VMs.
         * This prevents Cloudlets in VM scheduler queues from being lost.
         */
        setVmDestructionDelayFunction(null);

        setVmMapper(this::mapTaskWithDqn);
    }

    private Vm mapTaskWithDqn(Cloudlet cloudlet) {

        final List<Vm> vmList = getVmExecList();

        if (vmList.isEmpty()) {
            return Vm.NULL;
        }

        final DqnState currentState =
                observeState(vmList, cloudlet);

        /* Reward the previous placement. */
        if (lastState != null && lastCloudlet != null) {

            final double reward =
                    calculateReward(
                            lastState,
                            currentState);

            final boolean done =
                    getCloudletWaitingList().isEmpty();

            dqnAgent.recordTransition(
                    lastState,
                    lastAction,
                    reward,
                    currentState,
                    done);
        }

        final List<Vm> candidates =
                rankCandidateVms(
                        vmList,
                        cloudlet);

        if (candidates.isEmpty()) {
            return vmList.get(0);
        }

        /* DQN action = candidate rank. */
        final int action =
                dqnAgent.selectAction(currentState);

        final int candidateIndex =
                Math.floorMod(
                        action,
                        candidates.size());

        final Vm selectedVm =
                candidates.get(candidateIndex);

        lastState = currentState;
        lastAction = candidateIndex;
        lastCloudlet = cloudlet;

        return selectedVm;
    }

    /**
     * Rank VMs using actual remaining workload, projected completion time,
     * utilization, energy, cost and load imbalance.
     */
    private List<Vm> rankCandidateVms(
            List<Vm> vmList,
            Cloudlet incomingCloudlet) {

        final double incomingLength =
                incomingCloudlet == null
                        ? 0.0
                        : Math.max(
                                0.0,
                                incomingCloudlet.getLength());

        final double meanUtilization =
                vmList.stream()
                        .mapToDouble(
                                this::safeUtilization)
                        .average()
                        .orElse(0.0);

        final List<CandidateScore> scored =
                new ArrayList<>(vmList.size());

        double maxProjectedTime = 1.0;

        for (Vm vm : vmList) {

            final double remaining =
                    getVmRemainingWorkload(vm);

            final double effectiveMips =
                    Math.max(
                            1.0,
                            vm.getTotalMipsCapacity());

            final double projectedTime =
                    (remaining + incomingLength)
                            / effectiveMips;

            maxProjectedTime =
                    Math.max(
                            maxProjectedTime,
                            projectedTime);
        }

        for (Vm vm : vmList) {

            final double utilization =
                    safeUtilization(vm);

            final double remaining =
                    getVmRemainingWorkload(vm);

            final double effectiveMips =
                    Math.max(
                            1.0,
                            vm.getTotalMipsCapacity());

            final double projectedTime =
                    (remaining + incomingLength)
                            / effectiveMips;

            final double projectedTimeNorm =
                    clamp(
                            projectedTime
                                    / maxProjectedTime);

            final double energyNorm =
                    clamp(
                            (
                                    POWER_IDLE
                                            + (
                                            POWER_MAX
                                                    - POWER_IDLE)
                                            * utilization
                            )
                                    / POWER_MAX);

            final double costRate =
                    utilization
                            * COST_PER_CPU_SEC
                            + COST_PER_RAM_MB_SEC
                            * 1024.0;

            final double costNorm =
                    clamp(
                            costRate
                                    / MAX_COST_RATE);

            final double imbalancePenalty =
                    Math.abs(
                            utilization
                                    - meanUtilization);

            final double score =
                    0.55
                            * projectedTimeNorm
                    + 0.20
                            * utilization
                    + 0.15
                            * energyNorm
                    + 0.10
                            * costNorm
                    + 0.10
                            * imbalancePenalty;

            scored.add(
                    new CandidateScore(
                            vm,
                            score));
        }

        scored.sort(
                Comparator
                        .comparingDouble(
                                CandidateScore::score)
                        .thenComparingLong(
                                candidate ->
                                        candidate
                                                .vm()
                                                .getId()));

        final int count =
                Math.min(
                        CANDIDATE_COUNT,
                        scored.size());

        final List<Vm> candidates =
                new ArrayList<>(count);

        for (int i = 0;
             i < count;
             i++) {

            candidates.add(
                    scored.get(i).vm());
        }

        return candidates;
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

        double totalUtilization = 0.0;
        double totalRemainingWorkload = 0.0;

        final double[] utilizations =
                new double[vmList.size()];

        int index = 0;

        for (Vm vm : vmList) {

            final double utilization =
                    safeUtilization(vm);

            utilizations[index++] =
                    utilization;

            totalUtilization +=
                    utilization;

            totalRemainingWorkload +=
                    getVmRemainingWorkload(vm);
        }

        final double meanUtilization =
                totalUtilization
                        / vmList.size();

        double variance = 0.0;

        for (double utilization :
                utilizations) {

            variance +=
                    Math.pow(
                            utilization
                                    - meanUtilization,
                            2);
        }

        final double stdDev =
                Math.sqrt(
                        variance
                                / vmList.size());

        final double queueLoad =
                clamp(
                        getCloudletWaitingList()
                                .size()
                                / MAX_QUEUE_LENGTH);

        final double incomingLength =
                incomingCloudlet == null
                        ? 0.0
                        : Math.max(
                                0.0,
                                incomingCloudlet
                                        .getLength());

        final double remainingWorkload =
                totalRemainingWorkload
                        + incomingLength;

        final double normalizedRemainingWorkload =
                clamp(
                        remainingWorkload
                                / MAX_SYSTEM_WORKLOAD);

        final double currentPower =
                POWER_IDLE
                        + (
                        POWER_MAX
                                - POWER_IDLE)
                        * meanUtilization;

        final double normalizedEnergy =
                clamp(
                        currentPower
                                / POWER_MAX);

        final double currentCostRate =
                meanUtilization
                        * COST_PER_CPU_SEC
                        + COST_PER_RAM_MB_SEC
                        * 1024.0;

        final double normalizedCost =
                clamp(
                        currentCostRate
                                / MAX_COST_RATE);

        return new DqnState(
                meanUtilization,
                stdDev,
                queueLoad,
                normalizedRemainingWorkload,
                normalizedEnergy,
                normalizedCost);
    }

    /**
     * Actual scheduler-state workload:
     * executing = remaining length,
     * waiting = complete length.
     */
    private double getVmRemainingWorkload(Vm vm) {

        if (vm == null) {
            return 0.0;
        }

        final CloudletScheduler scheduler =
                vm.getCloudletScheduler();

        if (scheduler == null) {
            return 0.0;
        }

        double workload = 0.0;

        for (CloudletExecution execution :
                scheduler.getCloudletExecList()) {

            if (execution == null) {
                continue;
            }

            final double remaining =
                    execution
                            .getRemainingCloudletLength();

            if (remaining > 0.0) {
                workload += remaining;
            }
        }

        for (CloudletExecution execution :
                scheduler.getCloudletWaitingList()) {

            if (execution == null) {
                continue;
            }

            workload +=
                    Math.max(
                            0.0,
                            execution
                                    .getCloudletLength());
        }

        return workload;
    }

    private double safeUtilization(Vm vm) {

        if (vm == null) {
            return 0.0;
        }

        try {
            return clamp(
                    vm.getCpuPercentUtilization());
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    /**
     * Adaptive multi-objective reward.
     */
    private double calculateReward(
            DqnState previous,
            DqnState current) {

        final double previousQos =
                previous.getFeature(2)
                        + previous.getFeature(3);

        final double currentQos =
                current.getFeature(2)
                        + current.getFeature(3);

        final double qosImprovement =
                previousQos
                        - currentQos;

        final double energyImprovement =
                previous.getFeature(4)
                        - current.getFeature(4);

        final double costImprovement =
                previous.getFeature(5)
                        - current.getFeature(5);

        final double balanceImprovement =
                previous.getFeature(1)
                        - current.getFeature(1);

        final double utilizationImprovement =
                current.getFeature(0)
                        - previous.getFeature(0);

        final double queuePressure =
                current.getFeature(2);

        final double qosWeight =
                0.30
                        + 0.30
                        * queuePressure;

        final double energyWeight =
                0.40
                        - 0.15
                        * queuePressure;

        final double costWeight =
                1.0
                        - qosWeight
                        - energyWeight;

        double reward =
                qosWeight
                        * qosImprovement
                + energyWeight
                        * energyImprovement
                + costWeight
                        * costImprovement
                + 0.15
                        * balanceImprovement
                + 0.05
                        * utilizationImprovement;

        if (Double.isNaN(reward)
                || Double.isInfinite(reward)) {
            return 0.0;
        }

        return Math.max(
                -1.0,
                Math.min(
                        1.0,
                        reward));
    }

    private double clamp(double value) {

        if (Double.isNaN(value)
                || Double.isInfinite(value)) {
            return 0.0;
        }

        return Math.max(
                0.0,
                Math.min(
                        1.0,
                        value));
    }

    private record CandidateScore(
            Vm vm,
            double score) {
    }
}
