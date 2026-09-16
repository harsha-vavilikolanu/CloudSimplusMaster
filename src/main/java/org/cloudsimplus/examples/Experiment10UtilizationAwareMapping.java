package org.cloudsimplus.examples;

import org.cloudsimplus.brokers.DatacenterBroker;
import org.cloudsimplus.brokers.DatacenterBrokerSimple;
import org.cloudsimplus.builders.tables.CloudletsTableBuilder;
import org.cloudsimplus.cloudlets.Cloudlet;
import org.cloudsimplus.cloudlets.CloudletSimple;
import org.cloudsimplus.core.CloudSimPlus;
import org.cloudsimplus.datacenters.Datacenter;
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

public class Experiment10UtilizationAwareMapping {

    private static final int HOST_PES = 8;
    private static final int VM_PES = 2;
    private static final int CLOUDLET_PES = 2;

    private final CloudSimPlus simulation;
    private final DatacenterBroker broker;

    private List<Vm> vmList;
    private List<Cloudlet> cloudletList;

    public static void main(String[] args) {
        new Experiment10UtilizationAwareMapping();
    }

    private Experiment10UtilizationAwareMapping() {

        simulation = new CloudSimPlus();

        createDatacenter();

        broker = new DatacenterBrokerSimple(simulation);

        vmList = createVms();
        cloudletList = createCloudlets();

        broker.submitVmList(vmList);

        performUtilizationAwareMapping();

        broker.submitCloudletList(cloudletList);

        simulation.start();

        final var finishedCloudlets =
                broker.getCloudletFinishedList();

        new CloudletsTableBuilder(finishedCloudlets).build();

        System.out.println(
                "\nUTILIZATION-AWARE TASK TO VM MAPPING"
        );

        for (Cloudlet cloudlet : finishedCloudlets) {

            System.out.println(
                    "Cloudlet " + cloudlet.getId()
                            + " | Length: " + cloudlet.getLength() + " MI"
                            + " | CPU Utilization: "
                            + cloudlet.getUtilizationModelCpu()
                            .getUtilization(0)
                            + " | VM: " + cloudlet.getVm().getId()
                            + " | VM MIPS: "
                            + cloudlet.getVm().getMips()
                            + " | Start Time: "
                            + cloudlet.getStartTime()
                            + " | Finish Time: "
                            + cloudlet.getFinishTime()
            );
        }

        double makespan =
                finishedCloudlets.stream()
                        .mapToDouble(Cloudlet::getFinishTime)
                        .max()
                        .orElse(0);

        System.out.println(
                "\nMAKESPAN: " + makespan + " seconds"
        );
    }

    private Datacenter createDatacenter() {

        List<Host> hostList = new ArrayList<>();

        List<Pe> peList = new ArrayList<>();

        for (int i = 0; i < HOST_PES; i++) {
            peList.add(new PeSimple(2000));
        }

        Host host =
                new HostSimple(
                        8192,
                        10000,
                        1_000_000,
                        peList
                );

        hostList.add(host);

        return new DatacenterSimple(
                simulation,
                hostList
        );
    }

    private List<Vm> createVms() {

        List<Vm> list = new ArrayList<>();

        Vm vm0 =
                new VmSimple(0, 1000, VM_PES);

        vm0.setRam(512)
                .setBw(1000)
                .setSize(10000);

        vm0.setCloudletScheduler(
                new CloudletSchedulerSpaceShared()
        );

        Vm vm1 =
                new VmSimple(1, 2000, VM_PES);

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

    private List<Cloudlet> createCloudlets() {

        List<Cloudlet> list = new ArrayList<>();

        long[] lengths = {
                10_000,
                20_000,
                30_000,
                40_000
        };

        double[] cpuUtilizations = {
                0.2,
                0.5,
                0.8,
                1.0
        };

        for (int i = 0; i < lengths.length; i++) {

            UtilizationModelDynamic utilizationModel =
                    new UtilizationModelDynamic(
                            cpuUtilizations[i]
                    );

            Cloudlet cloudlet =
                    new CloudletSimple(
                            i,
                            lengths[i],
                            CLOUDLET_PES
                    );

            cloudlet.setFileSize(1024)
                    .setOutputSize(1024)
                    .setUtilizationModelCpu(utilizationModel);

            list.add(cloudlet);
        }

        return list;
    }

    private void performUtilizationAwareMapping() {

        double[] vmCompletionTime =
                new double[vmList.size()];

        List<Cloudlet> sortedCloudlets =
                new ArrayList<>(cloudletList);

        sortedCloudlets.sort(
                Comparator.comparingLong(
                        Cloudlet::getLength
                ).reversed()
        );

        System.out.println(
                "\nUTILIZATION-AWARE MAPPING DECISIONS"
        );

        for (Cloudlet cloudlet : sortedCloudlets) {

            double utilization =
                    cloudlet.getUtilizationModelCpu()
                            .getUtilization(0);

            Vm selectedVm = null;

            double minimumCompletionTime =
                    Double.MAX_VALUE;

            for (int i = 0;
                 i < vmList.size();
                 i++) {

                Vm vm = vmList.get(i);

                double executionTime =
                        cloudlet.getLength()
                                /
                                (vm.getMips() * utilization);

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
                    vmList.indexOf(selectedVm);

            double executionTime =
                    cloudlet.getLength()
                            /
                            (selectedVm.getMips()
                                    * utilization);

            vmCompletionTime[vmIndex] +=
                    executionTime;

            cloudlet.setVm(selectedVm);

            System.out.println(
                    "Cloudlet " + cloudlet.getId()
                            + " | Length: "
                            + cloudlet.getLength() + " MI"
                            + " | CPU Utilization: "
                            + utilization
                            + " -> VM "
                            + selectedVm.getId()
                            + " | VM MIPS: "
                            + selectedVm.getMips()
                            + " | Estimated Completion Time: "
                            + vmCompletionTime[vmIndex]
            );
        }

        System.out.println(
                "\nFINAL ESTIMATED VM COMPLETION TIMES"
        );

        for (int i = 0;
             i < vmCompletionTime.length;
             i++) {

            System.out.println(
                    "VM " + vmList.get(i).getId()
                            + " = "
                            + vmCompletionTime[i]
                            + " time units"
            );
        }
    }
}