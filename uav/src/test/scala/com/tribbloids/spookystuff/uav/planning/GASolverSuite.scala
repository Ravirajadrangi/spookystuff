package com.tribbloids.spookystuff.uav.planning

import com.tribbloids.spookystuff.SpookyEnvFixture
import com.tribbloids.spookystuff.uav.UAVTestUtils
import com.tribbloids.spookystuff.uav.actions.Waypoint
import com.tribbloids.spookystuff.uav.spatial.NED
import com.tribbloids.spookystuff.uav.system.UAV
import com.tribbloids.spookystuff.uav.telemetry.DummyLink
import org.scalactic.TolerantNumerics

import scala.util.Success

/**
  * Created by peng on 24/02/17.
  */
class GASolverSuite extends SpookyEnvFixture {

  val main: Seq[Waypoint] = UAVTestUtils.LawnMowerPattern(
    5,
    NED(10, 10, -10),
    NED(100, 0, 0),
    NED(0, 20, -2)
  )
    .wpActions

  val toBeInserted: Seq[Waypoint] = UAVTestUtils.NEDPattern(
    Seq(
      NED(50, 0, 0) -> NED(50, 40, 0),
      NED(50, 80, 0) -> NED(50, 100, 0)
    )
  )
    .wpActions

  val allWPs = main ++ toBeInserted

  val solver = GASolver(
    allWPs.map{ a => List(a)}.toList,
    this.spooky
  )

  lazy val dummyLink = {
    val drone = UAV(Seq("dummy"))
    val link = DummyLink(drone)
    link
  }
  lazy val dummyRoute = {
    Route(Success(dummyLink), main.indices)
  }

  it("Route can be converted to traces") {

    val route = dummyRoute
    val traces = route.toTracesOpt(solver.allTracesBroadcasted.value).get
    traces.mkString("\n").shouldBe(
      main.map{a => List(a)}
        .map {
          trace =>
            Seq(PreferLink(dummyLink)) ++ trace
        }
        .mkString("\n")
    )
  }

  it("Route can estimate cost") {

    implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.00001)

    val route = dummyRoute
    val cost = route.estimateCost(solver)
    assert(cost === 119.543903)
  }

  it("Route can calculate the optimal strategy to insert a waypoint") {
    val route = dummyRoute
    val inserted = route.optimalInsertFrom(Seq(main.length), solver)
    inserted.is.mkString(",").shouldBe(
      "0,10,1,2,3,4,5,6,7,8,9"
    )
  }

  it("Route can calculate the optimal strategy to insert several waypoints") {
    val route = dummyRoute
    val inserted = route.optimalInsertFrom(main.length until allWPs.length, solver)
    inserted.is.mkString(",").shouldBe(
      "0,10,1,2,11,3,4,5,6,12,7,8,13,9"
    )
  }

  it("sampling without replacement") {

    val seq = solver.sampleWithoutReplacement(2)
    assert(seq.size == 2)
  }
}
