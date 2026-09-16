package org.cloudsimplus.examples;

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
import java.util.Arrays;
import java.util.List;

public class Experiment21 {

    private static final int HOST_PES = 8;
    private static final int VM_PES = 2;
    private static final int CLOUDLET_PES = 2;

    private static final int INITIAL_CLOUDLETS = 20;
    private static final int SECOND_BATCH_CLOUDLETS = 30;
    private static final double SECOND_BATCH_DELAY = 100.0;

    private static final long[] CLOUDLET_LENGTH_PATTERN = { 10_000, 20_000, 30_000, 40_000 };
    private static final double[] CPU_UTILIZATION_PATTERN = { 0.5, 0.2, 0.8, 1.0 };

    private final CloudSimPlus simulation;
    private RCWBroker21 broker;
    private List<Vm> vmList;
    private List<Cloudlet> initialCloudlets;
    private List<Cloudlet> secondBatchCloudlets;

    public static void main(String[] args) {
        System.out.println("==== EXPERIMENT 21: TRUE RUNTIME RCW ====");
        new Experiment21();
    }

    public Experiment21() {
        simulation = new CloudSimPlus();

        createDatacenter();

        // 1. Initialize our custom broker
        broker = new RCWBroker21(simulation);

        // 2. Submit VMs
        createVms();
        broker.submitVmList(vmList);

        // 3. Submit first batch immediately
        initialCloudlets = createCloudlets(0, INITIAL_CLOUDLETS);
        broker.submitCloudletList(initialCloudlets);

        // 4. Schedule second batch for exactly t = 100.0s using the broker's native event logic
        secondBatchCloudlets = createCloudlets(INITIAL_CLOUDLETS, SECOND_BATCH_CLOUDLETS);
        broker.scheduleBatchSubmission(secondBatchCloudlets, SECOND_BATCH_DELAY);

        simulation.start();

        evaluateMetrics();
    }

    private void createDatacenter() {
        List<Pe> peList = new ArrayList<>();
        for (int i = 0; i < HOST_PES; i++) {
            peList.add(new PeSimple(2000));
        }

        Host host = new HostSimple(8192, 10000, 1_000_000, peList);
        new DatacenterSimple(simulation, Arrays.asList(host));
    }

    private void createVms() {
        vmList = new ArrayList<>();

        Vm vm0 = new VmSimple(0, 1000, VM_PES);
        vm0.setRam(512).setBw(1000).setSize(10000);
        vm0.setCloudletScheduler(new CloudletSchedulerSpaceShared());
        vmList.add(vm0);

        Vm vm1 = new VmSimple(1, 2000, VM_PES);
        vm1.setRam(512).setBw(1000).setSize(10000);
        vm1.setCloudletScheduler(new CloudletSchedulerSpaceShared());
        vmList.add(vm1);
    }

    private List<Cloudlet> createCloudlets(int startId, int count) {
        List<Cloudlet> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int id = startId + i;
            long length = CLOUDLET_LENGTH_PATTERN[id % CLOUDLET_LENGTH_PATTERN.length];
            double util = CPU_UTILIZATION_PATTERN[id % CPU_UTILIZATION_PATTERN.length];

            Cloudlet cloudlet = new CloudletSimple(id, length, CLOUDLET_PES);
            cloudlet.setFileSize(1024)
                    .setOutputSize(1024)
                    .setUtilizationModelCpu(new UtilizationModelDynamic(util));
            list.add(cloudlet);
        }
        return list;
    }

    private void evaluateMetrics() {
        List<Cloudlet> finishedCloudlets = broker.getCloudletFinishedList();
        
        double makespan = 0.0;
        double totalWaitingTime = 0.0;
        double totalExecutionTime = 0.0;

        for (Cloudlet c : finishedCloudlets) {
            if (c.getFinishTime() > makespan) {
                makespan = c.getFinishTime();
            }
            totalWaitingTime += c.getLifeTime();
            
            // Fail-proof way to calculate actual completion/execution time
            totalExecutionTime += (c.getFinishTime() - c.getStartTime()); 
        }

        int totalTasks = finishedCloudlets.size();
        
        System.out.println("\n=== EXPERIMENT EVALUATION METRICS ===");
        System.out.printf("Makespan (Overall Completion): %.2f s%n", makespan);
        System.out.printf("Average Execution Time       : %.2f s%n", totalExecutionTime / totalTasks);
        System.out.printf("Average Waiting Time         : %.2f s%n", totalWaitingTime / totalTasks);
        System.out.println("=====================================");
    }
}