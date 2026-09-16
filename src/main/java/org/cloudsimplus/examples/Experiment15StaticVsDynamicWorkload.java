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
public class Experiment15StaticVsDynamicWorkload {

    private static final int HOST_PES = 8;
    private static final int VM_PES = 2;
    private static final int CLOUDLET_PES = 2;

    private static final int TOTAL_CLOUDLETS = 50;
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
                "EXPERIMENT 15: STATIC VS DYNAMIC WORKLOAD"
        );

        System.out.println(
                "========================================"
        );

        System.out.println(
                "\nRUN A: STATIC WORKLOAD"
        );

        double staticMakespan =
                runSimulation(false);

        System.out.println(
                "\n========================================"
        );

        System.out.println(
                "RUN B: DYNAMIC WORKLOAD"
        );

        System.out.println(
                "========================================"
        );

        double dynamicMakespan =
                runSimulation(true);

        double difference =
                dynamicMakespan - staticMakespan;

        double percentageDifference =
                (difference / staticMakespan) * 100;

        System.out.println(
                "\n========================================"
        );

        System.out.println(
                "FINAL COMPARISON"
        );

        System.out.println(
                "========================================"
        );

        System.out.println(
                "Static Makespan  : "
                        + staticMakespan
                        + " seconds"
        );

        System.out.println(
                "Dynamic Makespan : "
                        + dynamicMakespan
                        + " seconds"
        );

        System.out.println(
                "Difference       : "
                        + difference
                        + " seconds"
        );

        System.out.println(
                "Percentage Difference: "
                        + percentageDifference
                        + "%"
        );
    }

    private static double runSimulation(
            boolean dynamicWorkload) {

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

        List<Cloudlet> cloudletList;

        if (dynamicWorkload) {

            cloudletList =
                    new ArrayList<>();

            cloudletList.addAll(
                    createCloudlets(
                            0,
                            INITIAL_CLOUDLETS,
                            0
                    )
            );

            cloudletList.addAll(
                    createCloudlets(
                            INITIAL_CLOUDLETS,
                            SECOND_BATCH_CLOUDLETS,
                            SECOND_BATCH_DELAY
                    )
            );

        } else {

            cloudletList =
                    createCloudlets(
                            0,
                            TOTAL_CLOUDLETS,
                            0
                    );
        }

        broker.submitCloudletList(
                cloudletList
        );

        simulation.start();

        List<Cloudlet> finishedCloudlets =
                broker.getCloudletFinishedList();

        double makespan =
                finishedCloudlets.stream()
                        .mapToDouble(
                                Cloudlet::getFinishTime
                        )
                        .max()
                        .orElse(0);

        System.out.println(
                "Finished Cloudlets: "
                        + finishedCloudlets.size()
        );

        System.out.println(
                "Makespan: "
                        + makespan
                        + " seconds"
        );

        return makespan;
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

            cloudletList.add(
                    cloudlet
            );
        }

        return cloudletList;
    }
}
