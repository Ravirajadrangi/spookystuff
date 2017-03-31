package com.tribbloids.spookystuff.uav.telemetry

import com.tribbloids.spookystuff.SpookyEnvFixture
import com.tribbloids.spookystuff.uav.dsl.{LinkFactories, LinkFactory}
import com.tribbloids.spookystuff.uav.system.UAV
import com.tribbloids.spookystuff.uav.telemetry.mavlink.MAVLink

/**
  * Created by peng on 30/11/16.
  */
class LinkFactoriesSuite extends SpookyEnvFixture {

  def canCreate(factory: LinkFactory, link: Link): Boolean = {

    val dryRun = factory.apply(link.uav)
    val result = link.coFactory(dryRun)

    result
  }

  override def setUp(): Unit = {
    super.setUp()
    Link.existing.values.foreach(_.tryClean())
  }

  override def tearDown(): Unit = {
    assert(Link.existing.size == 1) // ensure that canCreate won't leave zombie link(s)
    super.tearDown()
  }

  test("NoProxy can create link without proxy") {

    val factory = LinkFactories.Direct
    val link = MAVLink(UAV(Seq("dummy")), Nil).setContext(spooky, factory)
    assert(canCreate(factory, link))
  }

  test("NoProxy can create link with proxy that has no GCS out") {

    val factory = LinkFactories.Direct
    val link = MAVLink(UAV(Seq("dummy")), Seq("localhost:80")).setContext(spooky, factory)
    assert(canCreate(factory, link))
  }

  test("NoProxy can create link with proxy that has 1 GCS outs") {

    val factory = LinkFactories.Direct
    val link = MAVLink(UAV(Seq("dummy")), Seq("localhost:80"), Seq("localhost:14550"))
      .setContext(spooky, factory)
    assert(!canCreate(factory, link))
  }

  test("ForkToGCS cannot create link without proxy") {

    val factory = LinkFactories.ForkToGCS()
    val link = MAVLink(UAV(Seq("dummy")), Nil).setContext(spooky, factory)
    assert(!canCreate(factory, link))
  }

  test("ForkToGCS can create link with proxy that has identical GCS outs") {

    val factory = LinkFactories.ForkToGCS()
    val link = MAVLink(UAV(Seq("dummy")), Seq("localhost:80"), Seq("udp:localhost:14550"))
      .setContext(spooky, factory)
    assert(canCreate(factory, link))
  }

  test("ForkToGCS cannot create link with proxy that has different GCS outs") {

    val factory = LinkFactories.ForkToGCS()
    val link = MAVLink(UAV(Seq("dummy")), Seq("localhost:80")).setContext(spooky, factory)
    assert(!canCreate(factory, link))
  }
}
