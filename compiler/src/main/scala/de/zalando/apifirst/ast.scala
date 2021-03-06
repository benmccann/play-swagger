package de.zalando.apifirst

import java.net.URLEncoder

import de.zalando.apifirst.Application.Model
import de.zalando.apifirst.Domain.Type
import de.zalando.apifirst.Http.MimeType
import de.zalando.swagger.model._

import scala.language.{postfixOps, implicitConversions}
import scala.util.parsing.input.Positional

sealed trait Expr

object Http {

  abstract class Verb(val name: String) extends Expr

  case object GET extends Verb("GET")

  case object POST extends Verb("POST")

  case object PUT extends Verb("PUT")

  case object DELETE extends Verb("DELETE")

  case object HEAD extends Verb("HEAD")

  case object OPTIONS extends Verb("OPTIONS")

  case object TRACE extends Verb("TRACE")

  case object PATCH extends Verb("PATCH")

  private val verbs = GET :: POST :: PUT :: DELETE :: HEAD :: OPTIONS :: TRACE :: PATCH :: Nil

  def string2verb(name: String): Option[Verb] = verbs find {
    _.name == name.trim.toUpperCase
  }


  // TODO flesh out this hierarchy
  class MimeType(val name: String) extends Expr

  case object ApplicationJson extends MimeType(name = "application/json")

  case class Body(value: Option[String]) extends Expr

}

object Hypermedia {

  // TODO relation should be another API call, not an url
  case class Relation(tpe: String, url: String)

}

object Domain {

  type ModelDefinition = Iterable[Domain.Type]

  case class TypeName(fullName: String) {
    import TypeName.SL
    val simpleName = fullName.trim.takeRight(fullName.length - fullName.lastIndexOf(SL) - 1)
    val nestedNamespace = "_" + simpleName
    val asSimpleType = camelize(simpleName)
    val namespace = fullName.trim.take(fullName.lastIndexOf(SL)).toLowerCase
    val oneUp = {
      val space = Option(namespace).flatMap(_.split(SL).filter(_.nonEmpty).drop(1).lastOption).getOrElse("")
      (if (space.nonEmpty) "_" + space + "." else "") + asSimpleType
    }

    def nestedIn(t: TypeName) = namespace.nonEmpty && t.fullName.toLowerCase.startsWith(namespace)
    def relativeTo(t: TypeName) = {
      val newSpace = namespace.replace(t.namespace,"").dropWhile(_ == SL).replaceAll(SL.toString, "._")
      val prefix = if (t.namespace.isEmpty) "" else "_"
      if (newSpace.nonEmpty) prefix + newSpace + '.' + asSimpleType else asSimpleType
    }
    def nest(name: String) = TypeName(fullName + SL + name)
    private def camelize(s: String) = if (s.isEmpty) s else s.head.toUpper + s.tail
  }
  object TypeName {
    val SL = '/'
    def apply(namespace: String, simpleName: String):TypeName = TypeName(namespace + SL + simpleName)
    def escapeName(name: String) = {
      val SimpleInnerPattern = """.*(?:Seq|Option)\[([^\]\[]+)\].*""".r
      val ComplexInnerPattern = """.*(?:Map)\[String,([^\]\[]+)\].*""".r
      name match {
        case SimpleInnerPattern(innerName) =>
          name.replace(innerName, escapeComplexName(innerName))
        case ComplexInnerPattern(innerName) =>
          ComplexInnerPattern.replaceFirstIn(name, name.replace(innerName, escapeComplexName(innerName)))
        case _ =>
          escapeComplexName(name)
      }
    }

    def escapeComplexName(innerName: String): String = {
      innerName.split('.').map(escape).toSeq.mkString(".")
    }

    def escape(name: String) = {
      import de.zalando.swagger.ScalaReserved._
      if (
        names.contains(name) ||
          startNames.exists(name.startsWith) ||
          partNames.exists(name.contains)
      )
        "`" + name + "`"
      else
        name
    }
  }


  case class TypeMeta(comment: Option[String], constraints: Seq[String]) {
    override def toString = s"""TypeMeta(${
      comment match {
        case None => "None"
        case Some(s) => "Some(\"" + s.replaceAll("\"", "\\\"") + "\")"
      }
    })"""
  }

  object TypeMeta {
    def apply(comment: Option[String]):TypeMeta = TypeMeta(comment, Seq.empty[String])
  }

  implicit def option2TypeMeta(o: Option[String]): TypeMeta = TypeMeta(o)

  implicit def schema2TypeMeta(s: Schema): TypeMeta = {
    new TypeMeta(Option(s.description), ValidationConverter.toValidations(s))
  }

  implicit def typeInfo2TypeMeta(s: TypeInfo): TypeMeta = Option(s.format)

  implicit def paramOrRefInfo2TypeMeta(s: ParameterOrReference): TypeMeta = s match {
    case s: Parameter =>
      new TypeMeta(Option(s.description), ValidationConverter.toValidations(s))
    case s: ParameterReference =>
      Option(s.$ref)
  }

  abstract class Type(val name: TypeName, val meta: TypeMeta) extends Expr {
    def nestedTypes: Seq[Type] = Nil
    def imports: Set[String] = Set.empty
  }

  implicit def string2TypeName(s: String): TypeName = TypeName(s)

  class Nmbr(override val name: TypeName, override val meta: TypeMeta) extends Type(name, meta)

  case class Int(override val meta: TypeMeta) extends Nmbr("Int", meta)

  case class Lng(override val meta: TypeMeta) extends Nmbr("Long", meta)

  case class Flt(override val meta: TypeMeta) extends Nmbr("Float", meta)

  case class Dbl(override val meta: TypeMeta) extends Nmbr("Double", meta)

  case class Byt(override val meta: TypeMeta) extends Nmbr("Byte", meta)

  case class Str(format: Option[String] = None, override val meta: TypeMeta) extends Type("String", meta)

  case class Bool(override val meta: TypeMeta) extends Type("Boolean", meta)

  case class Date(override val meta: TypeMeta) extends Type("Date", meta) {
    override val imports = Set("java.util.Date")
  }

  case class File(override val meta: TypeMeta) extends Type("File", meta) {
    override val imports = Set("java.io.File")
  }

  case class DateTime(override val meta: TypeMeta) extends Type("Date", meta) {
    override val imports = Set("java.util.Date")
  }

  case class Password(override val meta: TypeMeta) extends Type("Password", meta)

  case class Null(override val meta: TypeMeta) extends Type("null", meta)

  case class Any(override val meta: TypeMeta) extends Type("Any", meta)

  abstract class Container(override val name: TypeName, val field: Field, override val meta: TypeMeta, override val imports: Set[String])
    extends Type(name, meta) {
    def allImports: Set[String] = imports ++ field.imports
    override def nestedTypes = field.kind.nestedTypes :+ field.kind
  }

  case class Arr(override val field: Field, override val meta: TypeMeta)
    extends Container(s"Seq[${field.kind.name.oneUp}]", field, meta, Set("scala.collection.Seq"))

  case class Opt(override val field: Field, override val meta: TypeMeta)
    extends Container(s"Option[${field.kind.name.oneUp}]", field, meta, Set("scala.Option"))

  case class CatchAll(override val field: Field, override val meta: TypeMeta)
    extends Container(s"Map[String, ${field.kind.name.oneUp}]", field, meta, Set("scala.collection.immutable.Map"))

  abstract class Entity(override val name: TypeName, override val meta: TypeMeta) extends Type(name, meta)

  case class Field(override val name: TypeName, kind: Type, override val meta: TypeMeta) extends Type(name, meta) {
    override def toString = s"""Field("$name", $kind, $meta)"""

    def asCode(prefix: String = "") = s"$name: ${kind.name}"

    override def imports = kind match {
      case c: Container => c.allImports
      case o => o.imports
    }

    override def nestedTypes = kind.nestedTypes :+ kind
  }

  case class TypeDef(override val name: TypeName,
                     fields: Seq[Field],
                     extend: Seq[Reference] = Nil,
                     override val meta: TypeMeta) extends Entity(name, meta) {
    override def toString = s"""\n\tTypeDef("$name", List(${fields.mkString("\n\t\t", ",\n\t\t", "")}), $extend, $meta)\n"""

    def imports(implicit ast: Model): Set[String] = {
      val fromFields = fields.flatMap(_.imports)
      val transient = extend.flatMap(_.resolve(ast).toSeq.flatMap(_.imports))
      (fromFields ++ transient).filter(_.trim.nonEmpty).toSet
    }

    def allFields(implicit ast: Model): Seq[Field] =
      fields ++ extend.flatMap(_.resolve.toSeq.flatMap(_.allFields))

    def allExtends(implicit ast: Model): Seq[Reference] =
      extend ++ extend.flatMap(_.resolve.toSeq.flatMap(_.allExtends))

    override def nestedTypes = fields flatMap (_.nestedTypes) filter { _.name.nestedIn(name) } distinct
  }

  abstract class Reference(override val name: TypeName, override val meta: TypeMeta) extends Type(name, meta) {
    def resolve(implicit ast: Model): Option[TypeDef] = ???
  }

  case class ReferenceObject(override val name: TypeName, override val meta: TypeMeta) extends Reference(name, meta) {
    override def toString = s"""ReferenceObject("$name", $meta)"""
    override def resolve(implicit ast: Model): Option[TypeDef] = ast.definitions.find(_.name == name) match {
      case Some(t: TypeDef) => Some(t)
      case _ => None
    }
  }

  case class RelativeSchemaFile(file: String, override val meta: TypeMeta) extends Reference(file, meta)

  case class EmbeddedSchema(file: String, ref: Reference, override val meta: TypeMeta) extends Reference(file, meta)

  object Reference {
    def apply(url: String, meta: TypeMeta = None): Reference = url.indexOf('#') match {
      case 0 => ReferenceObject(url.tail, meta)
      case i if i < 0 => RelativeSchemaFile(url, meta)
      case i if i > 0 =>
        val (filePart, urlPart) = url.splitAt(i)
        EmbeddedSchema(filePart, apply(urlPart, meta), meta)
    }
  }

}

object Path {

  import scala.language.{implicitConversions, postfixOps}

  abstract class PathElem(val value: String) extends Expr

  case object Root extends PathElem(value = "/")

  case class Segment(override val value: String) extends PathElem(value)

  // swagger in version 2.0 only supports Play's singleComponentPathPart - should be encoded for constraint,
  case class InPathParameter(override val value: String, constraint: String, encode: Boolean = true) extends PathElem(value)

  object InPathParameter {
    val encode = true
    val constraint = """[^/]+"""
  }

  case class FullPath(value: Seq[PathElem]) extends Expr {
    def isAbsolute = value match {
      case Root :: segments => true
      case _ => false
    }
    override val toString = string { p: InPathParameter => "{" + p.value + "}" }

    val camelize = string("by/" + _.value).split("/") map { p =>
      if (p.nonEmpty) p.head.toUpper +: p.tail else p
    } mkString ""

    def string(inPath2Str: InPathParameter => String) = {
      value match {
        case Nil => ""
        case Root :: Nil => "/"
        case other => other map {
          case Root => ""
          case Segment(v) => v
          case i: InPathParameter => inPath2Str(i)
        } mkString "/"
      }
    }

  }

  object FullPath {
    def is(elements: PathElem*): FullPath = FullPath(elements.toList)
  }

  def path2path(path: String, parameters: Seq[Application.Parameter]): FullPath = {
    def path2segments(path: String, parameters: Seq[Application.Parameter]) = {
      path.trim split Root.value map {
        case seg if seg.startsWith("{") && seg.endsWith("}") =>
          val name = seg.tail.init
          parameters.find(_.name == name) map { p => InPathParameter(name, p.constraint, p.encode) }
        case seg if seg.nonEmpty =>
          Some(Segment(seg))
        case seg if seg.isEmpty =>
          Some(Root)
      }
    }
    val segments = path2segments(path, parameters).toList.flatten
    val elements = if (path.endsWith("/")) segments :+ Root else segments
    FullPath(elements)
  }

}

object Query {

  case class QueryParam(name: String, value: String) extends Expr

  case class FullQuery(values: QueryParam*) extends Expr

}

object Application {

  // Play definition
  case class Parameter(name: String, typeName: Domain.Type,
                       fixed: Option[String], default: Option[String],
                       constraint: String, encode: Boolean) extends Expr with Positional

  case class HandlerCall(packageName: String, controller: String, instantiate: Boolean, method: String,
    queryParameters: Seq[Parameter], pathParameters: Seq[Parameter], bodyParameters: Seq[Parameter]) {
    val parameters = queryParameters ++ pathParameters
    val allParameters = parameters ++ bodyParameters
  }

  case class ApiCall(
                      verb: Http.Verb,
                      path: Path.FullPath,
                      handler: HandlerCall,
                      mimeIn: MimeType,
                      mimeOut: MimeType,
                      errorMapping: Map[String, Seq[Class[Exception]]],
                      resultType: Type,
                      successStatus: Int
                      )

  case class Model(calls: Seq[ApiCall], definitions: Iterable[Domain.Type])

}


// TODO probably additional parameters will be needed for definitions and inline objects
object ValidationConverter {
  def toValidations(p: CommonProperties):Seq[String] = p.`type` match {
    case PrimitiveType.STRING =>
      val emailConstraint: Option[String] = if ("email" == p.format) Some("emailAddress") else None
      val stringConstraints: Seq[String] = Seq(
        ifNot0(p.maxLength, s"maxLength(${p.maxLength})"),
        ifNot0(p.minLength, s"minLength(${p.minLength})"),
        Option(p.pattern) map { p => s"""pattern("$p".r)""" },
        emailConstraint
      ).flatten
      stringConstraints
    case PrimitiveType.NUMBER | PrimitiveType.INTEGER =>
      val strictMax = Option(p.exclusiveMaximum).getOrElse(false)
      val strictMin = Option(p.exclusiveMinimum).getOrElse(false)
      val numberConstraints = Seq(
        ifNot0(p.maximum.toInt, s"max(${p.maximum.toInt}, $strictMax)"),
        ifNot0(p.minimum.toInt, s"min(${p.minimum.toInt}, $strictMin)"),
        ifNot0(p.multipleOf, s"multipleOf(${p.multipleOf})")
      ).flatten
      numberConstraints
    case PrimitiveType.ARRAY =>
      // TODO these are not implemented in PlayValidations yet
      val arrayConstraints = Seq(
        Option(p.maxItems).map { p => s"maxItems($p)" },
        Option(p.minItems).map { p => s"minItems($p)" },
        Option(p.uniqueItems)map { p => s"uniqueItems($p)" }
      ).flatten
      Seq.empty[String]
    // TODO implement objects and other types
    case _ => Seq.empty[String]
  }

  def ifNot0(check:Int, result: String): Option[String] = if (check != 0) Some(result) else None
}
