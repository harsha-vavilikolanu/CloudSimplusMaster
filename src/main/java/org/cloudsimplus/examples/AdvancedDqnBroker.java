package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;
import org.cloudsimplus.vms.Vm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Proposed Double-DQN broker aggressively optimized for Makespan.
 * Solves the t=0 mapping storm via Virtual EFT Tracking.
 */
public class AdvancedDqnBroker extends DatacenterBrokerSimple {

    private static final double POWER_IDLE = 175.0;
    private static final double POWER_MAX = 375.0;
    private static final double COST_PER_CPU_SEC = 0.03;
    private static final double COST_PER_RAM_MB_SEC = 0.0005;

    private static final double MAX_QUEUE_LENGTH = 1000.0;
    private static final double MAX_SYSTEM_WORKLOAD = 80_000_000.0;
    private static final double MAX_INCOMING_WORKLOAD = 5_000.0;
    private static final double MAX_COST_RATE = 1.0;

    public static final int CANDIDATE_COUNT = 3;

    private final DqnAgent dqnAgent;
    private DqnState lastState;
    private int lastAction;
    private Cloudlet lastCloudlet;

    /*
     * CRITICAL: Tracks pending workload assignments during the mapping loop
     * before the simulation clock actually advances.
     */
    private final Map<Vm, Double> virtualEftMap = new HashMap<>();

    public AdvancedDqnBroker(CloudSimPlus simulation, String name, DqnAgent agent) {
        super(simulation, name);
        if (agent == null) {
            throw new IllegalArgumentException("DQN agent cannot be null.");
        }
        this.dqnAgent = agent;
        this.lastAction = 0;

        setShutdownWhenIdle(false);
        setVmDestructionDelayFunction(null);
        setVmMapper(this::mapTaskWithDqn);
    }

    private Vm mapTaskWithDqn(Cloudlet cloudlet) {
        final List<Vm> vmList = getVmExecList();

        if (vmList.isEmpty()) {
            return Vm.NULL;
        }

        final DqnState currentState = observeState(vmList, cloudlet);

        if (lastState != null && lastCloudlet != null) {
            final double reward = calculateReward(lastState, currentState);
            final boolean done = getCloudletWaitingList().isEmpty();
            dqnAgent.recordTransition(lastState, lastAction, reward, currentState, done);
        }

        final List<Vm> candidates = rankCandidateVms(vmList, cloudlet);

        if (candidates.isEmpty()) {
            return vmList.get(0);
        }

        final int action = dqnAgent.selectAction(currentState);
        final int candidateIndex = Math.floorMod(action, candidates.size());
        final Vm selectedVm = candidates.get(candidateIndex);

        /*
         * Update Virtual EFT so the next task evaluated in the t=0 mapping loop
         * instantly sees the load added by this task, forcing perfect distribution.
         */
        final double mips = Math.max(1.0, selectedVm.getTotalMipsCapacity());
        final double currentEft = getVirtualEft(selectedVm);
        virtualEftMap.put(selectedVm, currentEft + (Math.max(0.0, cloudlet.getLength()) / mips));

        lastState = currentState;
        lastAction = candidateIndex;
        lastCloudlet = cloudlet;

        return selectedVm;
    }

    /**
     * Accurately calculates when a VM will be completely free, 
     * accounting for both CloudSim's engine and our mapping loop tracker.
     */
    private double getVirtualEft(Vm vm) {
        if (vm == null) return 0.0;
        
        final double clock = getSimulation().clock();
        double remainingMi = 0.0;
        
        final CloudletScheduler scheduler = vm.getCloudletScheduler();
        if (scheduler != null) {
            for (CloudletExecution ce : scheduler.getCloudletExecList()) {
                if (ce != null) remainingMi += Math.max(0.0, ce.getRemainingCloudletLength());
            }
            for (CloudletExecution ce : scheduler.getCloudletWaitingList()) {
                if (ce != null) remainingMi += Math.max(0.0, ce.getCloudletLength());
            }
        }
        
        final double mips = Math.max(1.0, vm.getTotalMipsCapacity());
        final double actualEft = clock + (remainingMi / mips);
        final double trackedEft = virtualEftMap.getOrDefault(vm, clock);
        
        // Use whichever is larger to prevent double counting or stale data
        return Math.max(actualEft, trackedEft);
    }

    private List<Vm> rankCandidateVms(List<Vm> vmList, Cloudlet incomingCloudlet) {
        final double incomingLength = incomingCloudlet == null ? 0.0 : Math.max(0.0, incomingCloudlet.getLength());
        final List<CandidateScore> scored = new ArrayList<>(vmList.size());

        for (Vm vm : vmList) {
            if (vm == null) continue;

            final double mips = Math.max(1.0, vm.getTotalMipsCapacity());
            final double baseEft = getVirtualEft(vm);
            
            // Core optimization target: Minimum Completion Time
            final double projectedEft = baseEft + (incomingLength / mips);
            
            // Minor penalty to break ties and shape multi-objective output slightly
            final double energyPenalty = safeUtilization(vm) * 0.001; 

            final double score = projectedEft + energyPenalty;
            scored.add(new CandidateScore(vm, score));
        }

        // Sort strictly by lowest projected completion time
        scored.sort(Comparator.comparingDouble(CandidateScore::score)
                              .thenComparingLong(c -> c.vm().getId()));

        final int count = Math.min(CANDIDATE_COUNT, scored.size());
        final List<Vm> candidates = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            candidates.add(scored.get(i).vm());
        }

        return candidates;
    }

    private DqnState observeState(List<Vm> vmList, Cloudlet incomingCloudlet) {
        if (vmList.isEmpty()) {
            return new DqnState(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
        }

        double totalUtilization = 0.0;
        double totalWorkload = 0.0;
        final double[] utilizations = new double[vmList.size()];
        int index = 0;

        for (Vm vm : vmList) {
            final double utilization = safeUtilization(vm);
            utilizations[index++] = utilization;
            totalUtilization += utilization;
            
            // State observation uses actual CloudSim scheduler to see execution pressure
            double workload = 0.0;
            if (vm.getCloudletScheduler() != null) {
                for (CloudletExecution ce : vm.getCloudletScheduler().getCloudletExecList()) {
                    workload += Math.max(0.0, ce.getRemainingCloudletLength());
                }
                for (CloudletExecution ce : vm.getCloudletScheduler().getCloudletWaitingList()) {
                    workload += Math.max(0.0, ce.getCloudletLength());
                }
            }
            totalWorkload += workload;
        }

        final double meanUtilization = totalUtilization / vmList.size();
        double variance = 0.0;
        for (double utilization : utilizations) {
            variance += Math.pow(utilization - meanUtilization, 2);
        }
        final double stdDev = Math.sqrt(variance / vmList.size());

        final double queueLoad = clamp(getCloudletWaitingList().size() / MAX_QUEUE_LENGTH);
        final double incomingLength = incomingCloudlet == null ? 0.0 : Math.max(0.0, incomingCloudlet.getLength());
        final double normalizedIncoming = clamp(incomingLength / MAX_INCOMING_WORKLOAD);
        final double normalizedRemaining = clamp((totalWorkload + incomingLength) / MAX_SYSTEM_WORKLOAD);

        final double currentPower = POWER_IDLE + (POWER_MAX - POWER_IDLE) * meanUtilization;
        final double normalizedEnergy = clamp(currentPower / POWER_MAX);
        final double currentCostRate = meanUtilization * COST_PER_CPU_SEC + COST_PER_RAM_MB_SEC * 1024.0;
        final double normalizedCost = clamp(currentCostRate / MAX_COST_RATE);
        final double workloadImbalance = clamp(stdDev); 

        return new DqnState(meanUtilization, stdDev, queueLoad, normalizedRemaining, 
                            normalizedEnergy, normalizedCost, normalizedIncoming, workloadImbalance);
    }

    private double safeUtilization(Vm vm) {
        if (vm == null) return 0.0;
        try {
            return clamp(vm.getCpuPercentUtilization());
        } catch (Exception ignored) {
            return 0.0;
        }
    }

    private double calculateReward(DqnState previous, DqnState current) {
        final double previousWorkload = previous.getFeature(2) + previous.getFeature(3);
        final double currentWorkload = current.getFeature(2) + current.getFeature(3);
        
        final double workloadImprovement = previousWorkload - currentWorkload;
        final double energyImprovement = previous.getFeature(4) - current.getFeature(4);
        
        double reward = (0.8 * workloadImprovement) + (0.2 * energyImprovement);

        if (current.getFeature(2) > 0.90) reward -= 0.20;
        
        if (Double.isNaN(reward) || Double.isInfinite(reward)) {
            return 0.0;
        }
        return Math.max(-1.0, Math.min(1.0, reward));
    }

    private double clamp(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return 0.0;
        return Math.max(0.0, Math.min(1.0, value));
    }

    private record CandidateScore(Vm vm, double score) {}
}