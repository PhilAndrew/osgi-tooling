package wav.devtools.sbt.karaf.packaging

import java.io._
import java.net.{URI, URL}
import java.security.MessageDigest
import java.util.Formatter
import java.util.jar.{JarInputStream, Manifest => JManifest}
import javax.xml.transform.stream.{StreamResult, StreamSource}
import javax.xml.transform.{OutputKeys, TransformerFactory}
import javax.xml.validation.SchemaFactory
import org.apache.karaf.util.maven.Parser.pathFromMaven

import org.apache.commons.lang3.text.StrSubstitutor
import org.rauschig.jarchivelib._
import sbt.{IO, MavenRepository}

import scala.collection.JavaConversions._
import scala.util.Try
import scala.xml._

private[packaging] object Util {

  def injectProperties(filePath: String, properties: Map[String, String]): String =
    StrSubstitutor.replace(io.Source.fromFile(filePath).mkString, properties)

  def formatXml(inFile: String): String = {
    val transformer = TransformerFactory.newInstance.newTransformer
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
    val result = new StreamResult(new StringWriter())
    transformer.transform(new StreamSource(inFile), result)
    result.getWriter.toString
  }

  def validateXml(xmlFile: String, xsdFile: InputStream) {
    val schemaLang = "http://www.w3.org/2001/XMLSchema"
    val factory = SchemaFactory.newInstance(schemaLang)
    val schema = factory.newSchema(new StreamSource(xsdFile))
    val validator = schema.newValidator()
    validator.validate(new StreamSource(xmlFile))
  }

  def write(target: File, xsd: String, elems: Elem, source: Option[(File, Map[String, String])] = None): File = {
    if (target.exists) target.delete
    source.foreach { t =>
      val (f, props) = t
      if (f.exists) IO.write(target, injectProperties(f.getCanonicalPath, props))
    }
    if (!target.getParentFile.exists) target.getParentFile.mkdirs
    XML.save(target.getCanonicalPath, elems, "UTF-8", true, null)
    val formatted = formatXml(target.getCanonicalPath)
    IO.write(target, formatted)
    validateXml(target.getCanonicalPath, this.getClass.getClassLoader.getResourceAsStream(xsd))
    target
  }

  def setAttrs(e: Elem, attrs: Map[String, Option[String]]): Elem =
    e.copy(attributes = attrs.collect {
      case ((name, Some(value))) => Attribute(None, name, Text(value.toString), Null)
    }.fold(Null)((soFar, attr) => soFar append attr))

  def getJarManifest(path: String): JManifest = {
    val is = new FileInputStream(path)
    val jar = new JarInputStream(is)
    jar.getManifest
  }

  def unpack(archive: File, outDir: File, overwrite: Boolean = false): Unit = {
    require(archive.exists(), s"$archive not found.")
    if (overwrite && outDir.exists()) outDir.delete()
    if (!outDir.exists()) {
      val archiver: Archiver =
        if (archive.getName.endsWith(".tar.gz")) {
          ArchiverFactory.createArchiver(ArchiveFormat.TAR, CompressionType.GZIP)
        }
        else if (archive.getName.endsWith(".zip")) {
          ArchiverFactory.createArchiver(ArchiveFormat.ZIP)
        }
        else {
          sys.error(s"Unknown format for $archive")
          ???
        }
      archiver.extract(archive, outDir)
    }
  }

  /** A naive downloader that checks the shasum  **/

  def calculateSha1(f: File): String = {
    val md = MessageDigest.getInstance("SHA1")
    val fis = new FileInputStream(f)
    val buf = new Array[Byte](1024)
    var nread = fis.read(buf)
    while (nread != -1) {
      md.update(buf, 0, nread)
      nread = fis.read(buf)
    }
    md.digest().map(0xFF & _).map { "%02x".format(_) }.foldLeft("") { _ + _ }
  }

  private def tryDownload(source: URL, target: File): Boolean =
    IO.withTemporaryDirectory { dir =>
      println(s"Trying $source")
      val targetSha1 = new File(target.getParentFile, target.getName + ".sha1")
      val sourceSha1 = new URL(source.toString + ".sha1")
      val temp = new File(dir, target.getName)
      val downloaded = Try(IO.download(source, temp)).isSuccess
      val tempSha1 = new File(dir, target.getName + ".sha1")
      def downloadedSha1 = Try(IO.download(sourceSha1, tempSha1)).isSuccess
      if (!downloaded) false
      else if (!downloadedSha1 || matchesShasum(source.toURI, temp, tempSha1)) {
        IO.copyFile(temp, target)
        IO.copyFile(tempSha1, targetSha1)
        true
      } else false
    }

  def matchesShasum(source: URI, f: File, sha1: File): Boolean = {
    val actual = calculateSha1(f)
    val expected = io.Source.fromFile(sha1).getLines.mkString
    println(s"""|SHA1: $source
                |      actual:   $actual
                |      expected: $expected
                """.stripMargin)
    actual == expected
  }

  def downloadMavenArtifact(source: URI, localRepo: File, resolvers: Seq[MavenRepository] = Seq.empty): Option[File] = {
    assert(source.getScheme.startsWith("mvn"))
    val path = pathFromMaven(source.toString)
    val target = new File(localRepo, path)
    val targetSha1 = new File(localRepo, path + ".sha1")
    if (!target.exists()) {
      // true > false, so .sortBy(!_.isCache) will select cache repos first.
      val result = resolvers.sortBy(!_.isCache)
        .map(r => tryDownload(new URL(s"${r.root}$path"), target))
        .headOption
      if (result.isDefined) {
        println(s"Downloaded $source to $target")
        Some(target)
      } else None
    }
    else if (targetSha1.exists() && matchesShasum(source, target, targetSha1)) {
      println(s"Using cached file $target for $source")
      Some(target)
    }
    else None
  }

  def download(source: URI, target: File): Boolean =
    source.getScheme match {
      case "file" =>
        val targetSha1 = new File(target.getParentFile, target.getName + ".sha1")
        targetSha1.exists() && matchesShasum(source, target, targetSha1) ||
        target.exists()
      case "http" | "https" =>
        tryDownload(source.toURL, target)
      case _: String => false
    }

}


