package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.vms.Vm;

import java.util.List;

public class BaselineBrokers {

    /*
     * ============================================================
     * COMMON HELPER
     * ============================================================
     *
     * Returns the actual current workload of a VM.
     *
     * Running Cloudlets:
     *     remaining Cloudlet length
     *
     * Waiting Cloudlets:
     *     complete Cloudlet length
     *
     * ============================================================
     */
    private static double getVmWorkload(Vm vm) {

        if (vm == null || vm.getCloudletScheduler() == null) {
            return 0.0;
        }

        double workload = 0.0;

        /*
         * Running Cloudlets
         */
        for (CloudletExecution execution :
                vm.getCloudletScheduler().getCloudletExecList()) {

            if (execution == null) {
                continue;
            }

            workload += Math.max(
                    0.0,
                    execution.getRemainingCloudletLength()
            );
        }

        /*
         * Waiting Cloudlets
         */
        for (CloudletExecution execution :
                vm.getCloudletScheduler().getCloudletWaitingList()) {

            if (execution == null) {
                continue;
            }

            workload += Math.max(
                    0.0,
                    execution.getCloudletLength()
            );
        }

        return workload;
    }


    /*
     * ============================================================
     * FCFS BROKER
     * ============================================================
     *
     * Load-aware FCFS-style mapping.
     *
     * Priority:
     * 1. Lowest actual scheduler workload
     * 2. Lowest CPU utilization
     * 3. Rotating tie breaker
     *
     * ============================================================
     */
    public static class FcfsBroker
            extends DatacenterBrokerSimple {

        private int tieBreakerIndex = 0;

        public FcfsBroker(
                CloudSimPlus simulation,
                String name) {

            super(simulation, name);

            /*
             * Keep simulation alive until all Cloudlets finish.
             */
            setShutdownWhenIdle(false);

            /*
             * Do not automatically destroy idle VMs.
             */
            setVmDestructionDelay(-1);

            /*
             * Register VM mapper.
             */
            setVmMapper(this::fcfsMap);
        }

        private Vm fcfsMap(Cloudlet cloudlet) {

            final List<Vm> vms = getVmExecList();

            if (vms.isEmpty()) {
                return Vm.NULL;
            }

            /*
             * ----------------------------------------------------
             * STEP 1:
             * Find the minimum actual scheduler workload.
             * ----------------------------------------------------
             */
            double minimumWorkload = Double.MAX_VALUE;

            for (Vm vm : vms) {

                final double workload =
                        getVmWorkload(vm);

                if (workload < minimumWorkload) {
                    minimumWorkload = workload;
                }
            }

            /*
             * Make a final copy because this value is used
             * inside a lambda expression below.
             */
            final double finalMinimumWorkload =
                    minimumWorkload;

            final double tolerance = 1e-9;

            /*
             * ----------------------------------------------------
             * STEP 2:
             * Find all VMs having approximately the same minimum
             * workload.
             * ----------------------------------------------------
             */
            final List<Vm> workloadCandidates =
                    vms.stream()
                            .filter(vm ->
                                    Math.abs(
                                            getVmWorkload(vm)
                                                    - finalMinimumWorkload
                                    ) <= tolerance
                            )
                            .toList();

            if (workloadCandidates.isEmpty()) {
                return vms.get(0);
            }

            /*
             * ----------------------------------------------------
             * STEP 3:
             * Find minimum CPU utilization among those VMs.
             * ----------------------------------------------------
             */
            double minimumCpu = Double.MAX_VALUE;

            for (Vm vm : workloadCandidates) {

                final double cpu =
                        vm.getCpuPercentUtilization();

                if (cpu < minimumCpu) {
                    minimumCpu = cpu;
                }
            }

            /*
             * Final copy for lambda usage.
             */
            final double finalMinimumCpu =
                    minimumCpu;

            /*
             * ----------------------------------------------------
             * STEP 4:
             * Keep VMs with minimum CPU utilization.
             * ----------------------------------------------------
             */
            final List<Vm> cpuCandidates =
                    workloadCandidates.stream()
                            .filter(vm ->
                                    Math.abs(
                                            vm.getCpuPercentUtilization()
                                                    - finalMinimumCpu
                                    ) <= tolerance
                            )
                            .toList();

            if (cpuCandidates.isEmpty()) {
                return workloadCandidates.get(0);
            }

            /*
             * ----------------------------------------------------
             * STEP 5:
             * Rotate ties.
             *
             * This prevents VM 0 from receiving every Cloudlet
             * when all VMs initially have zero workload and zero
             * CPU utilization.
             * ----------------------------------------------------
             */
            final Vm selected =
                    cpuCandidates.get(
                            tieBreakerIndex
                                    % cpuCandidates.size()
                    );

            tieBreakerIndex++;

            return selected;
        }
    }


    /*
     * ============================================================
     * ROUND ROBIN BROKER
     * ============================================================
     *
     * Explicit cyclic VM assignment.
     *
     * VM sequence:
     *
     * VM0 -> VM1 -> VM2 -> ... -> VM99
     * -> VM0 -> VM1 -> ...
     *
     * ============================================================
     */
    public static class RoundRobinBroker
            extends DatacenterBrokerSimple {

        private int currentIndex = 0;

        public RoundRobinBroker(
                CloudSimPlus simulation,
                String name) {

            super(simulation, name);

            /*
             * Keep simulation running until Cloudlets finish.
             */
            setShutdownWhenIdle(false);

            /*
             * Keep VMs alive.
             */
            setVmDestructionDelay(-1);

            /*
             * Register mapper.
             */
            setVmMapper(this::roundRobinMap);
        }

        private Vm roundRobinMap(Cloudlet cloudlet) {

            final List<Vm> vms = getVmExecList();

            if (vms.isEmpty()) {
                return Vm.NULL;
            }

            /*
             * Select VM cyclically.
             */
            final Vm vm =
                    vms.get(
                            currentIndex
                                    % vms.size()
                    );

            currentIndex++;

            return vm;
        }
    }


    /*
     * ============================================================
     * MAX-MIN BROKER
     * ============================================================
     *
     * Projected-completion-time version.
     *
     * Formula:
     *
     *      Current Workload + Incoming Cloudlet Length
     *      --------------------------------------------
     *                     VM Total MIPS
     *
     * The VM with the smallest projected completion time
     * is selected.
     *
     * This avoids continuously selecting the fastest VM once
     * that VM has accumulated significant workload.
     *
     * ============================================================
     */
    public static class MaxMinBroker
            extends DatacenterBrokerSimple {

        public MaxMinBroker(
                CloudSimPlus simulation,
                String name) {

            super(simulation, name);

            /*
             * Keep simulation running until all Cloudlets finish.
             */
            setShutdownWhenIdle(false);

            /*
             * Keep VMs alive.
             */
            setVmDestructionDelay(-1);

            /*
             * Register mapper.
             */
            setVmMapper(this::maxMinMap);
        }

        private Vm maxMinMap(Cloudlet cloudlet) {

            final List<Vm> vms = getVmExecList();

            if (vms.isEmpty()) {
                return Vm.NULL;
            }

            /*
             * ----------------------------------------------------
             * Incoming Cloudlet length.
             * ----------------------------------------------------
             */
            final double incomingLength =
                    cloudlet == null
                            ? 0.0
                            : Math.max(
                                    0.0,
                                    cloudlet.getLength()
                            );

            /*
             * ----------------------------------------------------
             * Variables for best VM.
             * ----------------------------------------------------
             */
            Vm bestVm = null;

            double bestProjectedTime =
                    Double.MAX_VALUE;

            double bestMips =
                    -1.0;

            final double tolerance = 1e-9;

            /*
             * ----------------------------------------------------
             * Examine every VM.
             * ----------------------------------------------------
             */
            for (Vm vm : vms) {

                if (vm == null) {
                    continue;
                }

                /*
                 * Current scheduler workload.
                 *
                 * Running Cloudlets contribute their remaining
                 * lengths.
                 *
                 * Waiting Cloudlets contribute their full lengths.
                 */
                final double currentWorkload =
                        getVmWorkload(vm);

                /*
                 * VM processing capacity.
                 */
                final double effectiveMips =
                        Math.max(
                                1.0,
                                vm.getTotalMipsCapacity()
                        );

                /*
                 * ------------------------------------------------
                 * PROJECTED COMPLETION TIME
                 * ------------------------------------------------
                 */
                final double projectedTime =
                        (
                                currentWorkload
                                        + incomingLength
                        ) / effectiveMips;

                /*
                 * ------------------------------------------------
                 * PRIMARY CRITERION:
                 * Minimum projected completion time.
                 * ------------------------------------------------
                 */
                if (bestVm == null
                        || projectedTime
                                < bestProjectedTime) {

                    bestVm = vm;

                    bestProjectedTime =
                            projectedTime;

                    bestMips =
                            effectiveMips;

                    continue;
                }

                /*
                 * ------------------------------------------------
                 * SECONDARY CRITERION:
                 *
                 * When projected completion times are essentially
                 * equal, prefer the VM with greater processing
                 * capacity.
                 * ------------------------------------------------
                 */
                if (Math.abs(
                        projectedTime
                                - bestProjectedTime
                ) <= tolerance) {

                    if (effectiveMips > bestMips) {

                        bestVm = vm;

                        bestProjectedTime =
                                projectedTime;

                        bestMips =
                                effectiveMips;
                    }
                }
            }

            /*
             * ----------------------------------------------------
             * Safety fallback.
             * ----------------------------------------------------
             */
            if (bestVm == null) {
                return vms.get(0);
            }

            return bestVm;
        }
    }
}