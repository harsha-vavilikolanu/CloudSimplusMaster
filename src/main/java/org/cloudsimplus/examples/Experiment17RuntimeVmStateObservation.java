package org.cloudsimplus.examples;


import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.cloudlets.Cloudlet;
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
public class Experiment17RuntimeVmStateObservation {

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
                "EXPERIMENT 17: RUNTIME VM STATE OBSERVATION"
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
                new DatacenterBrokerSimple(
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

        broker.submitCloudletList(
                initialCloudlets
        );

        broker.submitCloudletList(
                secondBatchCloudlets
        );

        mapCloudlets(
                broker,
                initialCloudlets,
                vmList
        );

        mapCloudlets(
                broker,
                secondBatchCloudlets,
                vmList
        );

        simulation.addOnClockTickListener(
                event -> {

                    double time =
                            event.getTime();

                    if (time >= SECOND_BATCH_DELAY
                            && time < SECOND_BATCH_DELAY + 1) {

                        observeVmState(
                                time,
                                vmList
                        );
                    }
                }
        );

        simulation.start();
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

    private static void mapCloudlets(
            DatacenterBroker broker,
            List<Cloudlet> cloudletList,
            List<Vm> vmList) {

        for (int i = 0;
             i < cloudletList.size();
             i++) {

            Vm selectedVm =
                    vmList.get(
                            i % vmList.size()
                    );

            broker.bindCloudletToVm(
                    cloudletList.get(i),
                    selectedVm
            );
        }
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

            System.out.println(
                    "VM "
                            + vm.getId()
                            + " | MIPS: "
                            + vm.getMips()
                            + " | Executing Cloudlets: "
                            + executingCloudlets
                            + " | Waiting Cloudlets: "
                            + waitingCloudlets
            );
        }
    }
}
