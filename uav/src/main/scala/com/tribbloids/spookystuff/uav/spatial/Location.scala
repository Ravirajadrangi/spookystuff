package com.tribbloids.spookystuff.uav.spatial

import com.tribbloids.spookystuff.caching.ConcurrentSet
import com.tribbloids.spookystuff.uav.UAVConf
import com.tribbloids.spookystuff.utils.ScalaUDT
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.types.SQLUserDefinedType
import org.slf4j.LoggerFactory

import scala.collection.mutable.ArrayBuffer
import scala.language.implicitConversions
import scala.util.Random

/**
  * Created by peng on 15/02/17.
  */
trait Anchor extends Serializable {

  def getCoordinate(
                     system: CoordinateSystem = LLA,
                     from: Anchor = GeodeticAnchor
                   ): Option[system.V] = {
    _getCoordinate(system, from, InferenceContext())
  }

  def coordinate(system: CoordinateSystem = LLA, from: Anchor = GeodeticAnchor): system.V = {
    getCoordinate(system, from).getOrElse {
      throw new UnsupportedOperationException(s"cannot determine relative position from $from to $this")
    }
  }

  def _getCoordinate(
                      system: CoordinateSystem,
                      from: Anchor = GeodeticAnchor,
                      ic: InferenceContext
                    ): Option[system.V] = None
}

case object GeodeticAnchor extends Anchor {
}
// to be injected by SpookyConf
case object PlaceHoldingAnchor extends Anchor {
}

trait LocationLike extends Anchor with StartEndLocation {

  override def start = this
  override def end = this
}

case class UnknownLocation(
                            _id: Long = Random.nextLong()
                          ) extends LocationLike {

}

class LocationUDT() extends ScalaUDT[Location]

// to be enriched by SpookyContext (e.g. baseLocation, CRS etc.)
@SQLUserDefinedType(udt = classOf[LocationUDT])
@SerialVersionUID(-928750192836509428L)
case class Location(
                     definedBy: Seq[Relation] = Nil
                     //                     _id: Long = Random.nextLong()
                   ) extends LocationLike {

  //  assert(definedBy.nonEmpty, "Location not defined")

  //  require(!coordinates.exists(_._2 == this), "self referential coordinate cannot be used")

  def assumeAnchor(ref: Anchor): Location = {
    val cs = definedBy.map {
      tuple =>
        if (tuple.from == PlaceHoldingAnchor) {
          require(definedBy.size == 1, "")
          tuple.copy(from = ref)
        }
        else tuple
    }
    this.copy(definedBy = cs)
  }

  private val mnemonics: ArrayBuffer[Relation] = {
    val result = ArrayBuffer.empty[Relation]
    val preset = definedBy // ++ Seq(Denotation(NED(0,0,0), this))
    result.++=(preset)
    result
  }

  def addCoordinate(tuples: Relation*): this.type = {
    assert(!tuples.contains(null))
    mnemonics ++= tuples
    //    require(!tuples.exists(_._2 == this), "self referential coordinate cannot be used")
    this
  }

  // always add result into buffer to avoid repeated computation
  // recursively search through its relations to deduce the coordinate.
  override def _getCoordinate(
                               system: CoordinateSystem,
                               from: Anchor = GeodeticAnchor,
                               ic: InferenceContext
                             ): Option[system.V] = {

    if (ic.recursions >= 1000) {
      throw new UnsupportedOperationException("too many recursions")
    }

    if (from == this) {
      system.zero.foreach {
        z =>
          return Some(z)
      }
    }

    val allRelations = mnemonics

    def cacheAndYield(v: Coordinate): Option[system.V] = {
      addCoordinate(Relation(v, from))
      Some(v.asInstanceOf[system.V])
    }

    allRelations
      .foreach {
        rel =>
          if (
            rel.coordinate.system == system &&
              rel.from == from
          ) {
            return Some(rel.coordinate.asInstanceOf[system.V])
          }
      }

    allRelations
      .foreach {
        rel =>
          val debugStr = s"""
                            |${this.toString}
                            |-------------
                            |inferring ${system.name} from $from
                            |using ${rel.coordinate.system.name} from ${rel.from}
                          """.trim.stripMargin
          LoggerFactory.getLogger(this.getClass).debug {
            debugStr
          }

          val directOpt: Option[CoordinateSystem#V] = rel.project(from, system, ic)
          directOpt.foreach {
            direct =>
              return cacheAndYield(direct)
          }

          //use chain rule for inference
          rel.from match {
            case middle: Location if middle != this && middle != from =>
              for (
                c2 <- {
                  ic.getCoordinate(PendingTriplet(middle, system, this))
                };
                c1 <- {
                  ic.getCoordinate(PendingTriplet(from, system, middle))
                }
              ) {
                return cacheAndYield(c1.asInstanceOf[system.V] ++> c2.asInstanceOf[system.V])
              }
            case _ =>
          }
      }

    //add reverse deduction.

    None
  }

  override def toString: String = {
    definedBy.map(_.treeString).mkString("[\n", ",", "]")
  }
}

object Location {

  implicit def fromCoordinate(
                               c: Coordinate
                             ): Location = {
    Location(Seq(
      Relation(c, PlaceHoldingAnchor)
    ))
  }

  implicit def fromTuple(
                          t: (Coordinate, Anchor)
                        ): Location = {
    Location(Seq(
      Relation(t._1, t._2)
    ))
  }

  def parse(v: Any, conf: UAVConf): Location = {
    v match {
      case p: Location =>
        p.assumeAnchor(conf.homeLocation)
      case c: Coordinate =>
        c.assumeAnchor(conf.homeLocation)
      case v: GenericRowWithSchema =>
        val schema = v.schema
        val names = schema.fields.map(_.name)
        val c = if (names.containsSlice(Seq("lat", "lon", "alt"))) {
          LLA(
            v.getAs[Double]("lat"),
            v.getAs[Double]("lon"),
            v.getAs[Double]("alt")
          )
        }
        else if (names.containsSlice(Seq("north", "east", "down"))) {
          NED(
            v.getAs[Double]("north"),
            v.getAs[Double]("east"),
            v.getAs[Double]("down")
          )
        }
        else {
          ???
        }
        c.assumeAnchor(conf.homeLocation)
      case s: String =>
        ???
      case _ =>
        ???
    }
  }

  //TODO: from Row

  //  def d[T <: CoordinateLike : ClassTag](a: Position, b: Position): Try[Double] = {
  //    val ctg = implicitly[ClassTag[T]]
  //    dMat((a, b, ctg))
  //  }
}