package de.zalando.swagger

import java.io.{BufferedWriter, File, FileWriter}

import de.zalando.apifirst.Domain
import de.zalando.apifirst.Domain._
import de.zalando.swagger.model.PrimitiveType
import org.scalatest.{FunSpec, MustMatchers}

import scala.language.implicitConversions

class SchemaConverterTest extends FunSpec with MustMatchers {

  describe("Schema Converter") {

    it("should convert primitive schema") {
      val schema = new model.Schema("email", null, 0, 0, false, 0, false, 0, 0, null, 0, 0, false, null, null, PrimitiveType.STRING, Nil, null, null, null, null, null, null, null, false, null, null, null, 0, 0)
      val result = SchemaConverter.schema2Type(schema, "/definitions/type_name")
      result mustBe Domain.Str(Some("email"),TypeMeta(Some("email")))
    }

    it("should convert simple object") {
      val internalSchema = new model.Schema(null, null, 0, 0, false, 0, false, 0, 0, null, 0, 0, false, null, null, PrimitiveType.STRING, Nil, null, null, null, null, null, null, null, false, null, null, null, 0, 0)
      val props = Map("name" -> internalSchema)
      val schema = new model.Schema(null, null, 0, 0, false, 0, false, 0, 0, null, 0, 0, false, null, null, PrimitiveType.OBJECT, List("name"), null, null, null, null, props, null, null, false, null, null, null, 0, 0)
      val result = SchemaConverter.schema2Type(schema, "/definitions/type_name")
      result mustBe TypeDef("/definitions/type_name", Seq(Field("/definitions/type_name/name", Str(meta = None), TypeMeta(None))), Nil, TypeMeta(None))
    }

    it("should convert a complex object") {
      val internalSchema = new model.Schema(null, null, 0, 0, false, 0, false, 0, 0, null, 0, 0, false, null, null, PrimitiveType.STRING, Nil, null, null, null, null, null, null, null, false, null, null, null, 0, 0)
      val internalProps = Map("name" -> internalSchema)
      val properties = new model.Schema(null, null, 0, 0, false, 0, false, 0, 0, null, 0, 0, false, null, null, PrimitiveType.OBJECT, Nil, null, null, null, null, internalProps, null, null, false, null, null, null, 0, 0)
      val mainprops = Map("properties" -> properties)
      val schema = new model.Schema(null, null, 0, 0, false, 0, false, 0, 0, null, 0, 0, false, null, null, PrimitiveType.OBJECT, List("mainprops"), null, null, null, null, mainprops, null, null, false, null, null, null, 0, 0)
      val result = SchemaConverter.schema2Type(schema, "/definitions/type_name")
      result mustBe 	TypeDef("/definitions/type_name", List(
        Field("/definitions/type_name/properties", Opt(Field("/definitions/type_name/properties",
          TypeDef("/definitions/type_name/properties", List(
            Field("/definitions/type_name/properties/name", Opt(Field("/definitions/type_name/properties/name", Str(None,TypeMeta(None)), TypeMeta(None)),TypeMeta(None)), TypeMeta(None))), List(), TypeMeta(None))
          , TypeMeta(None)),TypeMeta(None)), TypeMeta(None))), List(), TypeMeta(None))
    }

    it("should convert primitive additionalProperties") {
      val props = model.Property(PrimitiveType.STRING, null, null, null, null, null, null)
      val schema = new model.Schema(null, null, 0, 0, false, 0, false, 0, 0, null, 0, 0, false, null, null, PrimitiveType.OBJECT, Nil, null, null, null, null, null, props, null, false, null, null, null, 0, 0)
      val result = SchemaConverter.schema2Type(schema, "/definitions/type_name")
      result mustBe TypeDef(TypeName("/definitions/type_name"), List(
        Field(TypeName("/definitions/type_name/additionalProperties"),
          CatchAll(Field(TypeName("/definitions/type_name/additionalProperties"), Str(None,TypeMeta(None)),
            TypeMeta(None)),TypeMeta(None)), TypeMeta(Some("Catch-All property")))), List(), TypeMeta(None))

    }

    it("should convert complex additionalProperties") {
      val props = model.Property(null, "#/definitions/ComplexModel", null, null, null, null, null)
      val schema = new model.Schema(null, null, 0, 0, false, 0, false, 0, 0, null, 0, 0, false, null, null, PrimitiveType.OBJECT, Nil, null, null, null, null, null, props, null, false, null, null, null, 0, 0)
      val result = SchemaConverter.schema2Type(schema, "/definitions/")
      result mustBe TypeDef(TypeName("/definitions/"), List(
        Field(TypeName("/definitions//additionalProperties"), CatchAll(Field(TypeName("/definitions//additionalProperties"), ReferenceObject(TypeName("/definitions/ComplexModel"), TypeMeta(None)), TypeMeta(None)),TypeMeta(None)), TypeMeta(Some("Catch-All property")))), List(), TypeMeta(None))

    }

  }

  implicit def fileFromText(text: String): File = {
    val temp = File.createTempFile("asdf", "test")
    temp.deleteOnExit()
    val out = new BufferedWriter(new FileWriter(temp))
    val txt = text.split("\n").map("  " + _).mkString("\n")
    out.write("definitions:\n" + text)
    out.close()
    temp
  }
}
