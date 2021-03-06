package com.tribbloids.spookystuff.execution

import com.tribbloids.spookystuff.SpookyContext
import com.tribbloids.spookystuff.actions._
import com.tribbloids.spookystuff.row.{BeaconRDD, DataRowSchema, SquashedFetchedRDD}
import org.apache.spark.rdd.RDD

import scala.collection.mutable.ArrayBuffer

/**
  * Basic Plan with no children, isExecuted always= true
  */
case class RDDPlan(
                    sourceRDD: SquashedFetchedRDD,
                    override val schema: DataRowSchema,
                    override val spooky: SpookyContext,
                    beaconRDD: Option[BeaconRDD[TraceView]] = None,
                    scratchRDDs: ScratchRDDs = ScratchRDDs()
                  ) extends ExecutionPlan(
  Seq(),
  ExecutionPlan.Context(spooky, scratchRDDs)
) {

  override lazy val beaconRDDOpt = beaconRDD

  override def doExecute(): SquashedFetchedRDD = sourceRDD
}
