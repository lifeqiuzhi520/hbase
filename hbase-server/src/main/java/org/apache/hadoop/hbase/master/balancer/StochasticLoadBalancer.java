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
package org.apache.hadoop.hbase.master.balancer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.ClusterStatus;
import org.apache.hadoop.hbase.HBaseInterfaceAudience;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.RegionLoad;
import org.apache.hadoop.hbase.ServerLoad;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.master.MasterServices;
import org.apache.hadoop.hbase.master.RegionPlan;
import org.apache.hadoop.hbase.master.balancer.BaseLoadBalancer.Cluster.Action;
import org.apache.hadoop.hbase.master.balancer.BaseLoadBalancer.Cluster.Action.Type;
import org.apache.hadoop.hbase.master.balancer.BaseLoadBalancer.Cluster.AssignRegionAction;
import org.apache.hadoop.hbase.master.balancer.BaseLoadBalancer.Cluster.MoveRegionAction;
import org.apache.hadoop.hbase.master.balancer.BaseLoadBalancer.Cluster.SwapRegionsAction;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.EnvironmentEdgeManager;
import org.apache.hadoop.hbase.util.Pair;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;

/**
 * <p>This is a best effort load balancer. Given a Cost function F(C) =&gt; x It will
 * randomly try and mutate the cluster to Cprime. If F(Cprime) &lt; F(C) then the
 * new cluster state becomes the plan. It includes costs functions to compute the cost of:</p>
 * <ul>
 * <li>Region Load</li>
 * <li>Table Load</li>
 * <li>Data Locality</li>
 * <li>Memstore Sizes</li>
 * <li>Storefile Sizes</li>
 * </ul>
 *
 *
 * <p>Every cost function returns a number between 0 and 1 inclusive; where 0 is the lowest cost
 * best solution, and 1 is the highest possible cost and the worst solution.  The computed costs are
 * scaled by their respective multipliers:</p>
 *
 * <ul>
 *   <li>hbase.master.balancer.stochastic.regionLoadCost</li>
 *   <li>hbase.master.balancer.stochastic.moveCost</li>
 *   <li>hbase.master.balancer.stochastic.tableLoadCost</li>
 *   <li>hbase.master.balancer.stochastic.localityCost</li>
 *   <li>hbase.master.balancer.stochastic.memstoreSizeCost</li>
 *   <li>hbase.master.balancer.stochastic.storefileSizeCost</li>
 * </ul>
 *
 * <p>In addition to the above configurations, the balancer can be tuned by the following
 * configuration values:</p>
 * <ul>
 *   <li>hbase.master.balancer.stochastic.maxMoveRegions which
 *   controls what the max number of regions that can be moved in a single invocation of this
 *   balancer.</li>
 *   <li>hbase.master.balancer.stochastic.stepsPerRegion is the coefficient by which the number of
 *   regions is multiplied to try and get the number of times the balancer will
 *   mutate all servers.</li>
 *   <li>hbase.master.balancer.stochastic.maxSteps which controls the maximum number of times that
 *   the balancer will try and mutate all the servers. The balancer will use the minimum of this
 *   value and the above computation.</li>
 * </ul>
 *
 * <p>This balancer is best used with hbase.master.loadbalance.bytable set to false
 * so that the balancer gets the full picture of all loads on the cluster.</p>
 */
@InterfaceAudience.LimitedPrivate(HBaseInterfaceAudience.CONFIG)
@edu.umd.cs.findbugs.annotations.SuppressWarnings(value="IS2_INCONSISTENT_SYNC",
  justification="Complaint is about costFunctions not being synchronized; not end of the world")
public class StochasticLoadBalancer extends BaseLoadBalancer {

  protected static final String STEPS_PER_REGION_KEY =
      "hbase.master.balancer.stochastic.stepsPerRegion";
  protected static final String MAX_STEPS_KEY =
      "hbase.master.balancer.stochastic.maxSteps";
  protected static final String MAX_RUNNING_TIME_KEY =
      "hbase.master.balancer.stochastic.maxRunningTime";
  protected static final String KEEP_REGION_LOADS =
      "hbase.master.balancer.stochastic.numRegionLoadsToRemember";
  private static final String TABLE_FUNCTION_SEP = "_";
  protected static final String MIN_COST_NEED_BALANCE_KEY =
      "hbase.master.balancer.stochastic.minCostNeedBalance";

  private static final Random RANDOM = new Random(System.currentTimeMillis());
  private static final Log LOG = LogFactory.getLog(StochasticLoadBalancer.class);

  Map<String, Deque<BalancerRegionLoad>> loads = new HashMap<>();

  // values are defaults
  private int maxSteps = 1000000;
  private int stepsPerRegion = 800;
  private long maxRunningTime = 30 * 1000 * 1; // 30 seconds.
  private int numRegionLoadsToRemember = 15;
  private float minCostNeedBalance = 0.05f;

  private CandidateGenerator[] candidateGenerators;
  private CostFromRegionLoadFunction[] regionLoadFunctions;
  private CostFunction[] costFunctions; // FindBugs: Wants this protected; IS2_INCONSISTENT_SYNC

  // to save and report costs to JMX
  private Double curOverallCost = 0d;
  private Double[] tempFunctionCosts;
  private Double[] curFunctionCosts;

  // Keep locality based picker and cost function to alert them
  // when new services are offered
  private LocalityBasedCandidateGenerator localityCandidateGenerator;
  private LocalityCostFunction localityCost;
  private RegionReplicaHostCostFunction regionReplicaHostCostFunction;
  private RegionReplicaRackCostFunction regionReplicaRackCostFunction;
  private boolean isByTable = false;
  private TableName tableName = null;

  /**
   * The constructor that pass a MetricsStochasticBalancer to BaseLoadBalancer to replace its
   * default MetricsBalancer
   */
  public StochasticLoadBalancer() {
    super(new MetricsStochasticBalancer());
  }

  @Override
  public void onConfigurationChange(Configuration conf) {
    setConf(conf);
  }

  @Override
  public synchronized void setConf(Configuration conf) {
    super.setConf(conf);
    LOG.info("loading config");

    maxSteps = conf.getInt(MAX_STEPS_KEY, maxSteps);

    stepsPerRegion = conf.getInt(STEPS_PER_REGION_KEY, stepsPerRegion);
    maxRunningTime = conf.getLong(MAX_RUNNING_TIME_KEY, maxRunningTime);

    numRegionLoadsToRemember = conf.getInt(KEEP_REGION_LOADS, numRegionLoadsToRemember);
    isByTable = conf.getBoolean(HConstants.HBASE_MASTER_LOADBALANCE_BYTABLE, isByTable);

    minCostNeedBalance = conf.getFloat(MIN_COST_NEED_BALANCE_KEY, minCostNeedBalance);

    if (localityCandidateGenerator == null) {
      localityCandidateGenerator = new LocalityBasedCandidateGenerator(services);
    }
    localityCost = new LocalityCostFunction(conf, services);

    if (candidateGenerators == null) {
      candidateGenerators = new CandidateGenerator[] {
          new RandomCandidateGenerator(),
          new LoadCandidateGenerator(),
          localityCandidateGenerator,
          new RegionReplicaRackCandidateGenerator(),
      };
    }

    regionLoadFunctions = new CostFromRegionLoadFunction[] {
      new ReadRequestCostFunction(conf),
      new WriteRequestCostFunction(conf),
      new MemstoreSizeCostFunction(conf),
      new StoreFileCostFunction(conf)
    };

    regionReplicaHostCostFunction = new RegionReplicaHostCostFunction(conf);
    regionReplicaRackCostFunction = new RegionReplicaRackCostFunction(conf);

    costFunctions = new CostFunction[]{
      new RegionCountSkewCostFunction(conf),
      new PrimaryRegionCountSkewCostFunction(conf),
      new MoveCostFunction(conf),
      localityCost,
      new TableSkewCostFunction(conf),
      regionReplicaHostCostFunction,
      regionReplicaRackCostFunction,
      regionLoadFunctions[0],
      regionLoadFunctions[1],
      regionLoadFunctions[2],
      regionLoadFunctions[3],
    };

    curFunctionCosts= new Double[costFunctions.length];
    tempFunctionCosts= new Double[costFunctions.length];

  }

  @Override
  protected void setSlop(Configuration conf) {
    this.slop = conf.getFloat("hbase.regions.slop", 0.001F);
  }

  @Override
  public synchronized void setClusterStatus(ClusterStatus st) {
    super.setClusterStatus(st);
    updateRegionLoad();
    for(CostFromRegionLoadFunction cost : regionLoadFunctions) {
      cost.setClusterStatus(st);
    }

    // update metrics size
    try {
      // by-table or ensemble mode
      int tablesCount = isByTable ? services.getTableDescriptors().getAll().size() : 1;
      int functionsCount = getCostFunctionNames().length;

      updateMetricsSize(tablesCount * (functionsCount + 1)); // +1 for overall
    } catch (Exception e) {
      LOG.error("failed to get the size of all tables", e);
    }
  }

  /**
   * Update the number of metrics that are reported to JMX
   */
  public void updateMetricsSize(int size) {
    if (metricsBalancer instanceof MetricsStochasticBalancer) {
        ((MetricsStochasticBalancer) metricsBalancer).updateMetricsSize(size);
    }
  }

  @Override
  public synchronized void setMasterServices(MasterServices masterServices) {
    super.setMasterServices(masterServices);
    this.localityCost.setServices(masterServices);
    this.localityCandidateGenerator.setServices(masterServices);

  }

  @Override
  protected synchronized boolean areSomeRegionReplicasColocated(Cluster c) {
    regionReplicaHostCostFunction.init(c);
    if (regionReplicaHostCostFunction.cost() > 0) return true;
    regionReplicaRackCostFunction.init(c);
    if (regionReplicaRackCostFunction.cost() > 0) return true;
    return false;
  }

  @Override
  protected boolean needsBalance(Cluster cluster) {
    if (areSomeRegionReplicasColocated(cluster)) {
      return true;
    }

    double total = 0.0;
    float sumMultiplier = 0.0f;
    for (CostFunction c : costFunctions) {
      float multiplier = c.getMultiplier();
      if (multiplier <= 0) {
        continue;
      }
      if (!c.isNeeded()) {
        LOG.debug(c.getClass().getName() + " indicated that its cost should not be considered");
        continue;
      }
      sumMultiplier += multiplier;
      total += c.cost() * multiplier;
    }

    if (total <= 0 || sumMultiplier <= 0
        || (sumMultiplier > 0 && (total / sumMultiplier) < minCostNeedBalance)) {
      LOG.info("Skipping load balancing because balanced cluster; " + "total cost is " + total
          + ", sum multiplier is " + sumMultiplier + " min cost which need balance is "
          + minCostNeedBalance);
      return false;
    }

    ClusterLoadState cs = new ClusterLoadState(cluster.clusterState);
    if (cs.getNumServers() < MIN_SERVER_BALANCE) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Not running balancer because only " + cs.getNumServers()
            + " active regionserver(s)");
      }
      return false;
    }


    return true;
  }

  @Override
  public synchronized List<RegionPlan> balanceCluster(TableName tableName, Map<ServerName,
    List<HRegionInfo>> clusterState) {
    this.tableName = tableName;
    return balanceCluster(clusterState);
  }

  /**
   * Given the cluster state this will try and approach an optimal balance. This
   * should always approach the optimal state given enough steps.
   */
  @Override
  public synchronized List<RegionPlan> balanceCluster(Map<ServerName,
    List<HRegionInfo>> clusterState) {
    List<RegionPlan> plans = balanceMasterRegions(clusterState);
    if (plans != null || clusterState == null || clusterState.size() <= 1) {
      return plans;
    }

    if (masterServerName != null && clusterState.containsKey(masterServerName)) {
      if (clusterState.size() <= 2) {
        return null;
      }
      clusterState = new HashMap<>(clusterState);
      clusterState.remove(masterServerName);
    }

    // On clusters with lots of HFileLinks or lots of reference files,
    // instantiating the storefile infos can be quite expensive.
    // Allow turning this feature off if the locality cost is not going to
    // be used in any computations.
    RegionLocationFinder finder = null;
    if (this.localityCost != null && this.localityCost.getMultiplier() > 0) {
      finder = this.regionFinder;
    }

    //The clusterState that is given to this method contains the state
    //of all the regions in the table(s) (that's true today)
    // Keep track of servers to iterate through them.
    Cluster cluster = new Cluster(clusterState, loads, finder, rackManager);

    long startTime = EnvironmentEdgeManager.currentTime();

    initCosts(cluster);

    if (!needsBalance(cluster)) {
      return null;
    }

    double currentCost = computeCost(cluster, Double.MAX_VALUE);
    curOverallCost = currentCost;
    for (int i = 0; i < this.curFunctionCosts.length; i++) {
      curFunctionCosts[i] = tempFunctionCosts[i];
    }
    LOG.info("start StochasticLoadBalancer.balancer, initCost=" + currentCost + ", functionCost="
        + functionCost());

    double initCost = currentCost;
    double newCost = currentCost;

    long computedMaxSteps = Math.min(this.maxSteps,
        ((long)cluster.numRegions * (long)this.stepsPerRegion * (long)cluster.numServers));
    // Perform a stochastic walk to see if we can get a good fit.
    long step;

    for (step = 0; step < computedMaxSteps; step++) {
      int generatorIdx = RANDOM.nextInt(candidateGenerators.length);
      CandidateGenerator p = candidateGenerators[generatorIdx];
      Cluster.Action action = p.generate(cluster);

      if (action.type == Type.NULL) {
        continue;
      }

      cluster.doAction(action);
      updateCostsWithAction(cluster, action);

      newCost = computeCost(cluster, currentCost);

      // Should this be kept?
      if (newCost < currentCost) {
        currentCost = newCost;

        // save for JMX
        curOverallCost = currentCost;
        for (int i = 0; i < this.curFunctionCosts.length; i++) {
          curFunctionCosts[i] = tempFunctionCosts[i];
        }
      } else {
        // Put things back the way they were before.
        // TODO: undo by remembering old values
        Action undoAction = action.undoAction();
        cluster.doAction(undoAction);
        updateCostsWithAction(cluster, undoAction);
      }

      if (EnvironmentEdgeManager.currentTime() - startTime >
          maxRunningTime) {
        break;
      }
    }
    long endTime = EnvironmentEdgeManager.currentTime();

    metricsBalancer.balanceCluster(endTime - startTime);

    // update costs metrics
    updateStochasticCosts(tableName, curOverallCost, curFunctionCosts);
    if (initCost > currentCost) {
      plans = createRegionPlans(cluster);
      if (LOG.isDebugEnabled()) {
        LOG.debug("Finished computing new load balance plan.  Computation took "
            + (endTime - startTime) + "ms to try " + step
            + " different iterations.  Found a solution that moves "
            + plans.size() + " regions; Going from a computed cost of "
            + initCost + " to a new cost of " + currentCost);
      }

      return plans;
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug("Could not find a better load balance plan.  Tried "
          + step + " different configurations in " + (endTime - startTime)
          + "ms, and did not find anything with a computed cost less than " + initCost);
    }
    return null;
  }

  /**
   * update costs to JMX
   */
  private void updateStochasticCosts(TableName tableName, Double overall, Double[] subCosts) {
    if (tableName == null) return;

    // check if the metricsBalancer is MetricsStochasticBalancer before casting
    if (metricsBalancer instanceof MetricsStochasticBalancer) {
      MetricsStochasticBalancer balancer = (MetricsStochasticBalancer) metricsBalancer;
      // overall cost
      balancer.updateStochasticCost(tableName.getNameAsString(),
        "Overall", "Overall cost", overall);

      // each cost function
      for (int i = 0; i < costFunctions.length; i++) {
        CostFunction costFunction = costFunctions[i];
        String costFunctionName = costFunction.getClass().getSimpleName();
        Double costPercent = (overall == 0) ? 0 : (subCosts[i] / overall);
        // TODO: cost function may need a specific description
        balancer.updateStochasticCost(tableName.getNameAsString(), costFunctionName,
          "The percent of " + costFunctionName, costPercent);
      }
    }
  }

  private String functionCost() {
    StringBuilder builder = new StringBuilder();
    for (CostFunction c:costFunctions) {
      builder.append(c.getClass().getSimpleName());
      builder.append(" : (");
      builder.append(c.getMultiplier());
      builder.append(", ");
      builder.append(c.cost());
      builder.append("); ");
    }
    return builder.toString();
  }

  /**
   * Create all of the RegionPlan's needed to move from the initial cluster state to the desired
   * state.
   *
   * @param cluster The state of the cluster
   * @return List of RegionPlan's that represent the moves needed to get to desired final state.
   */
  private List<RegionPlan> createRegionPlans(Cluster cluster) {
    List<RegionPlan> plans = new LinkedList<>();
    for (int regionIndex = 0;
         regionIndex < cluster.regionIndexToServerIndex.length; regionIndex++) {
      int initialServerIndex = cluster.initialRegionIndexToServerIndex[regionIndex];
      int newServerIndex = cluster.regionIndexToServerIndex[regionIndex];

      if (initialServerIndex != newServerIndex) {
        HRegionInfo region = cluster.regions[regionIndex];
        ServerName initialServer = cluster.servers[initialServerIndex];
        ServerName newServer = cluster.servers[newServerIndex];

        if (LOG.isTraceEnabled()) {
          LOG.trace("Moving Region " + region.getEncodedName() + " from server "
              + initialServer.getHostname() + " to " + newServer.getHostname());
        }
        RegionPlan rp = new RegionPlan(region, initialServer, newServer);
        plans.add(rp);
      }
    }
    return plans;
  }

  /**
   * Store the current region loads.
   */
  private synchronized void updateRegionLoad() {
    // We create a new hashmap so that regions that are no longer there are removed.
    // However we temporarily need the old loads so we can use them to keep the rolling average.
    Map<String, Deque<BalancerRegionLoad>> oldLoads = loads;
    loads = new HashMap<>();

    for (ServerName sn : clusterStatus.getServers()) {
      ServerLoad sl = clusterStatus.getLoad(sn);
      if (sl == null) {
        continue;
      }
      for (Entry<byte[], RegionLoad> entry : sl.getRegionsLoad().entrySet()) {
        Deque<BalancerRegionLoad> rLoads = oldLoads.get(Bytes.toString(entry.getKey()));
        if (rLoads == null) {
          // There was nothing there
          rLoads = new ArrayDeque<>();
        } else if (rLoads.size() >= numRegionLoadsToRemember) {
          rLoads.remove();
        }
        rLoads.add(new BalancerRegionLoad(entry.getValue()));
        loads.put(Bytes.toString(entry.getKey()), rLoads);
      }
    }

    for(CostFromRegionLoadFunction cost : regionLoadFunctions) {
      cost.setLoads(loads);
    }
  }

  protected void initCosts(Cluster cluster) {
    for (CostFunction c:costFunctions) {
      c.init(cluster);
    }
  }

  protected void updateCostsWithAction(Cluster cluster, Action action) {
    for (CostFunction c : costFunctions) {
      c.postAction(action);
    }
  }

  /**
   * Get the names of the cost functions
   */
  public String[] getCostFunctionNames() {
    if (costFunctions == null) return null;
    String[] ret = new String[costFunctions.length];
    for (int i = 0; i < costFunctions.length; i++) {
      CostFunction c = costFunctions[i];
      ret[i] = c.getClass().getSimpleName();
    }

    return ret;
  }

  /**
   * This is the main cost function.  It will compute a cost associated with a proposed cluster
   * state.  All different costs will be combined with their multipliers to produce a double cost.
   *
   * @param cluster The state of the cluster
   * @param previousCost the previous cost. This is used as an early out.
   * @return a double of a cost associated with the proposed cluster state.  This cost is an
   *         aggregate of all individual cost functions.
   */
  protected double computeCost(Cluster cluster, double previousCost) {
    double total = 0;

    for (int i = 0; i < costFunctions.length; i++) {
      CostFunction c = costFunctions[i];
      this.tempFunctionCosts[i] = 0.0;

      if (c.getMultiplier() <= 0) {
        continue;
      }

      Float multiplier = c.getMultiplier();
      Double cost = c.cost();

      this.tempFunctionCosts[i] = multiplier*cost;
      total += this.tempFunctionCosts[i];

      if (total > previousCost) {
        break;
      }
    }

    return total;
  }

  /** Generates a candidate action to be applied to the cluster for cost function search */
  abstract static class CandidateGenerator {
    abstract Cluster.Action generate(Cluster cluster);

    /**
     * From a list of regions pick a random one. Null can be returned which
     * {@link StochasticLoadBalancer#balanceCluster(Map)} recognize as signal to try a region move
     * rather than swap.
     *
     * @param cluster        The state of the cluster
     * @param server         index of the server
     * @param chanceOfNoSwap Chance that this will decide to try a move rather
     *                       than a swap.
     * @return a random {@link HRegionInfo} or null if an asymmetrical move is
     *         suggested.
     */
    protected int pickRandomRegion(Cluster cluster, int server, double chanceOfNoSwap) {
      // Check to see if this is just a move.
      if (cluster.regionsPerServer[server].length == 0 || RANDOM.nextFloat() < chanceOfNoSwap) {
        // signal a move only.
        return -1;
      }
      int rand = RANDOM.nextInt(cluster.regionsPerServer[server].length);
      return cluster.regionsPerServer[server][rand];

    }
    protected int pickRandomServer(Cluster cluster) {
      if (cluster.numServers < 1) {
        return -1;
      }

      return RANDOM.nextInt(cluster.numServers);
    }

    protected int pickRandomRack(Cluster cluster) {
      if (cluster.numRacks < 1) {
        return -1;
      }

      return RANDOM.nextInt(cluster.numRacks);
    }

    protected int pickOtherRandomServer(Cluster cluster, int serverIndex) {
      if (cluster.numServers < 2) {
        return -1;
      }
      while (true) {
        int otherServerIndex = pickRandomServer(cluster);
        if (otherServerIndex != serverIndex) {
          return otherServerIndex;
        }
      }
    }

    protected int pickOtherRandomRack(Cluster cluster, int rackIndex) {
      if (cluster.numRacks < 2) {
        return -1;
      }
      while (true) {
        int otherRackIndex = pickRandomRack(cluster);
        if (otherRackIndex != rackIndex) {
          return otherRackIndex;
        }
      }
    }

    protected Cluster.Action pickRandomRegions(Cluster cluster,
                                                       int thisServer,
                                                       int otherServer) {
      if (thisServer < 0 || otherServer < 0) {
        return Cluster.NullAction;
      }

      // Decide who is most likely to need another region
      int thisRegionCount = cluster.getNumRegions(thisServer);
      int otherRegionCount = cluster.getNumRegions(otherServer);

      // Assign the chance based upon the above
      double thisChance = (thisRegionCount > otherRegionCount) ? 0 : 0.5;
      double otherChance = (thisRegionCount <= otherRegionCount) ? 0 : 0.5;

      int thisRegion = pickRandomRegion(cluster, thisServer, thisChance);
      int otherRegion = pickRandomRegion(cluster, otherServer, otherChance);

      return getAction(thisServer, thisRegion, otherServer, otherRegion);
    }

    protected Cluster.Action getAction(int fromServer, int fromRegion,
        int toServer, int toRegion) {
      if (fromServer < 0 || toServer < 0) {
        return Cluster.NullAction;
      }
      if (fromRegion > 0 && toRegion > 0) {
        return new Cluster.SwapRegionsAction(fromServer, fromRegion,
          toServer, toRegion);
      } else if (fromRegion > 0) {
        return new Cluster.MoveRegionAction(fromRegion, fromServer, toServer);
      } else if (toRegion > 0) {
        return new Cluster.MoveRegionAction(toRegion, toServer, fromServer);
      } else {
        return Cluster.NullAction;
      }
    }
  }

  static class RandomCandidateGenerator extends CandidateGenerator {

    @Override
    Cluster.Action generate(Cluster cluster) {

      int thisServer = pickRandomServer(cluster);

      // Pick the other server
      int otherServer = pickOtherRandomServer(cluster, thisServer);

      return pickRandomRegions(cluster, thisServer, otherServer);
    }
  }

  static class LoadCandidateGenerator extends CandidateGenerator {

    @Override
    Cluster.Action generate(Cluster cluster) {
      cluster.sortServersByRegionCount();
      int thisServer = pickMostLoadedServer(cluster, -1);
      int otherServer = pickLeastLoadedServer(cluster, thisServer);

      return pickRandomRegions(cluster, thisServer, otherServer);
    }

    private int pickLeastLoadedServer(final Cluster cluster, int thisServer) {
      Integer[] servers = cluster.serverIndicesSortedByRegionCount;

      int index = 0;
      while (servers[index] == null || servers[index] == thisServer) {
        index++;
        if (index == servers.length) {
          return -1;
        }
      }
      return servers[index];
    }

    private int pickMostLoadedServer(final Cluster cluster, int thisServer) {
      Integer[] servers = cluster.serverIndicesSortedByRegionCount;

      int index = servers.length - 1;
      while (servers[index] == null || servers[index] == thisServer) {
        index--;
        if (index < 0) {
          return -1;
        }
      }
      return servers[index];
    }
  }

  static class LocalityBasedCandidateGenerator extends CandidateGenerator {

    private MasterServices masterServices;

    LocalityBasedCandidateGenerator(MasterServices masterServices) {
      this.masterServices = masterServices;
    }

    @Override
    Cluster.Action generate(Cluster cluster) {
      if (this.masterServices == null) {
        int thisServer = pickRandomServer(cluster);
        // Pick the other server
        int otherServer = pickOtherRandomServer(cluster, thisServer);
        return pickRandomRegions(cluster, thisServer, otherServer);
      }

      int thisServer = pickRandomServer(cluster);
      int thisRegion;
      if (thisServer == -1) {
        LOG.warn("Could not pick lowest locality region server");
        return Cluster.NullAction;
      } else {
      // Pick lowest locality region on this server
        thisRegion = pickLowestLocalityRegionOnServer(cluster, thisServer);
      }

      if (thisRegion == -1) {
        return Cluster.NullAction;
      }

      // Pick the least loaded server with good locality for the region
      int otherServer = cluster.getLeastLoadedTopServerForRegion(thisRegion, thisServer);

      if (otherServer == -1) {
        return Cluster.NullAction;
      }

      // Let the candidate region be moved to its highest locality server.
      int otherRegion = -1;

      return getAction(thisServer, thisRegion, otherServer, otherRegion);
    }

    private int pickLowestLocalityServer(Cluster cluster) {
      return cluster.getLowestLocalityRegionServer();
    }

    private int pickLowestLocalityRegionOnServer(Cluster cluster, int server) {
      return cluster.getLowestLocalityRegionOnServer(server);
    }

    void setServices(MasterServices services) {
      this.masterServices = services;
    }
  }

  /**
   * Generates candidates which moves the replicas out of the region server for
   * co-hosted region replicas
   */
  static class RegionReplicaCandidateGenerator extends CandidateGenerator {

    RandomCandidateGenerator randomGenerator = new RandomCandidateGenerator();

    /**
     * Randomly select one regionIndex out of all region replicas co-hosted in the same group
     * (a group is a server, host or rack)
     * @param primariesOfRegionsPerGroup either Cluster.primariesOfRegionsPerServer,
     * primariesOfRegionsPerHost or primariesOfRegionsPerRack
     * @param regionsPerGroup either Cluster.regionsPerServer, regionsPerHost or regionsPerRack
     * @param regionIndexToPrimaryIndex Cluster.regionsIndexToPrimaryIndex
     * @return a regionIndex for the selected primary or -1 if there is no co-locating
     */
    int selectCoHostedRegionPerGroup(int[] primariesOfRegionsPerGroup, int[] regionsPerGroup
        , int[] regionIndexToPrimaryIndex) {
      int currentPrimary = -1;
      int currentPrimaryIndex = -1;
      int selectedPrimaryIndex = -1;
      double currentLargestRandom = -1;
      // primariesOfRegionsPerGroup is a sorted array. Since it contains the primary region
      // ids for the regions hosted in server, a consecutive repetition means that replicas
      // are co-hosted
      for (int j = 0; j <= primariesOfRegionsPerGroup.length; j++) {
        int primary = j < primariesOfRegionsPerGroup.length
            ? primariesOfRegionsPerGroup[j] : -1;
        if (primary != currentPrimary) { // check for whether we see a new primary
          int numReplicas = j - currentPrimaryIndex;
          if (numReplicas > 1) { // means consecutive primaries, indicating co-location
            // decide to select this primary region id or not
            double currentRandom = RANDOM.nextDouble();
            // we don't know how many region replicas are co-hosted, we will randomly select one
            // using reservoir sampling (http://gregable.com/2007/10/reservoir-sampling.html)
            if (currentRandom > currentLargestRandom) {
              selectedPrimaryIndex = currentPrimary;
              currentLargestRandom = currentRandom;
            }
          }
          currentPrimary = primary;
          currentPrimaryIndex = j;
        }
      }

      // we have found the primary id for the region to move. Now find the actual regionIndex
      // with the given primary, prefer to move the secondary region.
      for (int j = 0; j < regionsPerGroup.length; j++) {
        int regionIndex = regionsPerGroup[j];
        if (selectedPrimaryIndex == regionIndexToPrimaryIndex[regionIndex]) {
          // always move the secondary, not the primary
          if (selectedPrimaryIndex != regionIndex) {
            return regionIndex;
          }
        }
      }
      return -1;
    }

    @Override
    Cluster.Action generate(Cluster cluster) {
      int serverIndex = pickRandomServer(cluster);
      if (cluster.numServers <= 1 || serverIndex == -1) {
        return Cluster.NullAction;
      }

      int regionIndex = selectCoHostedRegionPerGroup(
        cluster.primariesOfRegionsPerServer[serverIndex],
        cluster.regionsPerServer[serverIndex],
        cluster.regionIndexToPrimaryIndex);

      // if there are no pairs of region replicas co-hosted, default to random generator
      if (regionIndex == -1) {
        // default to randompicker
        return randomGenerator.generate(cluster);
      }

      int toServerIndex = pickOtherRandomServer(cluster, serverIndex);
      int toRegionIndex = pickRandomRegion(cluster, toServerIndex, 0.9f);
      return getAction(serverIndex, regionIndex, toServerIndex, toRegionIndex);
    }
  }

  /**
   * Generates candidates which moves the replicas out of the rack for
   * co-hosted region replicas in the same rack
   */
  static class RegionReplicaRackCandidateGenerator extends RegionReplicaCandidateGenerator {
    @Override
    Cluster.Action generate(Cluster cluster) {
      int rackIndex = pickRandomRack(cluster);
      if (cluster.numRacks <= 1 || rackIndex == -1) {
        return super.generate(cluster);
      }

      int regionIndex = selectCoHostedRegionPerGroup(
        cluster.primariesOfRegionsPerRack[rackIndex],
        cluster.regionsPerRack[rackIndex],
        cluster.regionIndexToPrimaryIndex);

      // if there are no pairs of region replicas co-hosted, default to random generator
      if (regionIndex == -1) {
        // default to randompicker
        return randomGenerator.generate(cluster);
      }

      int serverIndex = cluster.regionIndexToServerIndex[regionIndex];
      int toRackIndex = pickOtherRandomRack(cluster, rackIndex);

      int rand = RANDOM.nextInt(cluster.serversPerRack[toRackIndex].length);
      int toServerIndex = cluster.serversPerRack[toRackIndex][rand];
      int toRegionIndex = pickRandomRegion(cluster, toServerIndex, 0.9f);
      return getAction(serverIndex, regionIndex, toServerIndex, toRegionIndex);
    }
  }

  /**
   * Generates candidate actions to minimize the TableSkew cost function.
   *
   * For efficiency reasons, the cluster must be passed in when this generator is
   * constructed. Every move generated is applied to the cost function
   * (i.e. it is assumed that every action we generate is applied to the cluster).
   * This means we can adjust our cost incrementally for the cluster, rather than
   * recomputing at each iteration.
   */
  static class TableSkewCandidateGenerator extends CandidateGenerator {

    // Mapping of table -> true iff too many servers in the cluster store at least
    // cluster.maxRegionsIfEvenlydistributed(table)
    boolean[] tablesWithEnoughServersWithMaxRegions = null;

    @Override
    Action generate(Cluster cluster) {
      if (tablesWithEnoughServersWithMaxRegions == null || tablesWithEnoughServersWithMaxRegions.length != cluster.numTables) {
        tablesWithEnoughServersWithMaxRegions = new boolean[cluster.numTables];
      }
      if (cluster.hasUnevenRegionDistribution()) {
        Pair<Integer, Integer> leastAndMostLoadedServers = cluster.findLeastAndMostLoadedServers();
        return moveFromTableWithEnoughRegions(cluster, leastAndMostLoadedServers.getSecond(), leastAndMostLoadedServers.getFirst());
      } else {
        Optional<TableAndServer> tableServer = findSkewedTableServer(cluster);
        if (!tableServer.isPresent()) {
          return Cluster.NullAction;
        }
        return findBestActionForTableServer(cluster, tableServer.get());
      }
    }

    /**
     * Returns a move fromServer -> toServer such that after the move fromServer will still have at least
     * the min # regions in terms of table skew calculation
     */
    private Action moveFromTableWithEnoughRegions(Cluster cluster, int fromServer, int toServer) {
      for (int table : getShuffledRangeOfInts(0, cluster.numTables)) {
        int min = cluster.minRegionsIfEvenlyDistributed(table);
        if (cluster.numRegionsPerServerPerTable[fromServer][table] > min) {
          return getAction(fromServer, pickRandomRegionFromTableOnServer(cluster, fromServer, table), toServer, -1);
        }
      }
      return Cluster.NullAction;
    }

    /**
     * Picks a random subset of tables, then for each table T checks across cluster and returns first
     * server (if any) which holds too many regions from T. Returns Optional.absent() if no servers
     * are found that hold too many regions.
     */
    private Optional<TableAndServer> findSkewedTableServer(Cluster cluster) {
      Optional<TableAndServer> tableServer = Optional.absent();
      List<Integer> servers = getShuffledRangeOfInts(0, cluster.numServers);
      Iterator<Integer> tableIter = getShuffledRangeOfInts(0, cluster.numTables).iterator();
      while (tableIter.hasNext() && !tableServer.isPresent()) {
        int table = tableIter.next();
        int maxRegions = cluster.maxRegionsIfEvenlyDistributed(table);
        int numShouldHaveMaxRegions = cluster.numServersWithMaxRegionsIfEvenlyDistributed(table);
        int numWithMaxRegions = 0;
        for (int server : servers) {
          int numRegions = cluster.numRegionsPerServerPerTable[server][table];
          // if more than max, server clearly has too many regions
          if (numRegions > maxRegions) {
            tableServer = Optional.of(new TableAndServer(table, server));
            break;
          }
          // if equal to max, check to see if we are within acceptable limit
          if (numRegions == maxRegions) {
            numWithMaxRegions++;
          }
        }

        tablesWithEnoughServersWithMaxRegions[table] = numWithMaxRegions >= numShouldHaveMaxRegions;
        // If we have found a table with more than max, we are done
        if (tableServer.isPresent()) {
          break;
        }

        // Otherwise, check to see if there are too many servers with maxRegions
        if (numWithMaxRegions > numShouldHaveMaxRegions) {
          for (int server : servers) {
            int numRegions = cluster.numRegionsPerServerPerTable[server][table];
            if (numRegions == maxRegions) {
              tableServer = Optional.of(new TableAndServer(table, server));
              break;
            }
          }
        }
      }

      return tableServer;
    }

    /**
     * Returns an list of integers that stores [upper - lower] unique integers in random order
     * s.t. for each integer i lower <= i < upper
     */
    private List<Integer> getShuffledRangeOfInts(int lower, int upper) {
      Preconditions.checkArgument(lower < upper);
      ArrayList<Integer> arr = new ArrayList<Integer>(upper - lower);
      for (int i = lower; i < upper; i++) {
        arr.add(i);
      }
      Collections.shuffle(arr);
      return arr;
    }

    /**
     * Pick a random region from the specified server and table. Returns -1 if no regions from
     * the given table lie on the given server
     */
    protected int pickRandomRegionFromTableOnServer(Cluster cluster, int server, int table) {
      if (server < 0 || table < 0) {
        return -1;
      }
      List<Integer> regionsFromTable = new ArrayList<>();
      for (int region : cluster.regionsPerServer[server]) {
        if (cluster.regionIndexToTableIndex[region] == table) {
          regionsFromTable.add(region);
        }
      }
      return regionsFromTable.get(RANDOM.nextInt(regionsFromTable.size()));
    }

    /**
     * Returns servers in the cluster that store fewer than k regions for the given table (sorted by
     * servers with the fewest regions from givenTable first)
     */
    public List<Integer> getServersWithFewerThanKRegionsFromTable(final Cluster cluster, final int givenTable, int k) {
      List<Integer> serversWithFewerThanK = new ArrayList<>();
      for (int server = 0; server < cluster.numServers; server++) {
        if (cluster.numRegionsPerServerPerTable[server][givenTable] < k) {
          serversWithFewerThanK.add(server);
        }
      }
      Collections.sort(serversWithFewerThanK, new Comparator<Integer>() {
        @Override
        public int compare(Integer o1, Integer o2) {
          return cluster.numRegionsPerServerPerTable[o1.intValue()][givenTable] - cluster.numRegionsPerServerPerTable[o2.intValue()][givenTable];
        }
      });
      return serversWithFewerThanK;
    }

    /**
     * Given a table T for which server S stores too many regions, attempts to find a
     * SWAP operation that will better balance the cluster
     */
    public Action findBestActionForTableServer(Cluster cluster, TableAndServer tableServer) {
      int fromTable = tableServer.getTable();
      int fromServer = tableServer.getServer();

      int minNumRegions = cluster.minRegionsIfEvenlyDistributed(fromTable);
      int maxNumRegions = cluster.maxRegionsIfEvenlyDistributed(fromTable);
      List<Integer> servers;
      if (tablesWithEnoughServersWithMaxRegions[fromTable]) {
        servers = getServersWithFewerThanKRegionsFromTable(cluster, fromTable, minNumRegions);
      } else {
        servers = getServersWithFewerThanKRegionsFromTable(cluster, fromTable, maxNumRegions);
      }

      if (servers.isEmpty()) {
        return Cluster.NullAction;
      }

      Optional<Action> swap = trySwap(cluster, fromServer, fromTable, servers);
      if (swap.isPresent()) {
        return swap.get();
      }

      // If we cannot perform a swap, we should do nothing
      return Cluster.NullAction;
    }

    /**
     * Given server1, table1, we try to find server2 and table2 such that
     * at least 3 of the following 4 criteria are met
     *
     * 1) server1 has too many regions of table1
     * 2) server1 has too few regions of table2
     * 3) server2 has too many regions of table2
     * 4) server2 has too few regions of table1
     *
     * We consider N regions from table T
     *    too few if: N < cluster.minRegionsIfEvenlyDistributed(T)
     *    too many if: N > cluster.maxRegionsIfEvenlyDistributed(T)
     *
     * Because (1) and (4) are true apriori, we only need to check for (2) and (3).
     *
     * If 3 of the 4 criteria are met, we return a swap operation between
     * randomly selected regions from table1 on server1 and from table2 on server2.
     *
     * Optional.absent() is returned if we could not find such a SWAP.
     */
    private Optional<Action> trySwap(Cluster cluster, int server1, int table1, List<Integer> candidateServers) {
      // Because conditions (1) and (4) are true apriori, we only need to meet one of conditions (2) or (3)
      List<Integer> tables = getShuffledRangeOfInts(0, cluster.numTables);
      for (int table2 : tables) {
        int minRegions = cluster.minRegionsIfEvenlyDistributed(table2);
        int maxRegions = cluster.maxRegionsIfEvenlyDistributed(table2);
        for (int server2 : candidateServers) {
          int numRegions1 = cluster.numRegionsPerServerPerTable[server1][table2];
          int numRegions2 = cluster.numRegionsPerServerPerTable[server2][table2];
          if (numRegions2 == 0) {
            continue;
          }
          if ((numRegions1 < minRegions || numRegions2 > maxRegions) ||
              (minRegions != maxRegions && numRegions1 == minRegions && numRegions2 == maxRegions)) {
            int region1 = pickRandomRegionFromTableOnServer(cluster, server1, table1);
            int region2 = pickRandomRegionFromTableOnServer(cluster, server2, table2);
            return Optional.of(getAction(server1, region1, server2, region2));
          }
        }
      }
      return Optional.absent();
    }
  }

  /**
   * Base class of StochasticLoadBalancer's Cost Functions.
   */
  abstract static class CostFunction {

    private float multiplier = 0;

    protected Cluster cluster;

    CostFunction(Configuration c) {
    }

    boolean isNeeded() {
      return true;
    }
    float getMultiplier() {
      return multiplier;
    }

    void setMultiplier(float m) {
      this.multiplier = m;
    }

    /** Called once per LB invocation to give the cost function
     * to initialize it's state, and perform any costly calculation.
     */
    void init(Cluster cluster) {
      this.cluster = cluster;
    }

    /** Called once per cluster Action to give the cost function
     * an opportunity to update it's state. postAction() is always
     * called at least once before cost() is called with the cluster
     * that this action is performed on. */
    void postAction(Action action) {
      switch (action.type) {
      case NULL: break;
      case ASSIGN_REGION:
        AssignRegionAction ar = (AssignRegionAction) action;
        regionMoved(ar.region, -1, ar.server);
        break;
      case MOVE_REGION:
        MoveRegionAction mra = (MoveRegionAction) action;
        regionMoved(mra.region, mra.fromServer, mra.toServer);
        break;
      case SWAP_REGIONS:
        SwapRegionsAction a = (SwapRegionsAction) action;
        regionSwapped(a.fromRegion, a.fromServer, a.toRegion, a.toServer);
        break;
      default:
        throw new RuntimeException("Uknown action:" + action.type);
      }
    }

    protected void regionMoved(int region, int oldServer, int newServer) {
    }

    protected void regionSwapped(int region1, int server1, int region2, int server2) {
      regionMoved(region1, server1, server2);
      regionMoved(region2, server2, server1);
    }

    abstract double cost();

    /**
     * Function to compute a scaled cost using {@link DescriptiveStatistics}. It
     * assumes that this is a zero sum set of costs.  It assumes that the worst case
     * possible is all of the elements in one region server and the rest having 0.
     *
     * @param stats the costs
     * @return a scaled set of costs.
     */
    protected double costFromArray(double[] stats) {
      double totalCost = 0;
      double total = getSum(stats);

      double count = stats.length;
      double mean = total/count;

      // Compute max as if all region servers had 0 and one had the sum of all costs.  This must be
      // a zero sum cost for this to make sense.
      double max = ((count - 1) * mean) + (total - mean);

      // It's possible that there aren't enough regions to go around
      double min;
      if (count > total) {
        min = ((count - total) * mean) + ((1 - mean) * total);
      } else {
        // Some will have 1 more than everything else.
        int numHigh = (int) (total - (Math.floor(mean) * count));
        int numLow = (int) (count - numHigh);

        min = (numHigh * (Math.ceil(mean) - mean)) + (numLow * (mean - Math.floor(mean)));

      }
      min = Math.max(0, min);
      for (int i=0; i<stats.length; i++) {
        double n = stats[i];
        double diff = Math.abs(mean - n);
        totalCost += diff;
      }

      double scaled =  scale(min, max, totalCost);
      return scaled;
    }

    private double getSum(double[] stats) {
      double total = 0;
      for(double s:stats) {
        total += s;
      }
      return total;
    }

    /**
     * Scale the value between 0 and 1.
     *
     * @param min   Min value
     * @param max   The Max value
     * @param value The value to be scaled.
     * @return The scaled value.
     */
    protected double scale(double min, double max, double value) {
      if (max <= min || value <= min) {
        return 0;
      }
      if ((max - min) == 0) return 0;

      return Math.max(0d, Math.min(1d, (value - min) / (max - min)));
    }
  }

  /**
   * Given the starting state of the regions and a potential ending state
   * compute cost based upon the number of regions that have moved.
   */
  static class MoveCostFunction extends CostFunction {
    private static final String MOVE_COST_KEY = "hbase.master.balancer.stochastic.moveCost";
    private static final String MAX_MOVES_PERCENT_KEY =
        "hbase.master.balancer.stochastic.maxMovePercent";
    private static final float DEFAULT_MOVE_COST = 7;
    private static final int DEFAULT_MAX_MOVES = 600;
    private static final float DEFAULT_MAX_MOVE_PERCENT = 0.25f;

    private final float maxMovesPercent;

    MoveCostFunction(Configuration conf) {
      super(conf);

      // Move cost multiplier should be the same cost or higher than the rest of the costs to ensure
      // that large benefits are need to overcome the cost of a move.
      this.setMultiplier(conf.getFloat(MOVE_COST_KEY, DEFAULT_MOVE_COST));
      // What percent of the number of regions a single run of the balancer can move.
      maxMovesPercent = conf.getFloat(MAX_MOVES_PERCENT_KEY, DEFAULT_MAX_MOVE_PERCENT);
    }

    @Override
    double cost() {
      // Try and size the max number of Moves, but always be prepared to move some.
      int maxMoves = Math.max((int) (cluster.numRegions * maxMovesPercent),
          DEFAULT_MAX_MOVES);

      double moveCost = cluster.numMovedRegions;

      // Don't let this single balance move more than the max moves.
      // This allows better scaling to accurately represent the actual cost of a move.
      if (moveCost > maxMoves) {
        return 1000000;   // return a number much greater than any of the other cost
      }

      return scale(0, Math.min(cluster.numRegions, maxMoves), moveCost);
    }
  }

  /**
   * Compute the cost of a potential cluster state from skew in number of
   * regions on a cluster.
   */
  static class RegionCountSkewCostFunction extends CostFunction {
    private static final String REGION_COUNT_SKEW_COST_KEY =
        "hbase.master.balancer.stochastic.regionCountCost";
    private static final float DEFAULT_REGION_COUNT_SKEW_COST = 500;

    private double[] stats = null;

    RegionCountSkewCostFunction(Configuration conf) {
      super(conf);
      // Load multiplier should be the greatest as it is the most general way to balance data.
      this.setMultiplier(conf.getFloat(REGION_COUNT_SKEW_COST_KEY, DEFAULT_REGION_COUNT_SKEW_COST));
    }

    @Override
    double cost() {
      if (stats == null || stats.length != cluster.numServers) {
        stats = new double[cluster.numServers];
      }

      for (int i =0; i < cluster.numServers; i++) {
        stats[i] = cluster.regionsPerServer[i].length;
      }

      return costFromArray(stats);
    }
  }

  /**
   * Compute the cost of a potential cluster state from skew in number of
   * primary regions on a cluster.
   */
  static class PrimaryRegionCountSkewCostFunction extends CostFunction {
    private static final String PRIMARY_REGION_COUNT_SKEW_COST_KEY =
        "hbase.master.balancer.stochastic.primaryRegionCountCost";
    private static final float DEFAULT_PRIMARY_REGION_COUNT_SKEW_COST = 500;

    private double[] stats = null;

    PrimaryRegionCountSkewCostFunction(Configuration conf) {
      super(conf);
      // Load multiplier should be the greatest as primary regions serve majority of reads/writes.
      this.setMultiplier(conf.getFloat(PRIMARY_REGION_COUNT_SKEW_COST_KEY,
        DEFAULT_PRIMARY_REGION_COUNT_SKEW_COST));
    }

    @Override
    double cost() {
      if (!cluster.hasRegionReplicas) {
        return 0;
      }
      if (stats == null || stats.length != cluster.numServers) {
        stats = new double[cluster.numServers];
      }

      for (int i =0; i < cluster.numServers; i++) {
        stats[i] = 0;
        for (int regionIdx : cluster.regionsPerServer[i]) {
          if (regionIdx == cluster.regionIndexToPrimaryIndex[regionIdx]) {
            stats[i] ++;
          }
        }
      }

      return costFromArray(stats);
    }
  }

  /**
   * Compute the cost of a potential cluster configuration based upon how evenly
   * distributed tables are.
   */
  static class TableSkewCostFunction extends CostFunction {

    private static final String TABLE_SKEW_COST_KEY =
        "hbase.master.balancer.stochastic.tableSkewCost";
    private static final float DEFAULT_TABLE_SKEW_COST = 35;

    /**
     * Ranges from 0.0 to 1.0 and is the proportion of how much the most skewed table
     * (as opposed to the average skew across all tables) should affect TableSkew cost
     */
    private static final String MAX_TABLE_SKEW_WEIGHT_KEY =
        "hbase.master.balancer.stochastic.maxTableSkewWeight";
    private float DEFAULT_MAX_TABLE_SKEW_WEIGHT = 0.0f;

    private final float maxTableSkewWeight;
    private final float avgTableSkewWeight;

    // Number of moves for each table required to bring the cluster to a perfectly balanced
    // state (i.e. as if you had round-robin-ed regions across cluster)
    private int[] numMovesPerTable;

    TableSkewCostFunction(Configuration conf) {
      super(conf);
      this.setMultiplier(conf.getFloat(TABLE_SKEW_COST_KEY, DEFAULT_TABLE_SKEW_COST));
      maxTableSkewWeight = conf.getFloat(MAX_TABLE_SKEW_WEIGHT_KEY, DEFAULT_MAX_TABLE_SKEW_WEIGHT);
      Preconditions.checkArgument(0.0 <= maxTableSkewWeight && maxTableSkewWeight <= 1.0);
      avgTableSkewWeight = 1 - maxTableSkewWeight;
    }

    /**
     * Computes cost by:
     *
     * 1) Computing a skew score for each table (based on the number of regions
     * from that table that would have to be moved to reach an evenly balanced state)
     *
     * 2) Taking a weighted average of the highest skew score with the average skew score
     *
     * 3) Square rooting that value to more evenly distribute the values between 0-1
     * (since we have observed they are generally very small).
     *
     * @return the table skew cost for the cluster
     */
    @Override
    double cost() {
      double[] skewPerTable = computeSkewPerTable();
      if (skewPerTable.length == 0) {
        return 0;
      }
      double maxTableSkew = max(skewPerTable);
      double avgTableSkew = average(skewPerTable);

      return Math.sqrt(maxTableSkewWeight * maxTableSkew + avgTableSkewWeight * avgTableSkew);
    }

    @Override
    void init(Cluster cluster) {
      super.init(cluster);
      numMovesPerTable = computeNumMovesPerTable();
    }

    /**
     * Adjusts computed number of moves after two regions have been swapped
     */
    @Override
    protected void regionSwapped(int region1, int server1, int region2, int server2) {
      // If different tables, simply perform two moves
      if (cluster.regionIndexToTableIndex[region1] != cluster.regionIndexToTableIndex[region2]) {
        super.regionSwapped(region1, server1, region2, server2);
        return;
      }
      // If same table, do nothing
    }

    /**
     * Adjusts computed number of moves per table after a region has been moved
     */
    @Override
    protected void regionMoved(int region, int oldServer, int newServer) {
      int table = cluster.regionIndexToTableIndex[region];
      numMovesPerTable[table] = computeNumMovesForTable(table);
    }

    /**
     * Returns a mapping of table -> numMoves, where numMoves is the number of regions required to bring
     * each table to a fully balanced state (i.e. as if its regions had been round-robin-ed across the cluster).
     */
    private int[] computeNumMovesPerTable() {
      // Determine # region moves required for each table to have regions perfectly distributed across cluster
      int[] numMovesPerTable = new int[cluster.numTables];
      for (int table = 0; table < cluster.numTables; table++) {
        numMovesPerTable[table] = computeNumMovesForTable(table);
      }
      return numMovesPerTable;
    }

    /**
     * Computes the number of moves required across all servers to bring the given table to a balanced state
     * (i.e. as if its regions had been round-robin-ed across the cluster). We only consider moves as # of regions
     * that need to be sent, not received, so that we do not double count region moves.
     */
    private int computeNumMovesForTable(int table) {
      int numMinRegions = cluster.minRegionsIfEvenlyDistributed(table);
      int numMaxRegions = cluster.maxRegionsIfEvenlyDistributed(table);
      int numMaxServersRemaining = cluster.numServersWithMaxRegionsIfEvenlyDistributed(table);
      int numMoves = 0;

      for (int server = 0; server < cluster.numServers; server++) {
        int numRegions = cluster.numRegionsPerServerPerTable[server][table];
        if (numRegions >= numMaxRegions && numMaxServersRemaining > 0) {
          numMoves += numRegions - numMaxRegions;
          numMaxServersRemaining--;
        } else if (numRegions > numMinRegions) {
          numMoves += numRegions - numMinRegions;
        }
      }
      return numMoves;
    }

    /**
     * Returns mapping of tableIndex -> tableSkewScore, where tableSkewScore is a double between 0 to 1 with
     * 0 indicating no table skew (i.e. perfect distribution of regions among servers), and 1 representing
     * pathological table skew (i.e. all of a servers regions belonging to one table).
     */
    private double[] computeSkewPerTable() {
      if (numMovesPerTable == null) {
        numMovesPerTable = computeNumMovesPerTable();
      }
      double[] scaledSkewPerTable = new double[numMovesPerTable.length];
      for (int table = 0; table < numMovesPerTable.length; table++) {
        int numTotalRegions = cluster.numRegionsPerTable[table];
        int maxRegions = cluster.maxRegionsIfEvenlyDistributed(table);
        int pathologicalNumMoves = numTotalRegions - maxRegions;
        scaledSkewPerTable[table] = pathologicalNumMoves == 0 ? 0 : (double) numMovesPerTable[table] / pathologicalNumMoves;
      }
      return scaledSkewPerTable;
    }

    /**
     * Returns the max of the values in the passed array
     */
    private double max(double[] arr) {
      double max = arr[0];
      for (double d : arr) {
        if (d > max) {
          max = d;
        }
      }
      return max;
    }

    /**
     * Returns the average of the values in the passed array
     */
    private double average(double[] arr) {
      double sum = 0;
      for (double d : arr) {
        sum += d;
      }
      return sum / arr.length;
    }
  }

  /**
   * Compute the cost of a potential cluster configuration based upon how evenly
   * distributed tables are.
   *
   * @deprecated replaced by TableSkewCostFunction
   * This function only considers the maximum # of regions of each table stored
   * on any one server. This, however, neglects a number of cases. Consider the case
   * where N servers store 1 more region than as if the regions had been round robin-ed
   * across the cluster, but then K servers stored 0 regions of the table. The maximum
   * # regions stored would not properly reflect the table-skew of the cluster.
   *
   * Furthermore, this relies upon the cluster.numMaxRegionsPerTable field, which is not
   * properly updated. The values per table only increase as the cluster shifts (i.e.
   * as new maxima are found), but they do not go down when the maximum skew decreases
   * for a particular table.
   */
  @Deprecated
  static class OldTableSkewCostFunction extends CostFunction {

    private static final String TABLE_SKEW_COST_KEY =
        "hbase.master.balancer.stochastic.tableSkewCost";
    private static final float DEFAULT_TABLE_SKEW_COST = 35;

    OldTableSkewCostFunction(Configuration conf) {
      super(conf);
      this.setMultiplier(conf.getFloat(TABLE_SKEW_COST_KEY, DEFAULT_TABLE_SKEW_COST));
    }

    @Override
    double cost() {
      double max = cluster.numRegions;
      double min = ((double) cluster.numRegions) / cluster.numServers;
      double value = 0;

      for (int i = 0; i < cluster.numMaxRegionsPerTable.length; i++) {
        value += cluster.numMaxRegionsPerTable[i];
      }

      return scale(min, max, value);
    }
  }

  /**
   * Compute a cost of a potential cluster configuration based upon where
   * {@link org.apache.hadoop.hbase.regionserver.StoreFile}s are located.
   */
  static class LocalityCostFunction extends CostFunction {

    private static final String LOCALITY_COST_KEY = "hbase.master.balancer.stochastic.localityCost";
    private static final float DEFAULT_LOCALITY_COST = 25;

    private MasterServices services;

    LocalityCostFunction(Configuration conf, MasterServices srv) {
      super(conf);
      this.setMultiplier(conf.getFloat(LOCALITY_COST_KEY, DEFAULT_LOCALITY_COST));
      this.services = srv;
    }

    void setServices(MasterServices srvc) {
      this.services = srvc;
    }

    @Override
    double cost() {
      double max = 0;
      double cost = 0;

      // If there's no master so there's no way anything else works.
      if (this.services == null) {
        return cost;
      }

      for (int i = 0; i < cluster.regionLocations.length; i++) {
        max += 1;
        int serverIndex = cluster.regionIndexToServerIndex[i];
        int[] regionLocations = cluster.regionLocations[i];

        // If we can't find where the data is getTopBlock returns null.
        // so count that as being the best possible.
        if (regionLocations == null) {
          continue;
        }

        int index = -1;
        for (int j = 0; j < regionLocations.length; j++) {
          if (regionLocations[j] >= 0 && regionLocations[j] == serverIndex) {
            index = j;
            break;
          }
        }

        if (index < 0) {
          cost += 1;
        } else {
          cost += (1 - cluster.getLocalityOfRegion(i, serverIndex));
        }
      }
      return scale(0, max, cost);
    }
  }

  /**
   * Base class the allows writing costs functions from rolling average of some
   * number from RegionLoad.
   */
  abstract static class CostFromRegionLoadFunction extends CostFunction {

    private ClusterStatus clusterStatus = null;
    private Map<String, Deque<BalancerRegionLoad>> loads = null;
    private double[] stats = null;
    CostFromRegionLoadFunction(Configuration conf) {
      super(conf);
    }

    void setClusterStatus(ClusterStatus status) {
      this.clusterStatus = status;
    }

    void setLoads(Map<String, Deque<BalancerRegionLoad>> l) {
      this.loads = l;
    }

    @Override
    double cost() {
      if (clusterStatus == null || loads == null) {
        return 0;
      }

      if (stats == null || stats.length != cluster.numServers) {
        stats = new double[cluster.numServers];
      }

      for (int i =0; i < stats.length; i++) {
        //Cost this server has from RegionLoad
        long cost = 0;

        // for every region on this server get the rl
        for(int regionIndex:cluster.regionsPerServer[i]) {
          Collection<BalancerRegionLoad> regionLoadList =  cluster.regionLoads[regionIndex];

          // Now if we found a region load get the type of cost that was requested.
          if (regionLoadList != null) {
            cost += getRegionLoadCost(regionLoadList);
          }
        }

        // Add the total cost to the stats.
        stats[i] = cost;
      }

      // Now return the scaled cost from data held in the stats object.
      return costFromArray(stats);
    }

    protected double getRegionLoadCost(Collection<BalancerRegionLoad> regionLoadList) {
      double cost = 0;
      for (BalancerRegionLoad rl : regionLoadList) {
        cost += getCostFromRl(rl);
      }
      return cost / regionLoadList.size();
    }

    protected abstract double getCostFromRl(BalancerRegionLoad rl);
  }

  /**
   * Class to be used for the subset of RegionLoad costs that should be treated as rates.
   * We do not compare about the actual rate in requests per second but rather the rate relative
   * to the rest of the regions.
   */
  abstract static class CostFromRegionLoadAsRateFunction extends CostFromRegionLoadFunction {

    CostFromRegionLoadAsRateFunction(Configuration conf) {
      super(conf);
    }

    @Override
    protected double getRegionLoadCost(Collection<BalancerRegionLoad> regionLoadList) {
      double cost = 0;
      double previous = 0;
      boolean isFirst = true;
      for (BalancerRegionLoad rl : regionLoadList) {
        double current = getCostFromRl(rl);
        if (isFirst) {
          isFirst = false;
        } else {
          cost += current - previous;
        }
        previous = current;
      }
      return Math.max(0, cost / (regionLoadList.size() - 1));
    }
  }

  /**
   * Compute the cost of total number of read requests  The more unbalanced the higher the
   * computed cost will be.  This uses a rolling average of regionload.
   */

  static class ReadRequestCostFunction extends CostFromRegionLoadAsRateFunction {

    private static final String READ_REQUEST_COST_KEY =
        "hbase.master.balancer.stochastic.readRequestCost";
    private static final float DEFAULT_READ_REQUEST_COST = 5;

    ReadRequestCostFunction(Configuration conf) {
      super(conf);
      this.setMultiplier(conf.getFloat(READ_REQUEST_COST_KEY, DEFAULT_READ_REQUEST_COST));
    }

    @Override
    protected double getCostFromRl(BalancerRegionLoad rl) {
      return rl.getReadRequestsCount();
    }
  }

  /**
   * Compute the cost of total number of write requests.  The more unbalanced the higher the
   * computed cost will be.  This uses a rolling average of regionload.
   */
  static class WriteRequestCostFunction extends CostFromRegionLoadAsRateFunction {

    private static final String WRITE_REQUEST_COST_KEY =
        "hbase.master.balancer.stochastic.writeRequestCost";
    private static final float DEFAULT_WRITE_REQUEST_COST = 5;

    WriteRequestCostFunction(Configuration conf) {
      super(conf);
      this.setMultiplier(conf.getFloat(WRITE_REQUEST_COST_KEY, DEFAULT_WRITE_REQUEST_COST));
    }

    @Override
    protected double getCostFromRl(BalancerRegionLoad rl) {
      return rl.getWriteRequestsCount();
    }
  }

  /**
   * A cost function for region replicas. We give a very high cost to hosting
   * replicas of the same region in the same host. We do not prevent the case
   * though, since if numReplicas > numRegionServers, we still want to keep the
   * replica open.
   */
  static class RegionReplicaHostCostFunction extends CostFunction {
    private static final String REGION_REPLICA_HOST_COST_KEY =
        "hbase.master.balancer.stochastic.regionReplicaHostCostKey";
    private static final float DEFAULT_REGION_REPLICA_HOST_COST_KEY = 100000;

    long maxCost = 0;
    long[] costsPerGroup; // group is either server, host or rack
    int[][] primariesOfRegionsPerGroup;

    public RegionReplicaHostCostFunction(Configuration conf) {
      super(conf);
      this.setMultiplier(conf.getFloat(REGION_REPLICA_HOST_COST_KEY,
        DEFAULT_REGION_REPLICA_HOST_COST_KEY));
    }

    @Override
    void init(Cluster cluster) {
      super.init(cluster);
      // max cost is the case where every region replica is hosted together regardless of host
      maxCost = cluster.numHosts > 1 ? getMaxCost(cluster) : 0;
      costsPerGroup = new long[cluster.numHosts];
      primariesOfRegionsPerGroup = cluster.multiServersPerHost // either server based or host based
          ? cluster.primariesOfRegionsPerHost
          : cluster.primariesOfRegionsPerServer;
      for (int i = 0 ; i < primariesOfRegionsPerGroup.length; i++) {
        costsPerGroup[i] = costPerGroup(primariesOfRegionsPerGroup[i]);
      }
    }

    long getMaxCost(Cluster cluster) {
      if (!cluster.hasRegionReplicas) {
        return 0; // short circuit
      }
      // max cost is the case where every region replica is hosted together regardless of host
      int[] primariesOfRegions = new int[cluster.numRegions];
      System.arraycopy(cluster.regionIndexToPrimaryIndex, 0, primariesOfRegions, 0,
          cluster.regions.length);

      Arrays.sort(primariesOfRegions);

      // compute numReplicas from the sorted array
      return costPerGroup(primariesOfRegions);
    }

    @Override
    boolean isNeeded() {
      return cluster.hasRegionReplicas;
    }

    @Override
    double cost() {
      if (maxCost <= 0) {
        return 0;
      }

      long totalCost = 0;
      for (int i = 0 ; i < costsPerGroup.length; i++) {
        totalCost += costsPerGroup[i];
      }
      // Still return high cost for single region replicas being cohosted even as cluster scales
      return Math.sqrt(scale(0, maxCost, totalCost));
    }

    /**
     * For each primary region, it computes the total number of replicas in the array (numReplicas)
     * and returns a sum of numReplicas-1 squared. For example, if the server hosts
     * regions a, b, c, d, e, f where a and b are same replicas, and c,d,e are same replicas, it
     * returns (2-1) * (2-1) + (3-1) * (3-1) + (1-1) * (1-1).
     * @param primariesOfRegions a sorted array of primary regions ids for the regions hosted
     * @return a sum of numReplicas-1 squared for each primary region in the group.
     */
    protected long costPerGroup(int[] primariesOfRegions) {
      long cost = 0;
      int currentPrimary = -1;
      int currentPrimaryIndex = -1;
      // primariesOfRegions is a sorted array of primary ids of regions. Replicas of regions
      // sharing the same primary will have consecutive numbers in the array.
      for (int j = 0 ; j <= primariesOfRegions.length; j++) {
        int primary = j < primariesOfRegions.length ? primariesOfRegions[j] : -1;
        if (primary != currentPrimary) { // we see a new primary
          int numReplicas = j - currentPrimaryIndex;
          // square the cost
          if (numReplicas > 1) { // means consecutive primaries, indicating co-location
            cost += (numReplicas - 1) * (numReplicas - 1);
          }
          currentPrimary = primary;
          currentPrimaryIndex = j;
        }
      }

      return cost;
    }

    @Override
    protected void regionMoved(int region, int oldServer, int newServer) {
      if (maxCost <= 0) {
        return; // no need to compute
      }
      if (cluster.multiServersPerHost) {
        int oldHost = cluster.serverIndexToHostIndex[oldServer];
        int newHost = cluster.serverIndexToHostIndex[newServer];
        if (newHost != oldHost) {
          costsPerGroup[oldHost] = costPerGroup(cluster.primariesOfRegionsPerHost[oldHost]);
          costsPerGroup[newHost] = costPerGroup(cluster.primariesOfRegionsPerHost[newHost]);
        }
      } else {
        costsPerGroup[oldServer] = costPerGroup(cluster.primariesOfRegionsPerServer[oldServer]);
        costsPerGroup[newServer] = costPerGroup(cluster.primariesOfRegionsPerServer[newServer]);
      }
    }
  }

  /**
   * A cost function for region replicas for the rack distribution. We give a relatively high
   * cost to hosting replicas of the same region in the same rack. We do not prevent the case
   * though.
   */
  static class RegionReplicaRackCostFunction extends RegionReplicaHostCostFunction {
    private static final String REGION_REPLICA_RACK_COST_KEY =
        "hbase.master.balancer.stochastic.regionReplicaRackCostKey";
    private static final float DEFAULT_REGION_REPLICA_RACK_COST_KEY = 10000;

    public RegionReplicaRackCostFunction(Configuration conf) {
      super(conf);
      this.setMultiplier(conf.getFloat(REGION_REPLICA_RACK_COST_KEY,
        DEFAULT_REGION_REPLICA_RACK_COST_KEY));
    }

    @Override
    void init(Cluster cluster) {
      this.cluster = cluster;
      if (cluster.numRacks <= 1) {
        maxCost = 0;
        return; // disabled for 1 rack
      }
      // max cost is the case where every region replica is hosted together regardless of rack
      maxCost = getMaxCost(cluster);
      costsPerGroup = new long[cluster.numRacks];
      for (int i = 0 ; i < cluster.primariesOfRegionsPerRack.length; i++) {
        costsPerGroup[i] = costPerGroup(cluster.primariesOfRegionsPerRack[i]);
      }
    }

    @Override
    protected void regionMoved(int region, int oldServer, int newServer) {
      if (maxCost <= 0) {
        return; // no need to compute
      }
      int oldRack = cluster.serverIndexToRackIndex[oldServer];
      int newRack = cluster.serverIndexToRackIndex[newServer];
      if (newRack != oldRack) {
        costsPerGroup[oldRack] = costPerGroup(cluster.primariesOfRegionsPerRack[oldRack]);
        costsPerGroup[newRack] = costPerGroup(cluster.primariesOfRegionsPerRack[newRack]);
      }
    }
  }

  /**
   * Compute the cost of total memstore size.  The more unbalanced the higher the
   * computed cost will be.  This uses a rolling average of regionload.
   */
  static class MemstoreSizeCostFunction extends CostFromRegionLoadAsRateFunction {

    private static final String MEMSTORE_SIZE_COST_KEY =
        "hbase.master.balancer.stochastic.memstoreSizeCost";
    private static final float DEFAULT_MEMSTORE_SIZE_COST = 5;

    MemstoreSizeCostFunction(Configuration conf) {
      super(conf);
      this.setMultiplier(conf.getFloat(MEMSTORE_SIZE_COST_KEY, DEFAULT_MEMSTORE_SIZE_COST));
    }

    @Override
    protected double getCostFromRl(BalancerRegionLoad rl) {
      return rl.getMemStoreSizeMB();
    }
  }
  /**
   * Compute the cost of total open storefiles size.  The more unbalanced the higher the
   * computed cost will be.  This uses a rolling average of regionload.
   */
  static class StoreFileCostFunction extends CostFromRegionLoadFunction {

    private static final String STOREFILE_SIZE_COST_KEY =
        "hbase.master.balancer.stochastic.storefileSizeCost";
    private static final float DEFAULT_STOREFILE_SIZE_COST = 5;

    StoreFileCostFunction(Configuration conf) {
      super(conf);
      this.setMultiplier(conf.getFloat(STOREFILE_SIZE_COST_KEY, DEFAULT_STOREFILE_SIZE_COST));
    }

    @Override
    protected double getCostFromRl(BalancerRegionLoad rl) {
      return rl.getStorefileSizeMB();
    }
  }

  /**
   * Data structure that holds table and server indexes
   */
  static class TableAndServer {
    private final int table;
    private final int server;

    public TableAndServer(int table, int server) {
      this.table = table;
      this.server = server;
    }

    public int getTable() {
      return table;
    }

    public int getServer() {
      return server;
    }
  }

  /**
   * A helper function to compose the attribute name from tablename and costfunction name
   */
  public static String composeAttributeName(String tableName, String costFunctionName) {
    return tableName + TABLE_FUNCTION_SEP + costFunctionName;
  }

}
