
package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimEntity;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.core.events.SimEvent;
import org.cloudsimplus.datacenters.DatacenterSimple;
import org.cloudsimplus.hosts.Host;
import org.cloudsimplus.hosts.HostSimple;
import org.cloudsimplus.resources.Pe;
import org.cloudsimplus.resources.PeSimple;
import org.cloudsimplus.schedulers.cloudlet.CloudletSchedulerSpaceShared;
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class ExperimentFinalRCW {

    /*
     * =============================================================
     * SIMULATION CONFIGURATION
     * =============================================================
     */

    private static final int HOST_PES = 8;

    private static final int VM_PES = 2;

    private static final int CLOUDLET_PES = 2;

    /*
     * Reduced workload:
     *
     * First batch  = 10
     * Second batch = 10
     * Total        = 20
     */
    private static final int INITIAL_CLOUDLETS = 50;

    private static final int SECOND_BATCH_CLOUDLETS = 50;

    /*
     * Second batch arrives exactly at t = 100.
     */
    private static final double SECOND_BATCH_TIME = 100.0;

    /*
     * Simulation event tag.
     */
    private static final int SECOND_BATCH_EVENT = 1001;


    /*
     * Cloudlet length pattern.
     */
    private static final long[] CLOUDLET_LENGTH_PATTERN = {
            10_000,
            20_000,
            30_000,
            40_000
    };


    /*
     * CPU utilization pattern.
     */
    private static final double[] CPU_UTILIZATION_PATTERN = {
            0.5,
            0.2,
            0.8,
            1.0
    };


    /*
     * =============================================================
     * INSTANCE VARIABLES
     * =============================================================
     */

    private CloudSimPlus simulation;

    private DatacenterBroker broker;

    private List<Vm> vmList;

    /*
     * Second batch is created but held until t = 100.
     */
    private List<Cloudlet> secondBatchCloudlets;

    private boolean secondBatchSubmitted = false;

    /*
     * Runtime event entity.
     */
    private RuntimeEventEntity runtimeEventEntity;


    /*
     * =============================================================
     * MAIN
     * =============================================================
     */

    public static void main(String[] args) {

        System.out.println(
                "==== EXPERIMENT FINAL: TRUE RUNTIME RCW ===="
        );

        new ExperimentFinalRCW();
    }


    /*
     * =============================================================
     * CONSTRUCTOR
     * =============================================================
     */

    public ExperimentFinalRCW() {

        /*
         * Create CloudSim Plus simulation.
         */
        simulation = new CloudSimPlus();


        /*
         * Create datacenter.
         */
        createDatacenter();


        /*
         * Create RCW broker.
         */
        broker = new RCWBroker22(simulation);


        /*
         * Create VMs.
         */
        createVms();

        broker.submitVmList(vmList);


        /*
         * =========================================================
         * FIRST BATCH
         * =========================================================
         *
         * Cloudlets 0-9 are submitted at the beginning.
         */
        List<Cloudlet> initialCloudlets =
                createCloudlets(
                        0,
                        INITIAL_CLOUDLETS
                );

        broker.submitCloudletList(
                initialCloudlets
        );


        /*
         * =========================================================
         * SECOND BATCH
         * =========================================================
         *
         * Cloudlets 10-19 are created now but NOT submitted.
         *
         * They are submitted only at t = 100.
         */
        secondBatchCloudlets =
                createCloudlets(
                        INITIAL_CLOUDLETS,
                        SECOND_BATCH_CLOUDLETS
                );


        /*
         * Create runtime event entity.
         */
        runtimeEventEntity =
                new RuntimeEventEntity(simulation);


        /*
         * Start simulation.
         */
        simulation.start();


        /*
         * Display final metrics.
         */
        evaluateMetrics();
    }


    /*
     * =============================================================
     * RUNTIME EVENT ENTITY
     * =============================================================
     *
     * This replaces the clock-tick listener.
     * =============================================================
     */

    private class RuntimeEventEntity
            extends CloudSimEntity {

        public RuntimeEventEntity(
                final CloudSimPlus simulation) {

            super(simulation);
        }


        /*
         * Schedule the event at exactly t = 100.
         */
        @Override
        protected void startInternal() {

            schedule(
                    SECOND_BATCH_TIME,
                    SECOND_BATCH_EVENT
            );
        }


        /*
         * Process the runtime event.
         */
        @Override
        public void processEvent(
                final SimEvent event) {

            if (event.getTag()
                    == SECOND_BATCH_EVENT) {

                submitSecondBatchAtRuntime();
            }
        }
    }


    /*
     * =============================================================
     * RUNTIME SECOND-BATCH SUBMISSION
     * =============================================================
     */

    private void submitSecondBatchAtRuntime() {

        /*
         * Prevent duplicate submission.
         */
        if (secondBatchSubmitted) {
            return;
        }

        secondBatchSubmitted = true;


        /*
         * Get exact simulation time.
         */
        final double currentTime =
                simulation.clock();


        System.out.println();

        System.out.println(
                "========================================"
        );

        System.out.printf(
                "RUNTIME VM STATE CAPTURED AT TIME: %.2f%n",
                currentTime
        );

        System.out.println(
                "========================================"
        );


        /*
         * Verify exact t = 100.
         */
        if (Math.abs(
                currentTime - SECOND_BATCH_TIME
        ) > 0.000001) {

            System.err.printf(
                    "ERROR: Expected runtime event at %.2f "
                    + "but actual time is %.2f%n",

                    SECOND_BATCH_TIME,
                    currentTime
            );

        } else {

            System.out.println(
                    "SUCCESS: Runtime event occurred exactly "
                    + "at t = 100.00 seconds."
            );
        }


        /*
         * =========================================================
         * CAPTURE ACTUAL VM STATE
         * =========================================================
         */

        for (Vm vm : vmList) {

            double actualRemainingWorkload =
                    calculateActualRemainingWorkload(vm);


            /*
             * Print total remaining workload.
             */
            System.out.printf(
                    "VM %d (Capacity: %.0f MIPS)"
                    + " -> Actual Remaining Workload: %.0f MI%n",

                    vm.getId(),
                    vm.getMips(),
                    actualRemainingWorkload
            );


            /*
             * =====================================================
             * EXECUTING CLOUDLETS
             * =====================================================
             */

            for (CloudletExecution execution :
                    vm.getCloudletScheduler()
                      .getCloudletExecList()) {

                /*
                 * getRemainingCloudletLength() returns long,
                 * therefore %d is used.
                 */
                System.out.printf(
                        "   EXECUTING Cloudlet %d"
                        + " -> Remaining: %d MI%n",

                        execution.getCloudlet().getId(),

                        execution.getRemainingCloudletLength()
                );
            }


            /*
             * =====================================================
             * WAITING CLOUDLETS
             * =====================================================
             */

            for (CloudletExecution execution :
                    vm.getCloudletScheduler()
                      .getCloudletWaitingList()) {

                System.out.printf(
                        "   WAITING Cloudlet %d"
                        + " -> Complete Length: %d MI%n",

                        execution.getCloudlet().getId(),

                        execution.getCloudlet().getLength()
                );
            }
        }


        System.out.println(
                "----------------------------------------"
        );


        /*
         * =========================================================
         * SUBMIT SECOND BATCH
         * =========================================================
         */

        System.out.printf(
                "Submitting second batch at simulation time %.2f%n",
                simulation.clock()
        );


        broker.submitCloudletList(
                secondBatchCloudlets
        );


        System.out.printf(
                "Second batch of %d Cloudlets submitted.%n",

                secondBatchCloudlets.size()
        );


        System.out.println(
                "========================================"
        );

        System.out.println();
    }


    /*
     * =============================================================
     * CALCULATE ACTUAL REMAINING WORKLOAD
     * =============================================================
     */

    private double calculateActualRemainingWorkload(
            Vm vm) {

        double workload = 0.0;


        /*
         * Executing Cloudlets:
         * count only their remaining MI.
         */
        for (CloudletExecution execution :
                vm.getCloudletScheduler()
                  .getCloudletExecList()) {

            workload +=
                    execution.getRemainingCloudletLength();
        }


        /*
         * Waiting Cloudlets:
         * count their complete MI.
         */
        for (CloudletExecution execution :
                vm.getCloudletScheduler()
                  .getCloudletWaitingList()) {

            workload +=
                    execution.getCloudlet().getLength();
        }


        return workload;
    }


    /*
     * =============================================================
     * CREATE DATACENTER
     * =============================================================
     */

    private void createDatacenter() {

        List<Pe> peList =
                new ArrayList<>();


        /*
         * Host has 8 PEs.
         * Each PE = 2000 MIPS.
         */
        for (int i = 0;
             i < HOST_PES;
             i++) {

            peList.add(
                    new PeSimple(2000)
            );
        }


        /*
         * Create host.
         */
        Host host =
                new HostSimple(
                        8192,
                        10000,
                        1_000_000,
                        peList
                );


        /*
         * Create datacenter.
         */
        new DatacenterSimple(
                simulation,
                Arrays.asList(host)
        );
    }


    /*
     * =============================================================
     * CREATE VMS
     * =============================================================
     */

    private void createVms() {

        vmList =
                new ArrayList<>();


        /*
         * =========================================================
         * VM 0
         * =========================================================
         */

        Vm vm0 =
                new VmSimple(
                        0,
                        1000,
                        VM_PES
                );


        vm0.setRam(512)
           .setBw(1000)
           .setSize(10000);


        vm0.setCloudletScheduler(
                new CloudletSchedulerSpaceShared()
        );


        vmList.add(vm0);


        /*
         * =========================================================
         * VM 1
         * =========================================================
         */

        Vm vm1 =
                new VmSimple(
                        1,
                        2000,
                        VM_PES
                );


        vm1.setRam(512)
           .setBw(1000)
           .setSize(10000);


        vm1.setCloudletScheduler(
                new CloudletSchedulerSpaceShared()
        );


        vmList.add(vm1);
    }


    /*
     * =============================================================
     * CREATE CLOUDLETS
     * =============================================================
     */

    private List<Cloudlet> createCloudlets(
            int startId,
            int count) {

        List<Cloudlet> list =
                new ArrayList<>();


        for (int i = 0;
             i < count;
             i++) {

            int id =
                    startId + i;


            /*
             * Select Cloudlet length.
             */
            long length =
                    CLOUDLET_LENGTH_PATTERN[
                            id %
                            CLOUDLET_LENGTH_PATTERN.length
                    ];


            /*
             * Select CPU utilization.
             */
            double utilization =
                    CPU_UTILIZATION_PATTERN[
                            id %
                            CPU_UTILIZATION_PATTERN.length
                    ];


            /*
             * Create Cloudlet.
             */
            Cloudlet cloudlet =
                    new CloudletSimple(
                            id,
                            length,
                            CLOUDLET_PES
                    );


            cloudlet.setFileSize(1024)
                    .setOutputSize(1024)
                    .setUtilizationModelCpu(
                            new UtilizationModelDynamic(
                                    utilization
                            )
                    );


            list.add(cloudlet);
        }


        return list;
    }


    /*
     * =============================================================
     * FINAL METRICS
     * =============================================================
     */

    private void evaluateMetrics() {

        List<Cloudlet> finishedCloudlets =
                broker.getCloudletFinishedList();


        double makespan = 0.0;

        double totalWaitingTime = 0.0;

        double totalExecutionTime = 0.0;


        /*
         * Calculate metrics.
         */
        for (Cloudlet cloudlet :
                finishedCloudlets) {

            /*
             * Makespan.
             */
            if (cloudlet.getFinishTime()
                    > makespan) {

                makespan =
                        cloudlet.getFinishTime();
            }


            /*
             * Waiting time.
             */
            totalWaitingTime +=
                    cloudlet.getStartWaitTime();


            /*
             * Execution time.
             */
            totalExecutionTime +=
                    cloudlet.getTotalExecutionTime();
        }


        int totalTasks =
                finishedCloudlets.size();


        System.out.println();

        System.out.println(
                "=== EXPERIMENT EVALUATION METRICS ==="
        );


        System.out.printf(
                "Total Cloudlets Processed    : %d%n",
                totalTasks
        );


        System.out.printf(
                "Makespan (Overall Completion): %.2f s%n",
                makespan
        );


        if (totalTasks > 0) {

            System.out.printf(
                    "Average Execution Time       : %.2f s%n",

                    totalExecutionTime
                    / totalTasks
            );


            System.out.printf(
                    "Average Waiting Time         : %.2f s%n",

                    totalWaitingTime
                    / totalTasks
            );
        }


        System.out.println(
                "====================================="
        );
    }
}
