/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.openwhisk.core.loadBalancer

import akka.actor.ActorRef
import akka.actor.ActorRefFactory

import akka.actor.{Actor, ActorSystem, Props}
import akka.cluster.ClusterEvent._
import akka.cluster.{Cluster, Member, MemberStatus}
import akka.management.scaladsl.AkkaManagement
import akka.management.cluster.bootstrap.ClusterBootstrap
import pureconfig._
import pureconfig.generic.auto._
import org.apache.openwhisk.common._
import org.apache.openwhisk.core.WhiskConfig._
import org.apache.openwhisk.core.connector._
import org.apache.openwhisk.core.entity._
import org.apache.openwhisk.core.entity.size.SizeLong
import org.apache.openwhisk.core.controller.Controller
import org.apache.openwhisk.core.{ConfigKeys, WhiskConfig}
import org.apache.openwhisk.spi.SpiLoader

import scala.concurrent.Future
import scala.util.Random


class LeastLoadBalancer(
  config: WhiskConfig,
  controllerInstance: ControllerInstanceId,
  feedFactory: FeedFactory,
  invokerPoolFactory: InvokerPoolFactory,
  implicit val messagingProvider: MessagingProvider = SpiLoader.get[MessagingProvider])(
  implicit actorSystem: ActorSystem,
  logging: Logging)
    extends CommonLoadBalancer(config, feedFactory, controllerInstance) {

  /** Build a cluster of all loadbalancers */
  private val cluster: Option[Cluster] = if (loadConfigOrThrow[ClusterConfig](ConfigKeys.cluster).useClusterBootstrap) {
    AkkaManagement(actorSystem).start()
    ClusterBootstrap(actorSystem).start()
    Some(Cluster(actorSystem))
  } else if (loadConfigOrThrow[Seq[String]]("akka.cluster.seed-nodes").nonEmpty) {
    Some(Cluster(actorSystem))
  } else {
    None
  }

  /** State needed for scheduling. */
  val schedulingState = LeastLoadBalancerState()(lbConfig)

  /**
   * Monitors invoker supervision and the cluster to update the state sequentially
   *
   * All state updates should go through this actor to guarantee that
   * [[LeastLoadBalancerState.updateInvokers]] and [[LeastLoadBalancerState.updateCluster]]
   * are called exclusive of each other and not concurrently.
   */
  private val monitor = actorSystem.actorOf(Props(new Actor {
    override def preStart(): Unit = {
      cluster.foreach(_.subscribe(self, classOf[MemberEvent], classOf[ReachabilityEvent]))
    }

    // all members of the cluster that are available
    var availableMembers = Set.empty[Member]

    override def receive: Receive = {
      case CurrentInvokerPoolState(newState) =>
        schedulingState.updateInvokers(newState)

      // State of the cluster as it is right now
      case CurrentClusterState(members, _, _, _, _) =>
        availableMembers = members.filter(_.status == MemberStatus.Up)
        schedulingState.updateCluster(availableMembers.size)

      // General lifecycle events and events concerning the reachability of members. Split-brain is not a huge concern
      // in this case as only the invoker-threshold is adjusted according to the perceived cluster-size.
      // Taking the unreachable member out of the cluster from that point-of-view results in a better experience
      // even under split-brain-conditions, as that (in the worst-case) results in premature overloading of invokers vs.
      // going into overflow mode prematurely.
      case event: ClusterDomainEvent =>
        availableMembers = event match {
          case MemberUp(member)          => availableMembers + member
          case ReachableMember(member)   => availableMembers + member
          case MemberRemoved(member, _)  => availableMembers - member
          case UnreachableMember(member) => availableMembers - member
          case _                         => availableMembers
        }

        schedulingState.updateCluster(availableMembers.size)
    }
  }))

  override def invokerHealth(): Future[IndexedSeq[InvokerHealth]] = Future.successful(schedulingState.invokers)

  /** 1. Publish a message to the loadbalancer */
  override def publish(action: ExecutableWhiskActionMetaData, msg: ActivationMessage)(
    implicit transid: TransactionId): Future[Future[Either[ActivationId, WhiskActivation]]] = {

    val isBlackboxInvocation = action.exec.pull
    val actionType = if (!isBlackboxInvocation) "managed" else "blackbox"
    val (invokersToUse, stepSizes) = (schedulingState.invokers, schedulingState.managedStepSizes)
    val chosen = if (invokersToUse.nonEmpty) {
      val invoker: Option[(InvokerInstanceId, Boolean)] = LeastLoadBalancer.schedule(
        invokersToUse,
        schedulingState,
        action.limits.memory.megabytes.toDouble)
      invoker.foreach {
        case (_, true) =>
          val metric =
            if (isBlackboxInvocation)
              LoggingMarkers.BLACKBOX_SYSTEM_OVERLOAD
            else
              LoggingMarkers.MANAGED_SYSTEM_OVERLOAD
          MetricEmitter.emitCounterMetric(metric)
        case _ =>
      }
      invoker.map(_._1)
    } else {
      None
    }

    chosen
      .map { invoker =>
        // MemoryLimit() and TimeLimit() return singletons - they should be fast enough to be used here
        val memoryLimit = action.limits.memory
        val memoryLimitInfo = if (memoryLimit == MemoryLimit()) { "std" } else { "non-std" }
        val timeLimit = action.limits.timeout
        val timeLimitInfo = if (timeLimit == TimeLimit()) { "std" } else { "non-std" }
        logging.info(
          this,
          s"scheduled activation ${msg.activationId}, action '${msg.action.asString}' ($actionType), ns '${msg.user.namespace.name.asString}', mem limit ${memoryLimit.megabytes} MB (${memoryLimitInfo}), time limit ${timeLimit.duration.toMillis} ms (${timeLimitInfo}) to ${invoker}")
        val activationResult = setupActivation(msg, action, invoker)
        sendActivationToInvoker(messageProducer, msg, invoker).map(_ => activationResult)
      }
      .getOrElse {
        // report the state of all invokers
        val invokerStates = invokersToUse.foldLeft(Map.empty[InvokerState, Int]) { (agg, curr) =>
          val count = agg.getOrElse(curr.status, 0) + 1
          agg + (curr.status -> count)
        }

        logging.error(
          this,
          s"failed to schedule activation ${msg.activationId}, action '${msg.action.asString}' ($actionType), ns '${msg.user.namespace.name.asString}' - invokers to use: $invokerStates")
        Future.failed(LoadBalancerException("No invokers available"))
      }
  }

  override val invokerPool =
    invokerPoolFactory.createInvokerPool(
      actorSystem,
      messagingProvider,
      messageProducer,
      sendActivationToInvoker,
      Some(monitor))

  override protected def releaseInvoker(invoker: InvokerInstanceId, entry: ActivationEntry) = {
    schedulingState.invokerSlots
      .lift(invoker.toInt)
      .foreach(_.releaseConcurrent(entry.fullyQualifiedEntityName, entry.maxConcurrent, entry.memoryLimit.toMB.toInt))
  }
}

object LeastLoadBalancer extends LoadBalancerProvider {

  override def instance(whiskConfig: WhiskConfig, instance: ControllerInstanceId)(implicit actorSystem: ActorSystem,
                                                                                  logging: Logging): LoadBalancer = {

    val invokerPoolFactory = new InvokerPoolFactory {
      override def createInvokerPool(
        actorRefFactory: ActorRefFactory,
        messagingProvider: MessagingProvider,
        messagingProducer: MessageProducer,
        sendActivationToInvoker: (MessageProducer, ActivationMessage, InvokerInstanceId) => Future[ResultMetadata],
        monitor: Option[ActorRef]): ActorRef = {

        InvokerPool.prepare(instance, WhiskEntityStore.datastore())

        actorRefFactory.actorOf(
          InvokerPool.props(
            (f, i) => f.actorOf(InvokerActor.props(i, instance)),
            (m, i) => sendActivationToInvoker(messagingProducer, m, i),
            messagingProvider.getConsumer(
              whiskConfig,
              s"${Controller.topicPrefix}health${instance.asString}",
              s"${Controller.topicPrefix}health",
              maxPeek = 128),
            monitor))
      }

    }
    new LeastLoadBalancer(
      whiskConfig,
      instance,
      createFeedFactory(whiskConfig, instance),
      invokerPoolFactory)
  }

  def requiredProperties: Map[String, String] = kafkaHosts

  def leastLoad(invokers: IndexedSeq[InvokerHealth], schedulingState: LeastLoadBalancerState) : InvokerHealth = {
    invokers.reduceLeft((left, right) =>
      {
        val ll = schedulingState.getLoad(left.id)
        val rl = schedulingState.getLoad(right.id)
        if (ll > rl) {
          right
        }
        else {
          left
        }
      }
    )
  }

  /**
   * Scans through all invokers and searches for an invoker tries to get a free slot on an invoker. If no slot can be
   * obtained, randomly picks a healthy invoker.
   *
   * @param maxConcurrent concurrency limit supported by this action
   * @param invokers a list of available invokers to search in, including their state
   * @param dispatched semaphores for each invoker to give the slots away from
   * @param slots Number of slots, that need to be acquired (e.g. memory in MB)
   * @param index the index to start from (initially should be the "homeInvoker"
   * @param step stable identifier of the entity to be scheduled
   * @return an invoker to schedule to or None of no invoker is available
   */
  def schedule(
    invokers: IndexedSeq[InvokerHealth],
    schedulingState: LeastLoadBalancerState,
    memoryLimit: Double)(implicit logging: Logging, transId: TransactionId): Option[(InvokerInstanceId, Boolean)] = {

    val availableInvokers = invokers.filter(invoker => schedulingState.getMem(invoker.id) >= memoryLimit)

    if (availableInvokers.size > 0) {
      val invoker = leastLoad(availableInvokers, schedulingState)
      Some(invoker.id, false)
    } else {
      None
    }
  }
}

/**
 * Holds the state necessary for scheduling of actions.
 *
 * @param _invokers all of the known invokers in the system
 */
case class LeastLoadBalancerState(
  private var _invokers: IndexedSeq[InvokerHealth] = IndexedSeq.empty[InvokerHealth],
  private var _managedInvokers: IndexedSeq[InvokerHealth] = IndexedSeq.empty[InvokerHealth],
  private var _blackboxInvokers: IndexedSeq[InvokerHealth] = IndexedSeq.empty[InvokerHealth],
  private var _managedStepSizes: Seq[Int] = ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(0),
  private var _blackboxStepSizes: Seq[Int] = ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(0),
  protected[loadBalancer] var _invokerSlots: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]] =
    IndexedSeq.empty[NestedSemaphore[FullyQualifiedEntityName]],
  private var _clusterSize: Int = 1)(
  lbConfig: ShardingContainerPoolBalancerConfig =
    loadConfigOrThrow[ShardingContainerPoolBalancerConfig](ConfigKeys.loadbalancer))(implicit logging: Logging) {

  // Managed fraction and blackbox fraction can be between 0.0 and 1.0. The sum of these two fractions has to be between
  // 1.0 and 2.0.
  // If the sum is 1.0 that means, that there is no overlap of blackbox and managed invokers. If the sum is 2.0, that
  // means, that there is no differentiation between managed and blackbox invokers.
  // If the sum is below 1.0 with the initial values from config, the blackbox fraction will be set higher than
  // specified in config and adapted to the managed fraction.
  private val managedFraction: Double = Math.max(0.0, Math.min(1.0, lbConfig.managedFraction))
  private val blackboxFraction: Double = Math.max(1.0 - managedFraction, Math.min(1.0, lbConfig.blackboxFraction))
  logging.info(this, s"managedFraction = $managedFraction, blackboxFraction = $blackboxFraction")(
    TransactionId.loadbalancer)

  /** Getters for the variables, setting from the outside is only allowed through the update methods below */
  def invokers: IndexedSeq[InvokerHealth] = _invokers
  def managedInvokers: IndexedSeq[InvokerHealth] = _managedInvokers
  def blackboxInvokers: IndexedSeq[InvokerHealth] = _blackboxInvokers
  def managedStepSizes: Seq[Int] = _managedStepSizes
  def blackboxStepSizes: Seq[Int] = _blackboxStepSizes
  def invokerSlots: IndexedSeq[NestedSemaphore[FullyQualifiedEntityName]] = _invokerSlots
  def clusterSize: Int = _clusterSize

  /**
   * @param memory
   * @return calculated invoker slot
   */
  private def getInvokerSlot(memory: ByteSize): ByteSize = {
    val invokerShardMemorySize = memory / _invokers.size
    val newTreshold = if (invokerShardMemorySize < MemoryLimit.MIN_MEMORY) {
      logging.error(
        this,
        s"registered controllers: calculated controller's invoker shard memory size falls below the min memory of one action. "
          + s"Setting to min memory. Expect invoker overloads. Cluster size ${_invokers.size}, invoker user memory size ${memory.toMB.MB}, "
          + s"min action memory size ${MemoryLimit.MIN_MEMORY.toMB.MB}, calculated shard size ${invokerShardMemorySize.toMB.MB}.")(
        TransactionId.loadbalancer)
      MemoryLimit.MIN_MEMORY
    } else {
      invokerShardMemorySize
    }
    newTreshold
  }

  def getLoad(invoker: InvokerInstanceId) : Double = {
    //TODO: return the load of invoker
    val rand = (new Random).nextInt(100).toDouble
    logging.info(
    this,
    s"${invoker}'s CPU: ${rand}%"
    )
    rand
  }

  def getMem(invoker: InvokerInstanceId) : Double = {
    //TODO: return the memory of invoker in MB.
    val rand = ((new Random).nextInt(1024 - 128) + 128).toDouble
    logging.info(
    this,
    s"${invoker}'s Remain Memory: ${rand}MB"
    )
    rand
  }

  /**
   * Updates the scheduling state with the new invokers.
   *
   * This is okay to not happen atomically since dirty reads of the values set are not dangerous. It is important though
   * to update the "invokers" variables last, since they will determine the range of invokers to choose from.
   */
  def updateInvokers(newInvokers: IndexedSeq[InvokerHealth]): Unit = {
    val oldSize = _invokers.size
    val newSize = newInvokers.size

    // for small N, allow the managed invokers to overlap with blackbox invokers, and
    // further assume that blackbox invokers << managed invokers
    val managed = Math.max(1, Math.ceil(newSize.toDouble * managedFraction).toInt)
    val blackboxes = Math.max(1, Math.floor(newSize.toDouble * blackboxFraction).toInt)

    _invokers = newInvokers
    _managedInvokers = _invokers.take(managed)
    _blackboxInvokers = _invokers.takeRight(blackboxes)

    val logDetail = if (oldSize != newSize) {
      _managedStepSizes = ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(managed)
      _blackboxStepSizes = ShardingContainerPoolBalancer.pairwiseCoprimeNumbersUntil(blackboxes)

      if (oldSize < newSize) {
        // Keeps the existing state..
        val onlyNewInvokers = _invokers.drop(_invokerSlots.length)
        _invokerSlots = _invokerSlots ++ onlyNewInvokers.map { invoker =>
          new NestedSemaphore[FullyQualifiedEntityName](getInvokerSlot(invoker.id.userMemory).toMB.toInt)
        }
        val newInvokerDetails = onlyNewInvokers
          .map(i =>
            s"${i.id.toString}: ${i.status} / ${getInvokerSlot(i.id.userMemory).toMB.MB} of ${i.id.userMemory.toMB.MB}")
          .mkString(", ")
        s"number of known invokers increased: new = $newSize, old = $oldSize. details: $newInvokerDetails."
      } else {
        s"number of known invokers decreased: new = $newSize, old = $oldSize."
      }
    } else {
      s"no update required - number of known invokers unchanged: $newSize."
    }

    logging.info(
      this,
      s"loadbalancer invoker status updated. managedInvokers = $managed blackboxInvokers = $blackboxes. $logDetail")(
      TransactionId.loadbalancer)
  }

  def updateCluster(newSize: Int): Unit = {
    val actualSize = newSize max 1 // if a cluster size < 1 is reported, falls back to a size of 1 (alone)
    if (_clusterSize != actualSize) {
      val oldSize = _clusterSize
      _clusterSize = actualSize
      _invokerSlots = _invokers.map { invoker =>
        new NestedSemaphore[FullyQualifiedEntityName](getInvokerSlot(invoker.id.userMemory).toMB.toInt)
      }
      // Directly after startup, no invokers have registered yet. This needs to be handled gracefully.
      val invokerCount = _invokers.size
      val totalInvokerMemory =
        _invokers.foldLeft(0L)((total, invoker) => total + getInvokerSlot(invoker.id.userMemory).toMB).MB
      val averageInvokerMemory =
        if (totalInvokerMemory.toMB > 0 && invokerCount > 0) {
          (totalInvokerMemory / invokerCount).toMB.MB
        } else {
          0.MB
        }
      logging.info(
        this,
        s"loadbalancer cluster size changed from $oldSize to $actualSize active nodes. ${invokerCount} invokers with ${averageInvokerMemory} average memory size - total invoker memory ${totalInvokerMemory}.")(
        TransactionId.loadbalancer)
    }
  }
}