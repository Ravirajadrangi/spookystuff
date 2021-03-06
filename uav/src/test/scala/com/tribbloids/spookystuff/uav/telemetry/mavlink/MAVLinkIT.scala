package com.tribbloids.spookystuff.uav.telemetry.mavlink

import com.tribbloids.spookystuff.uav.dsl.LinkFactories
import com.tribbloids.spookystuff.uav.sim.{APMSim, APMSITLFixture}
import com.tribbloids.spookystuff.uav.system.UAV
import com.tribbloids.spookystuff.uav.telemetry.{Link, LinkITFixture}
import com.tribbloids.spookystuff.session.Session
import com.tribbloids.spookystuff.testutils.TestHelper

import scala.concurrent.{Await, Future}

/**
  * Created by peng on 27/01/17.
  */
class MAVLinkIT extends LinkITFixture with APMSITLFixture {

}

class MAVLinkIT_Proxy extends MAVLinkIT {

  override lazy val linkFactory = LinkFactories.ForkToGCS(
    ToExecutorSize = 2
  )

  it("GCS takeover and relinquish control during flight") {
    import scala.concurrent.ExecutionContext.Implicits.global
    import scala.concurrent.duration._

    val spooky = this.spooky

    val rdd = simURIRDD.map {
      connStr =>
        val drones = Seq(UAV(Seq(connStr)))
        val session = new Session(spooky)
        val link = Link.trySelect( //refitting
          drones,
          session
        )
          .get.asInstanceOf[MAVLink]

        val endpoint1 = link.Endpoints.primary
        val endpoint2 = link.Endpoints.executors.last
        endpoint1.PY.assureClearanceAlt(20)
        endpoint2.PY.start()

        val moved = Future {
          endpoint1.PY.testMove()
        }
        Thread.sleep(5000 / APMSim.SPEEDUP)

        def changeAndVerifyMode(endpoint: Endpoint, mode: String) = {
          val py = endpoint.PY
          py.mode(mode)
          TestHelper.assert(py.vehicle.mode.name.$STR.get == mode)
        }

        changeAndVerifyMode(endpoint2, "BRAKE")
        //          changeMode(py2, "LOITER")
        Thread.sleep(5000 / APMSim.SPEEDUP)
        changeAndVerifyMode(endpoint2, "GUIDED")
        val position = Await.result(moved, 60.seconds).$STR.get
        println(position)
    }
    val location = rdd.collect().head

    println(location)
    assertLinkCreated(parallelism)
  }
}
