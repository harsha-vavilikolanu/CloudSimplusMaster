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

public class Experiment9HeterogeneousVmMapping {

    private static final int HOSTS = 1;
    private static final int HOST_PES = 8;
    private static final int HOST_MIPS = 2000;
    private static final int HOST_RAM = 4096;
    private static final long HOST_BW = 10_000;
    private static final long HOST_STORAGE = 1_000_000;

    private static final int VM_PES = 2;
    private static final int CLOUDLET_PES = 2;

    private final CloudSimPlus simulation;
    private final DatacenterBroker broker0;

    private List<Vm> vmList;
    private List<Cloudlet> cloudletList;

    static void main() {
        new Experiment9HeterogeneousVmMapping();
    }

    private Experiment9HeterogeneousVmMapping() {

        simulation = new CloudSimPlus();

        createDatacenter();

        broker0 = new DatacenterBrokerSimple(simulation);

        vmList = createVms();
        cloudletList = createCloudlets();

        broker0.submitVmList(vmList);
        broker0.submitCloudletList(cloudletList);

        performCompletionTimeAwareMapping();

        simulation.start();

        final var cloudletFinishedList =
                broker0.getCloudletFinishedList();

        new CloudletsTableBuilder(
                cloudletFinishedList).build();

        System.out.println(
                "\nHETEROGENEOUS VM TASK MAPPING");

        for (Cloudlet cloudlet : cloudletFinishedList) {

            System.out.println(
                    "Cloudlet " + cloudlet.getId()
                            + " | Length: "
                            + cloudlet.getLength() + " MI"
                            + " | VM: "
                            + cloudlet.getVm().getId()
                            + " | VM MIPS: "
                            + cloudlet.getVm().getMips()
                            + " | Start Time: "
                            + cloudlet.getStartTime()
                            + " | Finish Time: "
                            + cloudlet.getFinishTime()
            );
        }

        final double makespan = cloudletFinishedList
                .stream()
                .mapToDouble(Cloudlet::getFinishTime)
                .max()
                .orElse(0);

        System.out.println(
                "\nMAKESPAN: "
                        + makespan
                        + " seconds");
    }

    private void performCompletionTimeAwareMapping() {

        double[] vmEstimatedTime =
                new double[vmList.size()];

        List<Cloudlet> sortedCloudlets =
                new ArrayList<>(cloudletList);

        sortedCloudlets.sort(
                Comparator.comparingLong(
                        Cloudlet::getLength).reversed());

        System.out.println(
                "\nHETEROGENEOUS VM MAPPING DECISIONS");

        for (Cloudlet cloudlet : sortedCloudlets) {

            int selectedVm = 0;

            double minimumCompletionTime =
                    vmEstimatedTime[0]
                            + cloudlet.getLength()
                            / vmList.get(0).getMips();

            for (int i = 1;
                 i < vmList.size();
                 i++) {

                double estimatedCompletionTime =
                        vmEstimatedTime[i]
                                + cloudlet.getLength()
                                / vmList.get(i).getMips();

                if (estimatedCompletionTime
                        < minimumCompletionTime) {

                    selectedVm = i;

                    minimumCompletionTime =
                            estimatedCompletionTime;
                }
            }

            broker0.bindCloudletToVm(
                    cloudlet,
                    vmList.get(selectedVm));

            vmEstimatedTime[selectedVm] =
                    minimumCompletionTime;

            System.out.println(
                    "Cloudlet " + cloudlet.getId()
                            + " | Length: "
                            + cloudlet.getLength() + " MI"
                            + " -> VM " + selectedVm
                            + " | VM MIPS: "
                            + vmList.get(selectedVm).getMips()
                            + " | Estimated Completion Time: "
                            + vmEstimatedTime[selectedVm]
            );
        }

        System.out.println(
                "\nFINAL ESTIMATED VM COMPLETION TIMES");

        for (int i = 0;
             i < vmList.size();
             i++) {

            System.out.println(
                    "VM " + i
                            + " = "
                            + vmEstimatedTime[i]
                            + " time units"
            );
        }
    }

    private Datacenter createDatacenter() {

        final var hostList =
                new ArrayList<Host>(HOSTS);

        for (int i = 0; i < HOSTS; i++) {
            hostList.add(createHost());
        }

        return new DatacenterSimple(
                simulation,
                hostList);
    }

    private Host createHost() {

        final var peList =
                new ArrayList<Pe>(HOST_PES);

        for (int i = 0;
             i < HOST_PES;
             i++) {

            peList.add(
                    new PeSimple(HOST_MIPS));
        }

        return new HostSimple(
                HOST_RAM,
                HOST_BW,
                HOST_STORAGE,
                peList);
    }

    private List<Vm> createVms() {

        final var vmList =
                new ArrayList<Vm>();

        Vm vm0 =
                new VmSimple(
                        1000,
                        VM_PES);

        vm0.setRam(512)
                .setBw(1000)
                .setSize(10_000);

        vm0.setCloudletScheduler(
                new CloudletSchedulerSpaceShared());

        Vm vm1 =
                new VmSimple(
                        2000,
                        VM_PES);

        vm1.setRam(512)
                .setBw(1000)
                .setSize(10_000);

        vm1.setCloudletScheduler(
                new CloudletSchedulerSpaceShared());

        vmList.add(vm0);
        vmList.add(vm1);

        return vmList;
    }

    private List<Cloudlet> createCloudlets() {

        final var cloudletList =
                new ArrayList<Cloudlet>();

        final var utilizationModel =
                new UtilizationModelDynamic(0.5);

        long[] cloudletLengths = {
                10_000,
                20_000,
                30_000,
                40_000
        };

        for (long length : cloudletLengths) {

            final var cloudlet =
                    new CloudletSimple(
                            length,
                            CLOUDLET_PES,
                            utilizationModel);

            cloudlet.setSizes(1024);

            cloudletList.add(cloudlet);
        }

        return cloudletList;
    }
}