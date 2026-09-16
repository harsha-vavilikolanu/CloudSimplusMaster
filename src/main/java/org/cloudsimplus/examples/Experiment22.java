package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletExecution;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
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
import java.util.List;

public class Experiment22 {

    private static final int HOST_PES = 8;
    private static final int VM_PES = 2;
    private static final int CLOUDLET_PES = 2;

    private static final int INITIAL_CLOUDLETS = 20;
    private static final int SECOND_BATCH_CLOUDLETS = 30;

    private static final double SECOND_BATCH_DELAY = 100;

    private static final long[] CLOUDLET_LENGTH_PATTERN = {
            10_000,
            20_000,
            30_000,
            40_000
    };

    private static final double[] CPU_UTILIZATION_PATTERN = {
            0.5,
            0.2,
            0.8,
            1.0
    };

    public static void main(String[] args) {

        System.out.println(
                "\n========================================"
        );

        System.out.println(
                "EXPERIMENT 22 output"
        );

        System.out.println(
                "========================================"
        );

        runSimulation();
    }

    private static void runSimulation() {

        CloudSimPlus simulation =
                new CloudSimPlus();

        createDatacenter(simulation);

        DatacenterBroker broker =
                new RCWBroker22(
                        simulation
                );

        List<Vm> vmList =
                createVms();

        broker.submitVmList(vmList);

        List<Cloudlet> initialCloudlets =
                createCloudlets(
                        0,
                        INITIAL_CLOUDLETS,
                        0
                );

        List<Cloudlet> secondBatchCloudlets =
                createCloudlets(
                        INITIAL_CLOUDLETS,
                        SECOND_BATCH_CLOUDLETS,
                        SECOND_BATCH_DELAY
                );

        /*
         * Submit both batches.
         *
         * The second batch already has a submission delay
         * of 100 seconds, so CloudSim will submit it at
         * the specified simulation time.
         */
        broker.submitCloudletList(
                initialCloudlets
        );

        broker.submitCloudletList(
                secondBatchCloudlets
        );

        simulation.start();

        /*
         * Observe the runtime VM state after simulation.
         */
        observeVmState(
                simulation.clock(),
                vmList
        );
    }

    private static void createDatacenter(
            CloudSimPlus simulation) {

        List<Pe> peList =
                new ArrayList<>();

        for (int i = 0;
             i < HOST_PES;
             i++) {

            peList.add(
                    new PeSimple(2000)
            );
        }

        Host host =
                new HostSimple(
                        8192,
                        10000,
                        1_000_000,
                        peList
                );

        List<Host> hostList =
                new ArrayList<>();

        hostList.add(host);

        new DatacenterSimple(
                simulation,
                hostList
        );
    }

    private static List<Vm> createVms() {

        List<Vm> vmList =
                new ArrayList<>();

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

        vmList.add(vm0);
        vmList.add(vm1);

        return vmList;
    }

    private static List<Cloudlet> createCloudlets(
            int startId,
            int numberOfCloudlets,
            double submissionDelay) {

        List<Cloudlet> cloudletList =
                new ArrayList<>();

        for (int i = 0;
             i < numberOfCloudlets;
             i++) {

            int cloudletId =
                    startId + i;

            long cloudletLength =
                    CLOUDLET_LENGTH_PATTERN[
                            cloudletId %
                                    CLOUDLET_LENGTH_PATTERN.length
                            ];

            double cpuUtilization =
                    CPU_UTILIZATION_PATTERN[
                            cloudletId %
                                    CPU_UTILIZATION_PATTERN.length
                            ];

            UtilizationModelDynamic utilizationModel =
                    new UtilizationModelDynamic(
                            cpuUtilization
                    );

            Cloudlet cloudlet =
                    new CloudletSimple(
                            cloudletId,
                            cloudletLength,
                            CLOUDLET_PES
                    );

            cloudlet
                    .setFileSize(1024)
                    .setOutputSize(1024)
                    .setUtilizationModelCpu(
                            utilizationModel
                    )
                    .setSubmissionDelay(
                            submissionDelay
                    );

            cloudletList.add(cloudlet);
        }

        return cloudletList;
    }

    private static void observeVmState(
            double time,
            List<Vm> vmList) {

        System.out.println(
                "\n========================================"
        );

        System.out.println(
                "RUNTIME VM STATE AT TIME: "
                        + time
        );

        System.out.println(
                "========================================"
        );

        for (Vm vm : vmList) {

            int executingCloudlets =
                    vm.getCloudletScheduler()
                            .getCloudletExecList()
                            .size();

            int waitingCloudlets =
                    vm.getCloudletScheduler()
                            .getCloudletWaitingList()
                            .size();

            double cpuUtilization =
                    vm.getCpuPercentUtilization()
                            * 100;

            long remainingWorkload = 0;

            /*
             * Remaining workload of executing Cloudlets.
             */
            for (CloudletExecution ce :
                    vm.getCloudletScheduler()
                            .getCloudletExecList()) {

                remainingWorkload +=
                        ce.getRemainingCloudletLength();
            }

            /*
             * Waiting Cloudlets are also remaining workload.
             */
            for (CloudletExecution ce :
                    vm.getCloudletScheduler()
                            .getCloudletWaitingList()) {

                remainingWorkload +=
                        ce.getCloudlet()
                                .getLength();
            }

            System.out.println(
                    "\nVM " + vm.getId()
            );

            System.out.println(
                    "Executing Cloudlets   : "
                            + executingCloudlets
            );

            System.out.println(
                    "Waiting Cloudlets     : "
                            + waitingCloudlets
            );

            System.out.println(
                    String.format(
                            "CPU Utilization        : %.2f%%",
                            cpuUtilization
                    )
            );

            System.out.println(
                    "Remaining Workload      : "
                            + remainingWorkload
                            + " MI"
            );

            System.out.println(
                    "----------------------------------------"
            );
        }
    }
}