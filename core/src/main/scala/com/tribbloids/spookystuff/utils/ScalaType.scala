package com.tribbloids.spookystuff.utils

import java.nio.ByteBuffer
import java.sql.{Date, Timestamp}

import org.apache.spark.SparkConf
import org.apache.spark.serializer.JavaSerializer
import org.apache.spark.sql.catalyst.ScalaReflection.universe._
import org.apache.spark.sql.types._

import scala.collection.Map
import scala.language.implicitConversions
import scala.reflect.ClassTag

/**
  * interface that unifies TypeTag, ClassTag, Class & DataType
  * Also a subclass of Spark SQL DataType but NOT recommended to use directly in DataFrame, can cause compatibility issues.
  * either use tryReify to attempt converting to a native DataType. Or use UnoptimizedScalaUDT (which is abandoned in Spark 2.x)
  * Will be simplified again once Spark 2.2 introduces UserDefinedType V2.
  */
//TODO: change to ThreadLocal to bypass thread safety?
//TODO: use scala type class: http://danielwestheide.com/blog/2013/02/06/the-neophytes-guide-to-scala-part-12-type-classes.html
trait ScalaType[T] extends DataType with (() => TypeTag[T]) with ReflectionLock with Serializable with IDMixin {

  override def defaultSize: Int = 0
  override def asNullable: this.type = this

  override def toString = typeName

  @transient lazy val asTypeTag: TypeTag[T] = locked {
    apply()
  }
  @transient lazy val asType = locked {
    asTypeTag.tpe
  }
  @transient lazy val asClass: Class[T] = locked {
    asTypeTag.mirror.runtimeClass(asType).asInstanceOf[Class[T]]
  }
  @transient lazy val asClassTag: ClassTag[T] = locked {
    ClassTag(asClass)
  }
  @transient lazy val tryReify: scala.util.Try[DataType] = locked {
    TypeUtils.tryCatalystTypeFor(asTypeTag)
      .flatMap {
        t =>
          scala.util.Try {
            assert(t.getClass != this.getClass, "cyclic reification")
            t
          }
      }
  }

  // if ttg is lost, will try to reconstruct from reified DType
  //  def scalaTypeOpt: Option[TypeTag[_]] = {
  //    Option(ttg)
  //      .orElse {
  //        reifyOpt.flatMap[TypeTag[_]] {
  //          (reified: DataType) =>
  //            scala.util.Try {
  //              reified.scalaType
  //            }
  //              .toOption
  //        }
  //      }
  //  }

  def reifyOrError: DataType = tryReify.get
  def reifyOrSelf = tryReify.getOrElse{this}
  def reifyOrNullType = tryReify.getOrElse{NullType}

  // see [SPARK-8647], this achieves the needed constant hash code without declaring singleton
  //TODO: this is not accurate due to type erasure, need a better way to handle both type erasure & type alias
  override val _id = {
    "" + asClass + "/" + asTypeTag.tpe.toString
  }
}

object ScalaType {

  trait TTg[T] extends ScalaType[T] {

    def _typeTag: TypeTag[T] // not serializable in 2.10

    lazy val serTypeTag: SerializableTypeTag[T] = new SerializableTypeTag(_typeTag)
    serTypeTag

    override def apply() = Option(_typeTag).getOrElse {
      serTypeTag.value
    }
  }
  implicit class FromTypeTag[T](@transient override val _typeTag: TypeTag[T]) extends TTg[T]
  def fromTypeTag[T](implicit v: TypeTag[T]) = new FromTypeTag(v)

  trait Clz[T] extends ScalaType[T] {

    def _class: Class[T]
    override lazy val asClass = {
      _class
    }

    private def mirror = {
      val loader = _class.getClassLoader
      runtimeMirror(loader)
    }
    //    def mirror = ReflectionUtils.mirrorFactory.get()

    @transient override lazy val asType = locked {
      val classSymbol = mirror.staticClass(_class.getCanonicalName)
      val tpe = classSymbol.selfType
      tpe
    }

    override def apply(): TypeTag[T] = {
      TypeUtils.createTypeTag(asType, mirror)
    }
  }
  implicit class FromClass[T](val _class: Class[T]) extends Clz[T]
  def fromClass[T](implicit v: Class[T]) = new FromClass(v)

  trait Ctg[T] extends Clz[T] {

    def _classTag: ClassTag[T]
    override lazy val asClassTag = {
      _classTag
    }

    def _class: Class[T] = locked {
      _classTag.runtimeClass.asInstanceOf[Class[T]]
    }
  }
  implicit class FromClassTag[T](val _classTag: ClassTag[T]) extends Ctg[T]
  def fromClassTag[T](implicit v: ClassTag[T]) = new FromClassTag(v)

  object DTypeView {

    lazy val atomicExamples: Seq[(Any, TypeTag[_])] = {

      implicit def pairFor[T: TypeTag](v: T): (T, TypeTag[T]) = {
        v -> TypeUtils.getTypeTag[T](v)
      }

      val result = Seq[(Any, TypeTag[_])](
        Array(0: Byte),
        false,
        new Date(0),
        new Timestamp(0),
        0.0,
        0: Float,
        0: Byte,
        0: Int,
        0L,
        0: Short,
        "a"
      )
      result
    }

    lazy val atomicTypePairs: Seq[(DataType, TypeTag[_])] = atomicExamples.map {
      v =>
        ScalaType.fromTypeTag(v._2).tryReify.get -> v._2
    }

    lazy val atomicTypeMap: Map[DataType, TypeTag[_]] = {
      Map(atomicTypePairs: _*)
    }
  }

  implicit class DTypeView(tt: DataType) extends ReflectionLock {

    // CatalystType => ScalaType
    // used in ReflectionMixin to determine the exact function to:
    // 1. convert data from CatalystType to canonical Scala Type (and obtain its TypeTag)
    // 2. use the obtained TypeTag to get the specific function implementation and applies to the canonic Scala Type data.
    // 3. get the output TypeTag of the function, use it to generate the output DataType of the new Extraction.
    def scalaTypeOpt: Option[TypeTag[_]] = locked {

      tt match {
        case NullType =>
          Some(TypeTag.Null)
        case st: ScalaType[_] =>
          Some(st.asTypeTag)
        case t if DTypeView.atomicTypeMap.contains(t) =>
          DTypeView.atomicTypeMap.get(t)
        case ArrayType(inner, _) =>
          val innerTagOpt = inner.scalaTypeOpt
          innerTagOpt.map {
            case at: TypeTag[a] =>
              implicit val att = at
              typeTag[Array[a]]
          }
        case MapType(key, value, _) =>
          val keyTag = key.scalaTypeOpt
          val valueTag = value.scalaTypeOpt
          val pairs = (keyTag, valueTag) match {
            case (Some(kt), Some(vt)) => Some(kt -> vt)
            case _ => None
          }

          pairs.map {
            pair =>
              (pair._1, pair._2) match {
                case (ttg1: TypeTag[a], ttg2: TypeTag[b]) =>
                  implicit val t1 = ttg1
                  implicit val t2 = ttg2
                  typeTag[Map[a, b]]
              }
          }
        case _ =>
          None
      }
    }

    def scalaType: TypeTag[_] = {
      scalaTypeOpt.getOrElse {
        throw new UnsupportedOperationException(s"cannot convert Catalyst type $tt to Scala type: TypeTag=${tt.scalaTypeOpt}")
      }
    }

    def reify = locked {
      val result = UnreifiedScalaType.reify(tt)
      result
    }

    def unboxArrayOrMap: DataType = locked {
      tt._unboxArrayOrMapOpt
        .orElse(
          tt.reify._unboxArrayOrMapOpt
        )
        .getOrElse(
          throw new UnsupportedOperationException(s"Type $tt is not an Array")
        )
    }

    private[utils] def _unboxArrayOrMapOpt: Option[DataType] = locked {
      tt match {
        case ArrayType(boxed, _) =>
          Some(boxed)
        case MapType(keyType, valueType, valueContainsNull) =>
          Some(StructType(Array(
            StructField("_1", keyType),
            StructField("_2", valueType, valueContainsNull)
          )))
        case _ =>
          None
      }
    }

    def filterArray: Option[DataType] = locked {
      if (tt.reify.isInstanceOf[ArrayType])
        Some(tt)
      else
        None
    }

    def asArray: DataType = locked {
      filterArray.getOrElse{
        ArrayType(tt)
      }
    }

    def ensureArray: DataType = locked {
      filterArray.getOrElse{
        throw new UnsupportedOperationException(s"Type $tt is not an Array")
      }
    }

    def =~= (another: DataType): Boolean = {
      val result = (tt eq another) ||
        (tt == another) ||
        (tt.reify == another.reify)

      result
    }

    def should_=~=(another: DataType): Unit = {
      val result = =~= (another)
      assert (
        result,
        s"""
           |Type not equal:
           |LEFT:  $tt -> ${tt.reify}
           |RIGHT: $another -> ${another.reify}
          """.stripMargin
      )
    }
  }
}

/**
  * Can only exist in DataRowSchema & extractor to remember ScalaType
  * Not allowed to be used in DataFrame schema
  */
class UnreifiedScalaType[T](@transient val _typeTag: TypeTag[T]) extends ScalaType.TTg[T] {

  override val typeName: String = "(unreified) " + asTypeTag
}

object UnreifiedScalaType {

  def apply[T](implicit ttg: TypeTag[T]): DataType = {
    if (ttg == TypeTag.Null) NullType
    else new UnreifiedScalaType(ttg)
  }

  def fromInstance[T](obj: T): DataType = {
    val clazz: Class[_] = obj.getClass
    apply(new ScalaType.FromClass(clazz).asTypeTag)
  }

  def reify(tt: DataType): DataType = {
    tt match {
      case udt: UnreifiedScalaType[_] => udt.reifyOrError
      case ArrayType(v, n) =>
        ArrayType(reify(v), n)
      case StructType(fields) =>
        StructType(
          fields.map {
            ff =>
              ff.copy(
                dataType = reify(ff.dataType)
              )
          }
        )
      case MapType(k, v, n) =>
        MapType(reify(k), reify(v), n)
      case _ => tt
    }
  }
}

/**
  *  A Scala TypeTag-based UDT, by default it doesn't compress object
  *  ideally it should compress object into InternalRow.
  *  Should be working and serve as the fallback strategy for ScalaReflection.schemaFor
  */
abstract class ScalaUDT[T: ClassTag] extends UserDefinedType[T] with ScalaType.Ctg[T] {

  override val typeName = this.getClass.getSimpleName.stripSuffix("$")//.stripSuffix("Type").stripSuffix("UDT").toLowerCase

  def serDe = { //TODO: kryo is better
    val conf = new SparkConf()
    new JavaSerializer(conf)
  }

  def _classTag: ClassTag[T] = implicitly[ClassTag[T]]

  def sqlType: DataType = BinaryType

  override def userClass: Class[T] = {
    _classTag.runtimeClass.asInstanceOf[Class[T]]
  }

  //should convert to internal Row.
  override def serialize(obj: Any): Any = {
    serDe.newInstance().serialize(obj).array()
  }

  override def deserialize(datum: Any): T = {
    datum match {
      case a: Array[Byte] =>
        serDe.newInstance().deserialize[T](ByteBuffer.wrap(a))
    }
  }
}