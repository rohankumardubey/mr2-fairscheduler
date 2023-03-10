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

import org.apache.hadoop.yarn.api.records.Priority;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.server.resourcemanager.resource.Resources;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.QueueMetrics;

/**
 * A Schedulable represents an entity that can launch tasks, such as a job
 * or a pool. It provides a common interface so that algorithms such as fair
 * sharing can be applied both within a pool and across pools. There are 
 * currently two types of Schedulables: JobSchedulables, which represent a
 * single job, and PoolSchedulables, which allocate among jobs in their pool.
 * 
 * Separate sets of Schedulables are used for maps and reduces. Each pool has
 * both a mapSchedulable and a reduceSchedulable, and so does each job.
 * 
 * A Schedulable is responsible for three roles:
 * 1) It can launch tasks through assignTask().
 * 2) It provides information about the job/pool to the scheduler, including:
 *    - Demand (maximum number of tasks required)
 *    - Number of currently running tasks
 *    - Minimum share (for pools)
 *    - Job/pool weight (for fair sharing)
 *    - Start time and priority (for FIFO)
 * 3) It can be assigned a fair share, for use with fair scheduling.
 * 
 * Schedulable also contains two methods for performing scheduling computations:
 * - updateDemand() is called periodically to compute the demand of the various
 *   jobs and pools, which may be expensive (e.g. jobs must iterate through all
 *   their tasks to count failed tasks, tasks that can be speculated, etc).
 * - redistributeShare() is called after demands are updated and a Schedulable's
 *   fair share has been set by its parent to let it distribute its share among
 *   the other Schedulables within it (e.g. for pools that want to perform fair
 *   sharing among their jobs).
 */
abstract class Schedulable {
  /** Fair share assigned to this Schedulable */
  private Resource fairShare = Resources.createResource(0);
  
  /**
   * PATRICK: Not sure how these metrics are going to come into play
   * @return
   */
  public abstract QueueMetrics getMetrics();
  
  /**
   * Name of job/pool, used for debugging as well as for breaking ties in
   * scheduling order deterministically. 
   */
  public abstract String getName();
  
  /**
   * Maximum number of resources required by this Schedulable. This is defined as
   * number of currently utilized resources + number of unlaunched resources (that
   * are either not yet launched or need to be speculated).
   */
  public abstract Resource getDemand();
  
  /** Get the aggregate amount of resources consumed by the schedulable. */
  public abstract Resource getResourceUsage();
  
  /** Minimum Resource share assigned to the schedulable. */
  public abstract Resource getMinShare();
  

  /** Job/pool weight in fair sharing. */
  public abstract double getWeight();
  
  /** Start time for jobs in FIFO pools; meaningless for PoolSchedulables.*/
  public abstract long getStartTime();
  
 /** Job priority for jobs in FIFO pools; meaningless for PoolSchedulables. */
  public abstract Priority getPriority();

  /** Refresh the Schedulable's demand and those of its children if any. */
  public abstract void updateDemand();
  
  /** 
   * Distribute the fair share assigned to this Schedulable among its 
   * children (used in pools where the internal scheduler is fair sharing). 
   */
  public abstract void redistributeShare();
  
  /** HIDDEN FOR NOW
  /**
   * Obtain a task for a given TaskTracker, or null if the Schedulable has
   * no tasks to launch at this moment or does not wish to launch a task on
   * this TaskTracker (e.g. is waiting for a TaskTracker with local data). 
   * In addition, if a job is skipped during this search because it is waiting
   * for a TaskTracker with local data, this method is expected to add it to
   * the <tt>visited</tt> collection passed in, so that the scheduler can
   * properly mark it as skipped during this heartbeat. Please see
   * {@link FairScheduler#getAllowedLocalityLevel(JobInProgress, long)}
   * for details of delay scheduling (waiting for trackers with local data).
   * 
   * @param tts      TaskTracker that the task will be launched on
   * @param currentTime Cached time (to prevent excessive calls to gettimeofday)
   * @param visited  A Collection to which this method must add all jobs that
   *                 were considered during the search for a job to assign.
   * @return Task to launch, or null if Schedulable cannot currently launch one.
   * @throws IOException Possible if obtainNew(Map|Reduce)Task throws exception.

  public abstract Task assignTask(TaskTrackerStatus tts, long currentTime,
      Collection<JobInProgress> visited) throws IOException;
  */

  /** Assign a fair share to this Schedulable. */
  public void setFairShare(Resource fairShare) {
    this.fairShare = fairShare;
  }
  
  /** Get the fair share assigned to this Schedulable. */
  public Resource getFairShare() {
    return fairShare;
  }
  
  /** Convenient toString implementation for debugging. */
  @Override
  public String toString() {
    return String.format("[%s, demand=%d, running=%d, share=%.1f,], w=%.1f]",
        getName(), getDemand(), getResourceUsage(), fairShare, getWeight());
  }
}
