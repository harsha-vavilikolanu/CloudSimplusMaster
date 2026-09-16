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
import java.util.Comparator;
import java.util.List;

public class Experiment13WorkloadDistributionAnalysis {

    private static final int HOST_PES = 8;
    private static final int VM_PES = 2;
    private static final int CLOUDLET_PES = 2;

    private static final int NUMBER_OF_CLOUDLETS = 25;

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
                "EXPERIMENT 13: WORKLOAD DISTRIBUTION ANALYSIS"
        );

        System.out.println(
                "========================================"
        );

        System.out.println(
                "\nRUN A: MIPS-AWARE MAPPING"
        );

        runSimulation(false);

        System.out.println(
                "\n========================================"
        );

        System.out.println(
                "RUN B: UTILIZATION-AWARE MAPPING"
        );

        System.out.println(
                "========================================"
        );

        runSimulation(true);
    }

    private static void runSimulation(
            boolean utilizationAware) {

        CloudSimPlus simulation =
                new CloudSimPlus();

        createDatacenter(simulation);

        DatacenterBroker broker =
                new DatacenterBrokerSimple(
                        simulation
                );

        List<Vm> vmList =
                createVms();

        List<Cloudlet> cloudletList =
                createCloudlets();

        broker.submitVmList(vmList);

        if (utilizationAware) {

            performUtilizationAwareMapping(
                    vmList,
                    cloudletList
            );

        } else {

            performMipsAwareMapping(
                    vmList,
                    cloudletList
            );
        }

        broker.submitCloudletList(
                cloudletList
        );

        simulation.start();

        List<Cloudlet> finishedCloudlets =
                broker.getCloudletFinishedList();

        analyzeWorkloadDistribution(
                vmList,
                finishedCloudlets
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

        List<Vm> list =
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

        list.add(vm0);
        list.add(vm1);

        return list;
    }

    private static List<Cloudlet>
    createCloudlets() {

        List<Cloudlet> list =
                new ArrayList<>();

        for (int i = 0;
             i < NUMBER_OF_CLOUDLETS;
             i++) {

            long cloudletLength =
                    CLOUDLET_LENGTH_PATTERN[
                            i %
                                    CLOUDLET_LENGTH_PATTERN.length
                            ];

            double cpuUtilization =
                    CPU_UTILIZATION_PATTERN[
                            i %
                                    CPU_UTILIZATION_PATTERN.length
                            ];

            UtilizationModelDynamic
                    utilizationModel =
                    new UtilizationModelDynamic(
                            cpuUtilization
                    );

            Cloudlet cloudlet =
                    new CloudletSimple(
                            i,
                            cloudletLength,
                            CLOUDLET_PES
                    );

            cloudlet
                    .setFileSize(1024)
                    .setOutputSize(1024)
                    .setUtilizationModelCpu(
                            utilizationModel
                    );

            list.add(cloudlet);
        }

        return list;
    }

    private static void
    performMipsAwareMapping(
            List<Vm> vmList,
            List<Cloudlet> cloudletList) {

        double[] vmCompletionTime =
                new double[vmList.size()];

        List<Cloudlet> sortedCloudlets =
                new ArrayList<>(
                        cloudletList
                );

        sortedCloudlets.sort(
                Comparator
                        .comparingLong(
                                Cloudlet::getLength
                        )
                        .reversed()
        );

        for (Cloudlet cloudlet :
                sortedCloudlets) {

            Vm selectedVm = null;

            double minimumCompletionTime =
                    Double.MAX_VALUE;

            for (int i = 0;
                 i < vmList.size();
                 i++) {

                Vm vm =
                        vmList.get(i);

                double executionTime =
                        cloudlet.getLength()
                                /
                                vm.getMips();

                double estimatedCompletionTime =
                        vmCompletionTime[i]
                                + executionTime;

                if (estimatedCompletionTime
                        < minimumCompletionTime) {

                    minimumCompletionTime =
                            estimatedCompletionTime;

                    selectedVm = vm;
                }
            }

            int vmIndex =
                    vmList.indexOf(
                            selectedVm
                    );

            vmCompletionTime[vmIndex] +=
                    cloudlet.getLength()
                            /
                            selectedVm.getMips();

            cloudlet.setVm(
                    selectedVm
            );
        }
    }

    private static void
    performUtilizationAwareMapping(
            List<Vm> vmList,
            List<Cloudlet> cloudletList) {

        double[] vmCompletionTime =
                new double[vmList.size()];

        List<Cloudlet> sortedCloudlets =
                new ArrayList<>(
                        cloudletList
                );

        sortedCloudlets.sort(
                Comparator
                        .comparingLong(
                                Cloudlet::getLength
                        )
                        .reversed()
        );

        for (Cloudlet cloudlet :
                sortedCloudlets) {

            double utilization =
                    cloudlet
                            .getUtilizationModelCpu()
                            .getUtilization(0);

            Vm selectedVm = null;

            double minimumCompletionTime =
                    Double.MAX_VALUE;

            for (int i = 0;
                 i < vmList.size();
                 i++) {

                Vm vm =
                        vmList.get(i);

                double executionTime =
                        cloudlet.getLength()
                                /
                                (
                                        vm.getMips()
                                                * utilization
                                );

                double estimatedCompletionTime =
                        vmCompletionTime[i]
                                + executionTime;

                if (estimatedCompletionTime
                        < minimumCompletionTime) {

                    minimumCompletionTime =
                            estimatedCompletionTime;

                    selectedVm = vm;
                }
            }

            int vmIndex =
                    vmList.indexOf(
                            selectedVm
                    );

            double executionTime =
                    cloudlet.getLength()
                            /
                            (
                                    selectedVm.getMips()
                                            * utilization
                            );

            vmCompletionTime[vmIndex] +=
                    executionTime;

            cloudlet.setVm(
                    selectedVm
            );
        }
    }

    private static void
    analyzeWorkloadDistribution(
            List<Vm> vmList,
            List<Cloudlet> finishedCloudlets) {

        System.out.println(
                "\nVM WORKLOAD DISTRIBUTION"
        );

        System.out.printf(
                "%-10s %-12s %-18s %-20s%n",
                "VM",
                "MIPS",
                "Cloudlet Count",
                "Total Assigned MI"
        );

        System.out.println(
                "------------------------------------------------------------"
        );

        for (Vm vm : vmList) {

            long cloudletCount =
                    finishedCloudlets.stream()
                            .filter(
                                    cloudlet ->
                                            cloudlet
                                                    .getVm()
                                                    .getId()
                                                    ==
                                                    vm.getId()
                            )
                            .count();

            long totalAssignedMi =
                    finishedCloudlets.stream()
                            .filter(
                                    cloudlet ->
                                            cloudlet
                                                    .getVm()
                                                    .getId()
                                                    ==
                                                    vm.getId()
                            )
                            .mapToLong(
                                    Cloudlet::getLength
                            )
                            .sum();

            System.out.printf(
                    "%-10d %-12.0f %-18d %-20d%n",
                    vm.getId(),
                    vm.getMips(),
                    cloudletCount,
                    totalAssignedMi
            );
        }

        double makespan =
                finishedCloudlets.stream()
                        .mapToDouble(
                                Cloudlet::getFinishTime
                        )
                        .max()
                        .orElse(0);

        System.out.println(
                "\nMakespan: "
                        + makespan
                        + " seconds"
        );
    }
}