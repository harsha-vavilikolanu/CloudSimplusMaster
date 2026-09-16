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
import org.cloudsimplus.utilizationmodels.UtilizationModelDynamic;
import org.cloudsimplus.vms.Vm;
import org.cloudsimplus.vms.VmSimple;

import java.util.ArrayList;
import java.util.List;

public class Experiment2CloudletAnalysis {

    private static final int HOSTS = 1;
    private static final int HOST_PES = 8;
    private static final int HOST_MIPS = 1000;
    private static final int HOST_RAM = 2048;
    private static final long HOST_BW = 10_000;
    private static final long HOST_STORAGE = 1_000_000;

    private static final int VMS = 1;
    private static final int VM_PES = 4;

    private static final int CLOUDLETS = 1;
    private static final int CLOUDLET_PES = 2;
    private static final int CLOUDLET_LENGTH = 10_000;

    private final CloudSimPlus simulation;
    private final DatacenterBroker broker0;
    private List<Vm> vmList;
    private List<Cloudlet> cloudletList;
    private Datacenter datacenter0;

    static void main() {
        new Experiment2CloudletAnalysis();
    }

    private Experiment2CloudletAnalysis() {

        simulation = new CloudSimPlus();

        datacenter0 = createDatacenter();

        broker0 = new DatacenterBrokerSimple(simulation);

        vmList = createVms();
        cloudletList = createCloudlets();

        broker0.submitVmList(vmList);
        broker0.submitCloudletList(cloudletList);

        simulation.start();

        final var cloudletFinishedList =
                broker0.getCloudletFinishedList();

        new CloudletsTableBuilder(cloudletFinishedList).build();

        System.out.println("\nCLOUDLET EXECUTION TIME ANALYSIS");

        for (Cloudlet cloudlet : cloudletFinishedList) {

            double startTime = cloudlet.getStartTime();
            double finishTime = cloudlet.getFinishTime();
            double executionTime = finishTime - startTime;

            double effectiveProcessingRate =
                    cloudlet.getLength() / executionTime;

            System.out.println(
                    "Cloudlet ID: " + cloudlet.getId());

            System.out.println(
                    "Cloudlet Length: "
                            + cloudlet.getLength() + " MI");

            System.out.println(
                    "VM ID: " + cloudlet.getVm().getId());

            System.out.println(
                    "VM MIPS: " + cloudlet.getVm().getMips());

            System.out.println(
                    "Start Time: " + startTime + " seconds");

            System.out.println(
                    "Finish Time: " + finishTime + " seconds");

            System.out.println(
                    "Execution Time: "
                            + executionTime + " seconds");

            System.out.println(
                    "Effective Processing Rate: "
                            + effectiveProcessingRate
                            + " MI/second");
        }
    }

    private Datacenter createDatacenter() {

        final var hostList =
                new ArrayList<Host>(HOSTS);

        for (int i = 0; i < HOSTS; i++) {

            final var host = createHost();

            hostList.add(host);
        }

        return new DatacenterSimple(
                simulation, hostList);
    }

    private Host createHost() {

        final var peList =
                new ArrayList<Pe>(HOST_PES);

        for (int i = 0; i < HOST_PES; i++) {

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
                new ArrayList<Vm>(VMS);

        for (int i = 0; i < VMS; i++) {

            final var vm =
                    new VmSimple(HOST_MIPS, VM_PES);

            vm.setRam(512)
                    .setBw(1000)
                    .setSize(10_000);

            vmList.add(vm);
        }

        return vmList;
    }

    private List<Cloudlet> createCloudlets() {

        final var cloudletList =
                new ArrayList<Cloudlet>(CLOUDLETS);

        final var utilizationModel =
                new UtilizationModelDynamic(0.5);

        for (int i = 0; i < CLOUDLETS; i++) {

            final var cloudlet =
                    new CloudletSimple(
                            CLOUDLET_LENGTH,
                            CLOUDLET_PES,
                            utilizationModel);

            cloudlet.setSizes(1024);

            cloudletList.add(cloudlet);
        }

        return cloudletList;
    }
}