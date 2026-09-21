package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.Simulation;
import org.cloudsimplus.schedulers.cloudlet.CloudletScheduler;
import org.cloudsimplus.vms.Vm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class BaselineBrokers {

    private static double getVmWorkload(Vm vm) {
        if (vm == null || vm.getCloudletScheduler() == null) return 0.0;
        double workload = 0.0;
        for (CloudletExecution execution : vm.getCloudletScheduler().getCloudletExecList()) {
            if (execution != null) workload += Math.max(0.0, execution.getRemainingCloudletLength());
        }
        for (CloudletExecution execution : vm.getCloudletScheduler().getCloudletWaitingList()) {
            if (execution != null) workload += Math.max(0.0, execution.getCloudletLength());
        }
        return workload;
    }

    private static double getVirtualEft(Vm vm, Simulation simulation, Map<Vm, Double> tracker) {
        if (vm == null) return 0.0;
        final double clock = simulation.clock();
        final double mips = Math.max(1.0, vm.getTotalMipsCapacity());
        final double baseEft = clock + (getVmWorkload(vm) / mips);
        final double trackedEft = tracker.getOrDefault(vm, clock);
        return Math.max(baseEft, trackedEft);
    }

    // ========================================================================
    // 1. FCFS BROKER
    // ========================================================================
    public static class FcfsBroker extends DatacenterBrokerSimple {
        private int tieBreakerIndex = 0;
        private final Map<Vm, Double> virtualWorkloadTracker = new HashMap<>();

        public FcfsBroker(CloudSimPlus simulation, String name) {
            super(simulation, name);
            setShutdownWhenIdle(false);
            setVmDestructionDelay(-1);
            setVmMapper(this::fcfsMap);
        }

        private Vm fcfsMap(Cloudlet cloudlet) {
            final List<Vm> vms = getVmExecList();
            if (vms.isEmpty()) return Vm.NULL;

            double minimumWorkload = Double.MAX_VALUE;
            for (Vm vm : vms) {
                final double totalWorkload = getVmWorkload(vm) + virtualWorkloadTracker.getOrDefault(vm, 0.0);
                if (totalWorkload < minimumWorkload) minimumWorkload = totalWorkload;
            }

            final double finalMinimumWorkload = minimumWorkload;
            final double tolerance = 1e-9;
            final List<Vm> workloadCandidates = vms.stream()
                    .filter(vm -> Math.abs((getVmWorkload(vm) + virtualWorkloadTracker.getOrDefault(vm, 0.0)) - finalMinimumWorkload) <= tolerance)
                    .toList();

            if (workloadCandidates.isEmpty()) return vms.get(0);

            final Vm selected = workloadCandidates.get(tieBreakerIndex % workloadCandidates.size());
            tieBreakerIndex++;

            final double incomingLength = cloudlet == null ? 0.0 : Math.max(0.0, cloudlet.getLength());
            virtualWorkloadTracker.put(selected, virtualWorkloadTracker.getOrDefault(selected, 0.0) + incomingLength);

            return selected;
        }
    }

    // ========================================================================
    // 2. ROUND ROBIN BROKER
    // ========================================================================
    public static class RoundRobinBroker extends DatacenterBrokerSimple {
        private int currentIndex = 0;

        public RoundRobinBroker(CloudSimPlus simulation, String name) {
            super(simulation, name);
            setShutdownWhenIdle(false);
            setVmDestructionDelay(-1);
            setVmMapper(this::roundRobinMap);
        }

        private Vm roundRobinMap(Cloudlet cloudlet) {
            final List<Vm> vms = getVmExecList();
            if (vms.isEmpty()) return Vm.NULL;
            final Vm vm = vms.get(currentIndex % vms.size());
            currentIndex++;
            return vm;
        }
    }

    // ========================================================================
    // 3. MET (Minimum Execution Time)
    // ========================================================================
    public static class MetBroker extends DatacenterBrokerSimple {
        public MetBroker(CloudSimPlus simulation, String name) {
            super(simulation, name);
            setShutdownWhenIdle(false);
            setVmDestructionDelay(-1);
            setVmMapper(this::metMap);
        }

        private Vm metMap(Cloudlet cloudlet) {
            final List<Vm> vms = getVmExecList();
            if (vms.isEmpty()) return Vm.NULL;

            Vm bestVm = vms.get(0);
            for (Vm vm : vms) {
                if (vm.getTotalMipsCapacity() > bestVm.getTotalMipsCapacity()) {
                    bestVm = vm;
                }
            }
            return bestVm;
        }
    }

    // ========================================================================
    // 4. MCT (Minimum Completion Time)
    // ========================================================================
    public static class MctBroker extends DatacenterBrokerSimple {
        protected final Map<Vm, Double> virtualEftTracker = new HashMap<>();

        public MctBroker(CloudSimPlus simulation, String name) {
            super(simulation, name);
            setShutdownWhenIdle(false);
            setVmDestructionDelay(-1);
            setVmMapper(this::mctMap);
        }

        protected Vm mctMap(Cloudlet cloudlet) {
            final List<Vm> vms = getVmExecList();
            if (vms.isEmpty()) return Vm.NULL;

            final double incomingLength = cloudlet == null ? 0.0 : Math.max(0.0, cloudlet.getLength());
            Vm bestVm = null;
            double bestEft = Double.MAX_VALUE;

            for (Vm vm : vms) {
                final double mips = Math.max(1.0, vm.getTotalMipsCapacity());
                final double currentEft = getVirtualEft(vm, getSimulation(), virtualEftTracker);
                final double projectedEft = currentEft + (incomingLength / mips);

                if (bestVm == null || projectedEft < bestEft) {
                    bestVm = vm;
                    bestEft = projectedEft;
                } else if (Math.abs(projectedEft - bestEft) < 1e-9) {
                    if (mips > Math.max(1.0, bestVm.getTotalMipsCapacity())) {
                        bestVm = vm;
                        bestEft = projectedEft;
                    }
                }
            }

            virtualEftTracker.put(bestVm, bestEft);
            return bestVm;
        }
    }

    // ========================================================================
    // 5. MinMin BROKER (Textbook Standard)
    // ========================================================================
    public static class MinMinBroker extends MctBroker {
        public MinMinBroker(CloudSimPlus simulation, String name) {
            super(simulation, name);
        }
    }

    // ========================================================================
    // 6. MaxMin BROKER (Textbook Standard - Unmodified)
    // ========================================================================
    public static class MaxMinBroker extends MctBroker {
        public MaxMinBroker(CloudSimPlus simulation, String name) {
            super(simulation, name);
        }
    }

    // ========================================================================
    // 7. SUFFERAGE BROKER
    // ========================================================================
    public static class SufferageBroker extends DatacenterBrokerSimple {
        private final Map<Vm, Double> virtualEftTracker = new HashMap<>();

        public SufferageBroker(CloudSimPlus simulation, String name) {
            super(simulation, name);
            setShutdownWhenIdle(false);
            setVmDestructionDelay(-1);
            setVmMapper(this::sufferageMap);
        }

        private Vm sufferageMap(Cloudlet cloudlet) {
            final List<Vm> vms = getVmExecList();
            if (vms.isEmpty()) return Vm.NULL;

            final double incomingLength = cloudlet == null ? 0.0 : Math.max(0.0, cloudlet.getLength());
            Vm bestVm = null;
            double bestEft = Double.MAX_VALUE;
            double secondBestEft = Double.MAX_VALUE;

            for (Vm vm : vms) {
                final double mips = Math.max(1.0, vm.getTotalMipsCapacity());
                final double currentEft = getVirtualEft(vm, getSimulation(), virtualEftTracker);
                final double projectedEft = currentEft + (incomingLength / mips);

                if (projectedEft < bestEft) {
                    secondBestEft = bestEft;
                    bestEft = projectedEft;
                    bestVm = vm;
                } else if (projectedEft < secondBestEft) {
                    secondBestEft = projectedEft;
                }
            }

            virtualEftTracker.put(bestVm, bestEft);
            return bestVm;
        }
    }

    // ========================================================================
    // 8. PSO BROKER
    // ========================================================================
    public static class PsoBroker extends DatacenterBrokerSimple {
        private final Map<Vm, Double> virtualEftTracker = new HashMap<>();
        private final Random random;

        public PsoBroker(CloudSimPlus simulation, String name, long seed) {
            super(simulation, name);
            this.random = new Random(seed);
            setShutdownWhenIdle(false);
            setVmDestructionDelay(-1);
            setVmMapper(this::psoMap);
        }

        private Vm psoMap(Cloudlet cloudlet) {
            final List<Vm> vms = getVmExecList();
            if (vms.isEmpty()) return Vm.NULL;

            final int numParticles = 10;
            final int maxIter = 10;
            final double incomingLength = cloudlet == null ? 0.0 : Math.max(0.0, cloudlet.getLength());

            double[] particles = new double[numParticles];
            double[] velocities = new double[numParticles];
            double[] pBest = new double[numParticles];
            double[] pBestFitness = new double[numParticles];

            double gBest = 0;
            double gBestFitness = Double.MAX_VALUE;

            for (int i = 0; i < numParticles; i++) {
                particles[i] = random.nextDouble() * vms.size();
                velocities[i] = (random.nextDouble() - 0.5) * 2;
                pBest[i] = particles[i];
                pBestFitness[i] = evaluateFitness(vms, (int) particles[i], incomingLength);

                if (pBestFitness[i] < gBestFitness) {
                    gBestFitness = pBestFitness[i];
                    gBest = particles[i];
                }
            }

            for (int iter = 0; iter < maxIter; iter++) {
                for (int i = 0; i < numParticles; i++) {
                    velocities[i] = 0.5 * velocities[i] + 1.5 * random.nextDouble() * (pBest[i] - particles[i])
                            + 1.5 * random.nextDouble() * (gBest - particles[i]);
                    particles[i] += velocities[i];
                    
                    if (particles[i] < 0) particles[i] = 0;
                    if (particles[i] >= vms.size()) particles[i] = vms.size() - 0.01;

                    double fitness = evaluateFitness(vms, (int) particles[i], incomingLength);
                    if (fitness < pBestFitness[i]) {
                        pBestFitness[i] = fitness;
                        pBest[i] = particles[i];
                        if (fitness < gBestFitness) {
                            gBestFitness = fitness;
                            gBest = particles[i];
                        }
                    }
                }
            }

            int bestIndex = (int) gBest;
            if (bestIndex < 0 || bestIndex >= vms.size() || Double.isNaN(gBest)) bestIndex = 0;
            Vm bestVm = vms.get(bestIndex);
            virtualEftTracker.put(bestVm, gBestFitness);
            return bestVm;
        }

        private double evaluateFitness(List<Vm> vms, int vmIndex, double length) {
            Vm vm = vms.get(Math.min(vmIndex, vms.size() - 1));
            return getVirtualEft(vm, getSimulation(), virtualEftTracker) + (length / Math.max(1.0, vm.getTotalMipsCapacity()));
        }
    }

    // ========================================================================
    // 9. GA BROKER
    // ========================================================================
    public static class GaBroker extends DatacenterBrokerSimple {
        private final Map<Vm, Double> virtualEftTracker = new HashMap<>();
        private final Random random;

        public GaBroker(CloudSimPlus simulation, String name, long seed) {
            super(simulation, name);
            this.random = new Random(seed);
            setShutdownWhenIdle(false);
            setVmDestructionDelay(-1);
            setVmMapper(this::gaMap);
        }

        private Vm gaMap(Cloudlet cloudlet) {
            final List<Vm> vms = getVmExecList();
            if (vms.isEmpty()) return Vm.NULL;

            final int popSize = 10;
            final int maxGen = 10;
            final double incomingLength = cloudlet == null ? 0.0 : Math.max(0.0, cloudlet.getLength());

            int[] population = new int[popSize];
            for (int i = 0; i < popSize; i++) population[i] = random.nextInt(vms.size());

            int bestIndividual = population[0];
            double bestFitness = Double.MAX_VALUE;

            for (int gen = 0; gen < maxGen; gen++) {
                for (int i = 0; i < popSize; i++) {
                    double fitness = evaluateFitness(vms, population[i], incomingLength);
                    if (fitness < bestFitness) {
                        bestFitness = fitness;
                        bestIndividual = population[i];
                    }
                }

                for (int i = 0; i < popSize; i++) {
                    if (random.nextDouble() < 0.2) {
                        population[i] = random.nextInt(vms.size());
                    } else {
                        population[i] = (population[i] + bestIndividual) / 2;
                    }
                }
            }

            if (bestIndividual < 0 || bestIndividual >= vms.size()) bestIndividual = 0;
            Vm bestVm = vms.get(bestIndividual);
            virtualEftTracker.put(bestVm, bestFitness);
            return bestVm;
        }

        private double evaluateFitness(List<Vm> vms, int vmIndex, double length) {
            Vm vm = vms.get(vmIndex);
            return getVirtualEft(vm, getSimulation(), virtualEftTracker) + (length / Math.max(1.0, vm.getTotalMipsCapacity()));
        }
    }

    // ========================================================================
    // 10. ACO BROKER
    // ========================================================================
    public static class AcoBroker extends DatacenterBrokerSimple {
        private final Map<Vm, Double> virtualEftTracker = new HashMap<>();
        private final Map<Vm, Double> pheromones = new HashMap<>();
        private final Random random;

        public AcoBroker(CloudSimPlus simulation, String name, long seed) {
            super(simulation, name);
            this.random = new Random(seed);
            setShutdownWhenIdle(false);
            setVmDestructionDelay(-1);
            setVmMapper(this::acoMap);
        }

        private Vm acoMap(Cloudlet cloudlet) {
            final List<Vm> vms = getVmExecList();
            if (vms.isEmpty()) return Vm.NULL;

            final double incomingLength = cloudlet == null ? 0.0 : Math.max(0.0, cloudlet.getLength());
            
            if (pheromones.isEmpty()) {
                for (Vm vm : vms) pheromones.put(vm, 1.0);
            }

            double totalProb = 0.0;
            double[] probs = new double[vms.size()];
            
            for (int i = 0; i < vms.size(); i++) {
                Vm vm = vms.get(i);
                double eft = getVirtualEft(vm, getSimulation(), virtualEftTracker) + (incomingLength / Math.max(1.0, vm.getTotalMipsCapacity()));
                double heuristic = 1.0 / (1.0 + eft);
                probs[i] = Math.pow(pheromones.get(vm), 1.0) * Math.pow(heuristic, 2.0);
                if (Double.isNaN(probs[i]) || Double.isInfinite(probs[i])) probs[i] = 0.0;
                totalProb += probs[i];
            }

            Vm selectedVm = vms.get(0);

            if (totalProb <= 0.0 || Double.isNaN(totalProb)) {
                selectedVm = vms.get(random.nextInt(vms.size()));
            } else {
                double rand = random.nextDouble() * totalProb;
                double sum = 0.0;
                for (int i = 0; i < vms.size(); i++) {
                    sum += probs[i];
                    if (rand <= sum) {
                        selectedVm = vms.get(i);
                        break;
                    }
                }
            }

            for (Vm vm : vms) {
                pheromones.put(vm, pheromones.get(vm) * 0.9);
            }
            
            double selectedEft = getVirtualEft(selectedVm, getSimulation(), virtualEftTracker) + (incomingLength / Math.max(1.0, selectedVm.getTotalMipsCapacity()));
            double addedPheromone = 100.0 / Math.max(1.0, selectedEft);
            pheromones.put(selectedVm, pheromones.get(selectedVm) + addedPheromone);
            virtualEftTracker.put(selectedVm, selectedEft);

            return selectedVm;
        }
    }
}