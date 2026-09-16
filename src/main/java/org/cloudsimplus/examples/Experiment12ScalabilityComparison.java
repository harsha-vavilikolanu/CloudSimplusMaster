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

public class Experiment12ScalabilityComparison {

    private static final int HOST_PES = 8;
    private static final int VM_PES = 2;
    private static final int CLOUDLET_PES = 2;

    private static final int[] WORKLOAD_SIZES = {
            10,
            25,
            50,
            100,
            200
    };

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
                "EXPERIMENT 12: SCALABILITY COMPARISON"
        );

        System.out.println(
                "========================================"
        );

        System.out.printf(
                "%-15s %-25s %-30s %-20s%n",
                "Cloudlets",
                "MIPS-Aware Makespan",
                "Utilization-Aware Makespan",
                "Improvement (%)"
        );

        System.out.println(
                "--------------------------------------------------------------------------"
        );

        for (int numberOfCloudlets :
                WORKLOAD_SIZES) {

            double mipsAwareMakespan =
                    runSimulation(
                            false,
                            numberOfCloudlets
                    );

            double utilizationAwareMakespan =
                    runSimulation(
                            true,
                            numberOfCloudlets
                    );

            double improvement =
                    (
                            mipsAwareMakespan
                                    - utilizationAwareMakespan
                    )
                            /
                            mipsAwareMakespan
                            * 100;

            System.out.printf(
                    "%-15d %-25.4f %-30.4f %-20.4f%n",
                    numberOfCloudlets,
                    mipsAwareMakespan,
                    utilizationAwareMakespan,
                    improvement
            );
        }
    }

    private static double runSimulation(
            boolean utilizationAware,
            int numberOfCloudlets) {

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
                createCloudlets(
                        numberOfCloudlets
                );

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

        return finishedCloudlets.stream()
                .mapToDouble(
                        Cloudlet::getFinishTime
                )
                .max()
                .orElse(0);
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
    createCloudlets(
            int numberOfCloudlets) {

        List<Cloudlet> list =
                new ArrayList<>();

        for (int i = 0;
             i < numberOfCloudlets;
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
}