package com.tribbloids.spookystuff.uav.planning

import com.tribbloids.spookystuff.uav.UAVTestUtils
import com.tribbloids.spookystuff.uav.actions.Waypoint
import com.tribbloids.spookystuff.uav.sim.{APMSITLFixture, DefaultSimFactory}
import com.tribbloids.spookystuff.uav.spatial.NED
import com.tribbloids.spookystuff.uav.telemetry.DummyLink
import org.scalactic.TolerantNumerics

/**
  * Created by peng on 24/02/17.
  */
class GASolverSuite extends APMSITLFixture {

  override lazy val simFactory = DefaultSimFactory

  override def parallelism: Int = 4

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

  lazy val link = {
    val drone = simDrones.head
    val link = DummyLink(drone)
    link
  }
  lazy val initialRoute = {
    Route(link, main.indices)
  }

  test("Route can be converted to traces") {

    val route = initialRoute
    val traces = route.toTraces(solver.allTracesBroadcasted.value)
    traces.mkString("\n").shouldBe(
      main.map{a => List(a)}
        .map {
          trace =>
            Seq(UseLink(link)) ++ trace
        }
        .mkString("\n")
    )
  }

  test("Route can estimate cost") {

    implicit val doubleEquality = TolerantNumerics.tolerantDoubleEquality(0.00001)

    val route = initialRoute
    val cost = route.estimateCost(solver)
    assert(cost === 119.543903)
  }

  test("Route can calculate the optimal strategy to insert a waypoint") {
    val route = initialRoute
    val inserted = route.optimalInsertFrom(Seq(main.length), solver)
    inserted.is.mkString(",").shouldBe(
      "0,10,1,2,3,4,5,6,7,8,9"
    )
  }

  test("Route can calculate the optimal strategy to insert several waypoints") {
    val route = initialRoute
    val inserted = route.optimalInsertFrom(main.length until allWPs.length, solver)
    inserted.is.mkString(",").shouldBe(
      "0,10,1,2,11,3,4,5,6,12,7,8,13,9"
    )
  }

  test("Generating seed population") {

//    val
  }
}