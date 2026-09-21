package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;
import org.cloudsimplus.vms.Vm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class AdvancedDqnBroker extends DatacenterBrokerSimple {

    private final DqnAgent dqnAgent;
    private DqnState lastState;
    private int lastAction;
    private double lastGlobalMakespan;

    private final Map<Vm, Double> virtualEftMap = new HashMap<>();

    public AdvancedDqnBroker(CloudSimPlus simulation, String name, DqnAgent agent) {
        super(simulation, name);
        if (agent == null) {
            throw new IllegalArgumentException("DQN agent cannot be null.");
        }
        this.dqnAgent = agent;
        this.lastAction = 0;
        this.lastGlobalMakespan = 0.0;

        setShutdownWhenIdle(false);
        setVmDestructionDelayFunction(null);
        setVmMapper(this::mapTaskWithDqn);
    }

    private Vm mapTaskWithDqn(Cloudlet cloudlet) {
        final List<Vm> vmList = getVmExecList();
        if (vmList.isEmpty()) return Vm.NULL;

        double currentGlobalMakespan = 0.0;
        for (Vm v : vmList) {
            currentGlobalMakespan = Math.max(currentGlobalMakespan, getVirtualEft(v));
        }

        final DqnState currentState = observeState(vmList, cloudlet, currentGlobalMakespan);

        if (lastState != null) {
            double deltaMakespan = currentGlobalMakespan - lastGlobalMakespan;
            double reward = deltaMakespan <= 0.0 ? 1.0 : - (deltaMakespan * 2.0);
            dqnAgent.recordTransition(lastState, lastAction, reward, currentState, getCloudletWaitingList().isEmpty());
        }

        final int action = dqnAgent.selectAction(currentState);
        final double length = cloudlet == null ? 0.0 : Math.max(0.0, cloudlet.getLength());

        Vm selectedVm = null;
        double bestScore = Double.MAX_VALUE;
        double assignedEft = 0.0;

        for (Vm vm : vmList) {
            if (vm == null) continue;
            final double mips = Math.max(1.0, vm.getTotalMipsCapacity());
            final double baseEft = getVirtualEft(vm);
            final double execTime = length / mips;
            final double projectedEft = baseEft + execTime;

            double score;
            switch (action) {
                case 0 -> {
                    double makespanExtension = Math.max(0.0, projectedEft - currentGlobalMakespan);
                    score = (makespanExtension * 100.0) + projectedEft;
                }
                case 1 -> {
                    if (projectedEft <= currentGlobalMakespan) {
                        score = currentGlobalMakespan - projectedEft;
                    } else {
                        score = 10_000.0 + projectedEft;
                    }
                }
                case 2 -> {
                    score = projectedEft + (0.25 * execTime);
                }
                default -> {
                    score = projectedEft;
                }
            }

            if (selectedVm == null || score < bestScore) {
                bestScore = score;
                selectedVm = vm;
                assignedEft = projectedEft;
            } else if (Math.abs(score - bestScore) < 1e-9 && mips > selectedVm.getTotalMipsCapacity()) {
                selectedVm = vm;
                assignedEft = projectedEft;
            }
        }

        virtualEftMap.put(selectedVm, assignedEft);

        lastState = currentState;
        lastAction = action;
        lastGlobalMakespan = Math.max(currentGlobalMakespan, assignedEft);

        return selectedVm;
    }

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
        return Math.max(clock + (remainingMi / mips), virtualEftMap.getOrDefault(vm, clock));
    }

    private DqnState observeState(List<Vm> vmList, Cloudlet incomingCloudlet, double currentMaxEft) {
        if (vmList.isEmpty()) return new DqnState(0, 0, 0, 0, 0, 0, 0, 0);

        double totalUtilization = 0.0;
        double totalWorkload = 0.0;
        final double[] utilizations = new double[vmList.size()];
        int index = 0;

        for (Vm vm : vmList) {
            double u = safeUtilization(vm);
            utilizations[index++] = u;
            totalUtilization += u;
            if (vm.getCloudletScheduler() != null) {
                for (CloudletExecution ce : vm.getCloudletScheduler().getCloudletExecList()) {
                    totalWorkload += Math.max(0.0, ce.getRemainingCloudletLength());
                }
                for (CloudletExecution ce : vm.getCloudletScheduler().getCloudletWaitingList()) {
                    totalWorkload += Math.max(0.0, ce.getCloudletLength());
                }
            }
        }

        final double meanUtil = totalUtilization / vmList.size();
        double variance = 0.0;
        for (double u : utilizations) variance += Math.pow(u - meanUtil, 2);
        final double stdDev = Math.sqrt(variance / vmList.size());

        final double queueProgress = 1.0 - (getCloudletWaitingList().size() / 1000.0);
        final double incomingLength = incomingCloudlet == null ? 0.0 : incomingCloudlet.getLength();
        final double normalizedIncoming = incomingLength / 5000.0;
        final double normalizedRemaining = totalWorkload / 80_000_000.0;

        return new DqnState(meanUtil, stdDev, queueProgress, normalizedRemaining, 
                            meanUtil, meanUtil, normalizedIncoming, stdDev);
    }

    private double safeUtilization(Vm vm) {
        if (vm == null) return 0.0;
        try {
            return Math.max(0.0, Math.min(1.0, vm.getCpuPercentUtilization()));
        } catch (Exception ignored) {
            return 0.0;
        }
    }
}