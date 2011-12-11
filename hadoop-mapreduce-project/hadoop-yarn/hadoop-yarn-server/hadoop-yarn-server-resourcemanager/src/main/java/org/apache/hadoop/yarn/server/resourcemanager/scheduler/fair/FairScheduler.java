/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.LimitedPrivate;
import org.apache.hadoop.classification.InterfaceStability.Evolving;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.Lock;
import org.apache.hadoop.yarn.api.records.ApplicationAttemptId;
import org.apache.hadoop.yarn.api.records.Container;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.QueueInfo;
import org.apache.hadoop.yarn.api.records.QueueUserACLInfo;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.factory.providers.RecordFactoryProvider;
import org.apache.hadoop.yarn.server.resourcemanager.RMAuditLogger;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.RMAuditLogger.AuditConstants;
import org.apache.hadoop.yarn.server.resourcemanager.recovery.Store.RMState;
import org.apache.hadoop.yarn.server.resourcemanager.resource.Resources;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.RMAppAttemptState;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.attempt.event.RMAppAttemptRejectedEvent;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainer;
import org.apache.hadoop.yarn.server.resourcemanager.rmcontainer.RMContainerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.Allocation;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.QueueMetrics;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.ResourceScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerApp;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerAppReport;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerNode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerNodeReport;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.SchedulerUtils;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CSQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.CapacityScheduler;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.capacity.LeafQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.AppAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.AppRemovedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.ContainerExpiredSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeRemovedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeUpdateSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.SchedulerEvent;
import org.apache.hadoop.yarn.server.security.ContainerTokenSecretManager;

@LimitedPrivate("yarn")
@Evolving
public class FairScheduler implements ResourceScheduler {

  private boolean initialized;
  private Configuration conf;
  private ContainerTokenSecretManager containerTokenSecretManager;
  private RMContext rmContext;
  private Resource minimumAllocation;
  private Resource maximumAllocation;
  private PoolManager poolMgr;
  
  private static final Log LOG = LogFactory.getLog(FairScheduler.class);
 
  // Prefix for config variables
  public static final String CONFIG_PREFIX =  "yarn.scheduler.fair.";
  
  // Config vars for min/max allocation
  public static final String MINIMUM_ALLOCATION_CONFIG = 
      CONFIG_PREFIX + "minimum-allocation-mb";
  public static final String MAXIMUM_ALLOCATION_CONFIG = 
      CONFIG_PREFIX + "maximum-allocation-mb";
  
  // Defaults for min/max allocation
  private static final int MINIMUM_MEMORY = 512;
  private static final int MAXIMUM_MEMORY = 10240;

  // This stores per-application scheduling information, indexed by
  // attempt ID's for fast lookup.
  protected Map<ApplicationAttemptId, SchedulerApp> applications
  = new HashMap<ApplicationAttemptId, SchedulerApp>();
  
  // Nodes in the cluster, indexed by NodeId
  private Map<NodeId, SchedulerNode> nodes = 
      new ConcurrentHashMap<NodeId, SchedulerNode>();
  
  // Aggregate capacity of the cluster
  private Resource clusterCapacity = 
      RecordFactoryProvider.getRecordFactory(null).newRecordInstance(Resource.class);
  
  protected boolean sizeBasedWeight; // Give larger weights to larger jobs
  protected WeightAdjuster weightAdjuster; // Can be null for no weight adjuster

  private final static List<Container> EMPTY_CONTAINER_LIST = 
      new ArrayList<Container>();
  
  private static final Allocation EMPTY_ALLOCATION = 
      new Allocation(EMPTY_CONTAINER_LIST, Resources.createResource(0));
  

  public Configuration getConf() {
    return this.conf;
  }
  
  public PoolManager getPoolManager() {
    return this.poolMgr;
  }

  public List<PoolSchedulable> getPoolSchedulables() {
    List<PoolSchedulable> scheds = new ArrayList<PoolSchedulable>();
    for (Pool pool: poolMgr.getPools()) {
      scheds.add(pool.getPoolSchedulable());
    }
    return scheds;
  }
  
  private RMContainer getRMContainer(ContainerId containerId) {
    SchedulerApp application = 
        applications.get(containerId.getApplicationAttemptId());
    return (application == null) ? null : application.getRMContainer(containerId);
  }
  
  /**
  * Recompute the internal variables used by the scheduler - per-job weights,
  * fair shares, deficits, minimum slot allocations, and amount of used and
  * required resources per job.
  */
  protected void update() {    
    synchronized (this) {
      // TODO: reload allocation file?
    
      updateRunnability(); // Set job runnability based on user/pool limits 
      
      // Update demands of apps and pools
      for (Pool pool: poolMgr.getPools()) {
        pool.getPoolSchedulable().updateDemand();
      }
      
      // Compute fair shares based on updated demands
      List<PoolSchedulable> poolScheds = this.getPoolSchedulables();
      SchedulingAlgorithms.computeFairShares(
          poolScheds, clusterCapacity);
      
      // Use the computed shares to assign shares within each pool
      for (Pool pool: poolMgr.getPools()) {
        pool.getPoolSchedulable().redistributeShare();
      }
    }
  }
  
  /**
   * This updates the runnability of all apps based on whether or not
   * any users/pools have exceeded their capacity.
   */
  private void updateRunnability() {
    List<AppSchedulable> apps = new ArrayList<AppSchedulable>();
    
    // Start by marking everything as not runnable
    for (Pool p: poolMgr.getPools()) {
      for (AppSchedulable a: p.getPoolSchedulable().getAppSchedulables()) {
        a.getApp().setRunnable(false);
        apps.add(a);
      }
    }
    // Create a list of sorted jobs in order of start time and priority
    Collections.sort(apps, new FifoAppComparator());
    // Mark jobs as runnable in order of start time and priority, until
    // user or pool limits have been reached.
    Map<String, Integer> userJobs = new HashMap<String, Integer>();
    Map<String, Integer> poolJobs = new HashMap<String, Integer>();
    for (AppSchedulable app: apps) {
      String user = app.getApp().getUser();
      String pool = app.getApp().getQueueName();
      int userCount = userJobs.containsKey(user) ? userJobs.get(user) : 0;
      int poolCount = poolJobs.containsKey(pool) ? poolJobs.get(pool) : 0;
      if (userCount < poolMgr.getUserMaxApps(user) &&
          poolCount < poolMgr.getPoolMaxApps(pool)) {
        userJobs.put(user, userCount + 1);
        poolJobs.put(pool, poolCount + 1);
        app.getApp().setRunnable(true);
      }
    }
  }
  
  public ContainerTokenSecretManager getContainerTokenSecretManager() {
    return this.containerTokenSecretManager;
  }
  
  public double getAppWeight(AppSchedulable app) {
    if (!app.getApp().isRunnable()) {
      // Job won't launch tasks, but don't return 0 to avoid division errors
      return 1.0;
    } else {
      double weight = 1.0;
      if (sizeBasedWeight) {
        // Set weight based on runnable tasks
        // TODO: maybe this should consider both current and pending resources?
        weight = Math.log1p(app.getResourceUsage().getMemory()) / Math.log(2);
      }
      weight *= app.getPriority().getPriority(); // TODO maybe use indirect function of prio
      if (weightAdjuster != null) {
        // Run weight through the user-supplied weightAdjuster
        weight = weightAdjuster.adjustWeight(app, weight);
      }
      return weight;
    }
  }
  
  @Override
  public Resource getMinimumResourceCapability() {
    return this.minimumAllocation;
  }

  @Override
  public Resource getMaximumResourceCapability() {
    return this.maximumAllocation;
  }
  
  public Resource getClusterCapacity() {
    return this.clusterCapacity;
  }


  /**
   * Add a new application to the scheduler, with a given id, pool name,
   * and user.
   * @param applicationAttemptId
   * @param queueName
   * @param user
   */
  protected synchronized void
  addApplication(ApplicationAttemptId applicationAttemptId,
      String poolName, String user) {
    Pool pool = this.poolMgr.getPool(poolName);
    if (pool == null) {
      String message = "Application " + applicationAttemptId + 
          " submitted by user " + user + " to unknown pool: " + poolName;
      this.rmContext.getDispatcher().getEventHandler().handle(
          new RMAppAttemptRejectedEvent(applicationAttemptId, message));
      return;
    }
    
    // The Store class seems completely unused.
    SchedulerApp schedulerApp = 
        new SchedulerApp(applicationAttemptId, user, pool, rmContext, null);

   // TODO: ACL
    pool.addApp(schedulerApp);

    applications.put(applicationAttemptId, schedulerApp);

    LOG.info("Application Submission: " + applicationAttemptId + 
        ", user: " + user +
        ", currently active: " + applications.size());

    rmContext.getDispatcher().getEventHandler().handle(
        new RMAppAttemptEvent(applicationAttemptId,
            RMAppAttemptEventType.APP_ACCEPTED));
  }
  
  private synchronized void removeApplication(
      ApplicationAttemptId applicationAttemptId,
      RMAppAttemptState rmAppAttemptFinalState) {
    LOG.info("Application " + applicationAttemptId + " is done." +
        " finalState=" + rmAppAttemptFinalState);
    
    SchedulerApp application = applications.get(applicationAttemptId);

    if (application == null) {
      LOG.info("Unknown application " + applicationAttemptId + " has completed!");
      return;
    }
    
    // Release all the running containers 
    for (RMContainer rmContainer : application.getLiveContainers()) {
      completedContainer(rmContainer, 
          SchedulerUtils.createAbnormalContainerStatus(
              rmContainer.getContainerId(), 
              SchedulerUtils.COMPLETED_APPLICATION), 
          RMContainerEventType.KILL);
    }
    
     // Release all reserved containers
    for (RMContainer rmContainer : application.getReservedContainers()) {
      completedContainer(rmContainer, 
          SchedulerUtils.createAbnormalContainerStatus(
              rmContainer.getContainerId(), 
              "Application Complete"), 
          RMContainerEventType.KILL);
    }
    
    // Clean up pending requests, metrics etc.
    application.stop(rmAppAttemptFinalState);
    
    // Inform the pool
    Pool pool = this.poolMgr.getPool(application.getQueue().getQueueName());
    pool.removeJob(application);
    
    // Remove from our data-structure
    applications.remove(applicationAttemptId);
  }
  
  /**
   * Clean up a completed container. This involves (TODO)
   * @param rmContainer
   * @param containerStatus
   * @param event
   */
  private synchronized void completedContainer(RMContainer rmContainer,
      ContainerStatus containerStatus, RMContainerEventType event) {
    if (rmContainer == null) {
      LOG.info("Null container completed...");
      return;
    }
    
    Container container = rmContainer.getContainer();
    
    // Get the application for the finished container
    ApplicationAttemptId applicationAttemptId = container.getId().getApplicationAttemptId();
    SchedulerApp application = applications.get(applicationAttemptId);
    if (application == null) {
      LOG.info("Container " + container + " of" +
          " unknown application " + applicationAttemptId + 
          " completed with event " + event);
      return;
    }
    
    // Get the node on which the container was allocated
    SchedulerNode node = nodes.get(container.getNodeId());

    // TODO, not sure if this is all we need to do here (see Capacity)
    application.containerCompleted(rmContainer, containerStatus, event);
    node.unreserveResource(application);

    LOG.info("Application " + applicationAttemptId + 
        " released container " + container.getId() +
        " on node: " + node + 
        " with event: " + event);
  }
  
  private synchronized void addNode(RMNode node) {
    this.nodes.put(node.getNodeID(), new SchedulerNode(node));
    Resources.addTo(clusterCapacity, node.getTotalCapability());
    
    LOG.info("Added node " + node.getNodeAddress() + 
        " cluster capacity: " + clusterCapacity);
  }

  private synchronized void removeNode(RMNode rmNode) {
    SchedulerNode node = this.nodes.get(rmNode.getNodeID());
    Resources.subtractFrom(clusterCapacity, rmNode.getTotalCapability());

    // Remove running containers
    List<RMContainer> runningContainers = node.getRunningContainers();
    for (RMContainer container : runningContainers) {
      completedContainer(container, 
          SchedulerUtils.createAbnormalContainerStatus(
              container.getContainerId(), 
              SchedulerUtils.LOST_CONTAINER), 
          RMContainerEventType.KILL);
    }
    
    // Remove reservations, if any
    RMContainer reservedContainer = node.getReservedContainer();
    if (reservedContainer != null) {
      completedContainer(reservedContainer, 
          SchedulerUtils.createAbnormalContainerStatus(
              reservedContainer.getContainerId(), 
              SchedulerUtils.LOST_CONTAINER), 
          RMContainerEventType.KILL);
    }

    this.nodes.remove(rmNode.getNodeID());
    LOG.info("Removed node " + rmNode.getNodeAddress() + 
        " cluster capacity: " + clusterCapacity);
  }
  
  @Override
  public Allocation allocate(ApplicationAttemptId appAttemptId,
      List<ResourceRequest> ask, List<ContainerId> release) {
    
    // Make sure this application exists
    SchedulerApp application = applications.get(appAttemptId);
    if (application == null) {
      LOG.info("Calling allocate on removed " +
          "or non existant application " + appAttemptId);
      return EMPTY_ALLOCATION;
    }
    
    // Sanity check
    SchedulerUtils.normalizeRequests(ask, minimumAllocation.getMemory());

    // Release containers
    for (ContainerId releasedContainerId : release) {
      RMContainer rmContainer = getRMContainer(releasedContainerId);
      if (rmContainer == null) {
         RMAuditLogger.logFailure(application.getUser(),
             AuditConstants.RELEASE_CONTAINER, 
             "Unauthorized access or invalid container", "CapacityScheduler",
             "Trying to release container not owned by app or with invalid id",
             application.getApplicationId(), releasedContainerId);
      }
      completedContainer(rmContainer,
          SchedulerUtils.createAbnormalContainerStatus(
              releasedContainerId, 
              SchedulerUtils.RELEASED_CONTAINER),
          RMContainerEventType.RELEASED);
    }
    
    synchronized (application) {

      if (!ask.isEmpty()) {

        if(LOG.isDebugEnabled()) {
          LOG.debug("allocate: pre-update" +
            " applicationAttemptId=" + appAttemptId + 
            " application=" + application);
        }
        application.showRequests();
  
        // Update application requests
        application.updateResourceRequests(ask);
  
        LOG.debug("allocate: post-update");
        application.showRequests();
      }

      if(LOG.isDebugEnabled()) {
        LOG.debug("allocate:" +
          " applicationAttemptId=" + appAttemptId + 
          " #ask=" + ask.size());
      }

      return new Allocation(
          application.pullNewlyAllocatedContainers(), 
          application.getHeadroom());
    }
  }
  
  private void containerLaunchedOnNode(ContainerId containerId, SchedulerNode node) {
    // Get the application for the finished container
    ApplicationAttemptId applicationAttemptId = containerId.getApplicationAttemptId();
    SchedulerApp application = applications.get(applicationAttemptId);
    if (application == null) {
      LOG.info("Unknown application: " + applicationAttemptId + 
          " launched container " + containerId +
          " on node: " + node);
      return;
    }
    
    application.containerLaunchedOnNode(containerId);
  }
  
  private synchronized void nodeUpdate(RMNode nm, 
      List<ContainerStatus> newlyLaunchedContainers,
      List<ContainerStatus> completedContainers) {
    LOG.info("nodeUpdate: " + nm + " cluster capacity: " + clusterCapacity);
    
    SchedulerNode node = nodes.get(nm.getNodeID());

    // Processing the newly launched containers
    for (ContainerStatus launchedContainer : newlyLaunchedContainers) {
      containerLaunchedOnNode(launchedContainer.getContainerId(), node);
    }

    // Process completed containers
    for (ContainerStatus completedContainer : completedContainers) {
      ContainerId containerId = completedContainer.getContainerId();
      LOG.debug("Container FINISHED: " + containerId);
      completedContainer(getRMContainer(containerId), 
          completedContainer, RMContainerEventType.FINISHED);
    }

    // Assign new containers...
    // 1. Check for reserved applications
    // 2. Schedule if there are no reservations

    // If we have have an application that has reserved a resource on this node
    // already, we try to complete the reservation.
    RMContainer reservedContainer = node.getReservedContainer();
    if (reservedContainer != null) {
      SchedulerApp reservedApplication = 
          applications.get(reservedContainer.getApplicationAttemptId());
      
      // Try to fulfill the reservation
      LOG.info("Trying to fulfill reservation for application " + 
          reservedApplication.getApplicationId() + " on node: " + nm);
      
      Pool pool = poolMgr.getPool(reservedApplication.getQueueName());
      pool.getPoolSchedulable().assignContainer(node, true);
    }

    
    // Otherwise, schedule at pool which is furthest below fair share
    else {
      List<PoolSchedulable> scheds = this.getPoolSchedulables();
      Collections.sort(scheds, new SchedulingAlgorithms.FairShareComparator());
      //TODO: RETURN IF SCHEDULE ANYTHING HERE
      for (PoolSchedulable sched : scheds) {
        Resource assigned = sched.assignContainer(node, false);
        if (Resources.greaterThan(assigned, Resources.none())) {
          break;
        }
      }
    }
  }

  @Override
  public SchedulerNodeReport getNodeReport(NodeId nodeId) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public SchedulerAppReport getSchedulerAppInfo(
      ApplicationAttemptId appAttemptId) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public QueueMetrics getRootQueueMetrics() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void handle(SchedulerEvent event) {
    switch(event.getType()) {
    case NODE_ADDED:
    {
      NodeAddedSchedulerEvent nodeAddedEvent = (NodeAddedSchedulerEvent)event;
      addNode(nodeAddedEvent.getAddedRMNode());
    }
    break;
    case NODE_REMOVED:
    {
      NodeRemovedSchedulerEvent nodeRemovedEvent = (NodeRemovedSchedulerEvent)event;
      removeNode(nodeRemovedEvent.getRemovedRMNode());
    }
    break;
    case NODE_UPDATE:
    {
      NodeUpdateSchedulerEvent nodeUpdatedEvent = 
      (NodeUpdateSchedulerEvent)event;
      this.nodeUpdate(nodeUpdatedEvent.getRMNode(), 
          nodeUpdatedEvent.getNewlyLaunchedContainers(),
          nodeUpdatedEvent.getCompletedContainers());
    }
    break;
    case APP_ADDED:
    {
      AppAddedSchedulerEvent appAddedEvent = (AppAddedSchedulerEvent) event;
      addApplication(appAddedEvent.getApplicationAttemptId(), appAddedEvent
          .getQueue(), appAddedEvent.getUser());
    }
    break;
    case APP_REMOVED:
    {
      AppRemovedSchedulerEvent appRemovedEvent = (AppRemovedSchedulerEvent)event;
      this.removeApplication(appRemovedEvent.getApplicationAttemptID(),
          appRemovedEvent.getFinalAttemptState());
    }
    break;
    case CONTAINER_EXPIRED:
    {
      ContainerExpiredSchedulerEvent containerExpiredEvent = 
          (ContainerExpiredSchedulerEvent) event;
    }
    break;
    default:
      // TODO: Handle ERROR
    }
  }

  @Override
  public void recover(RMState state) throws Exception {
    // TODO Auto-generated method stub
    
  }

  @Override
  public synchronized void reinitialize(Configuration conf,
      ContainerTokenSecretManager containerTokenSecretManager, 
      RMContext rmContext) 
  throws IOException 
  {
    if (!this.initialized) {
      this.conf = conf;
      this.containerTokenSecretManager = containerTokenSecretManager;
      this.rmContext = rmContext;
      minimumAllocation = 
        Resources.createResource(conf.getInt(MINIMUM_ALLOCATION_CONFIG, MINIMUM_MEMORY));
      maximumAllocation = 
        Resources.createResource(conf.getInt(MAXIMUM_ALLOCATION_CONFIG, MAXIMUM_MEMORY));
      initialized = true;
      
      sizeBasedWeight = conf.getBoolean(
          CONFIG_PREFIX + ".sizebasedweight", false);
      
      poolMgr = new PoolManager(this);
      
      try {
        poolMgr.initialize();
      }
      catch (Exception e) {
        throw new IOException("Failed to start FairScheduler", e);
      }
    } else {
      this.conf = conf;
      
      try {
       poolMgr.reloadAllocs(); //TODO: Maybe this should be based on a timer like
                               // in the old incarnation. Not sure of semantics of reinit.
      }
      catch (Exception e) {
        throw new IOException("Failed to initialize FairScheduler", e);
      }
    }
  }

  @Override
  public QueueInfo getQueueInfo(String queueName, boolean includeChildQueues,
      boolean recursive) throws IOException {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public List<QueueUserACLInfo> getQueueUserAclInfo() {
    // TODO Auto-generated method stub
    return null;
  }

}
