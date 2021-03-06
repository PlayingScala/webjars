package utils

import play.api.Configuration
import play.api.http.Status
import play.api.libs.json.{JsValue, Json}
import play.api.libs.ws.{WSResponse, WSAPI, WSAuthScheme, WSRequestHolder}
import play.api.mvc.Results.EmptyContent

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class BinTray(implicit ec: ExecutionContext, ws: WSAPI, config: Configuration) {

  val BASE_URL = "https://bintray.com/api/v1"

  val username = config.getString("bintray.username").get
  val password = config.getString("bintray.password").get
  val gpgPassphrase = config.getString("bintray.gpg.passphrase").get

  val ossUsername = config.getString("oss.username").get
  val ossPassword = config.getString("oss.password").get


  def ws(path: String): WSRequestHolder = {
    ws
      .url(BASE_URL + path)
      .withAuth(username, password, WSAuthScheme.BASIC)
  }

  def error(response: WSResponse): String = {
    Try {
      (response.json \ "message").as[String]
    } getOrElse response.body
  }

  def createPackage(subject: String, repo: String, name: String, desc: String, labels: Seq[String], licenses: Seq[String], vcsUrl: String, websiteUrl: Option[String], issueTrackerUrl: Option[String], githubRepo: Option[String]): Future[JsValue] = {

    val json = Json.obj(
      "name" -> name,
      "desc" -> desc,
      "labels" -> labels,
      "licenses" -> licenses,
      "vcs_url" -> vcsUrl,
      "website_url" -> websiteUrl,
      "issue_tracker_url" -> issueTrackerUrl,
      "github_repo" -> githubRepo
    )

    ws(s"/packages/$subject/$repo").post(json).flatMap { response =>
      response.status match {
        case Status.CREATED =>
          Future.successful(response.json)
        case _ =>
          Future.failed(new Exception(error(response)))
      }
    }
  }

  def getPackage(subject: String, repo: String, name: String): Future[JsValue] = {
    ws(s"/packages/$subject/$repo/$name").get().flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.json)
        case _ => Future.failed(new Exception(s"Package not found: $subject $repo $name"))
      }
    }
  }

  def getOrCreatePackage(subject: String, repo: String, name: String, desc: String, labels: Seq[String], licenses: Seq[String], vcsUrl: String, websiteUrl: Option[String], issueTrackerUrl: Option[String], githubRepo: Option[String]): Future[JsValue] = {
    getPackage(subject, repo, name).recoverWith {
      case e: Exception =>
        createPackage(subject, repo, name, desc, labels, licenses, vcsUrl, websiteUrl, issueTrackerUrl, githubRepo)
    }
  }

  def deletePackage(subject: String, repo: String, name: String): Future[JsValue] = {
    ws(s"/packages/$subject/$repo/$name").delete().flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.json)
        case _ => Future.failed(new Exception(error(response)))
      }
    }
  }

  def createVersion(subject: String, repo: String, packageName: String, name: String, description: String, vcsTag: Option[String] = None): Future[JsValue] = {
    val json = Json.obj(
      "name" -> name,
      "desc" -> description,
      "vcs_tag" -> vcsTag
    )

    ws(s"/packages/$subject/$repo/$packageName/versions").post(json).flatMap { response =>
      response.status match {
        case Status.CREATED =>
          Future.successful(response.json)
        case _ =>
          Future.failed(new Exception(error(response)))
      }
    }
  }

  def uploadMavenArtifact(subject: String, repo: String, packageName: String, path: String, jarBytes: Array[Byte]): Future[JsValue] = {
    ws(s"/maven/$subject/$repo/$packageName/$path").withHeaders("X-GPG-PASSPHRASE" -> gpgPassphrase).put(jarBytes).flatMap { response =>
      response.status match {
        case Status.CREATED => Future.successful(response.json)
        case _ => Future.failed(new Exception(error(response)))
      }
    }
  }

  def publishVersion(subject: String, repo: String, packageName: String, version: String): Future[JsValue] = {
    ws(s"/content/$subject/$repo/$packageName/$version/publish").post(EmptyContent()).flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.json)
        case _ => Future.failed(new Exception(error(response)))
      }
    }
  }

  def signVersion(subject: String, repo: String, packageName: String, version: String): Future[JsValue] = {
    ws(s"/gpg/$subject/$repo/$packageName/versions/$version").withHeaders("X-GPG-PASSPHRASE" -> gpgPassphrase).post(EmptyContent()).flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.json)
        case _ => Future.failed(new Exception(error(response)))
      }
    }
  }

  def syncToMavenCentral(subject: String, repo: String, packageName: String, version:String): Future[JsValue] = {

    val json = Json.obj(
      "username" -> ossUsername,
      "password" -> ossPassword
    )

    ws(s"/maven_central_sync/$subject/$repo/$packageName/versions/$version").post(json).flatMap { response =>
      response.status match {
        case Status.OK => Future.successful(response.json)
        case _ => Future.failed(new Exception(error(response)))
      }
    }
  }

  // this returns a Future as a way to deal with failure
  // a Try would be better but wouldn't compose in a for comprehension with Futures
  def convertLicenses(licenses: Seq[String]): Future[Seq[String]] = {
    Future.sequence {
      licenses.map { license =>

        val maybeAcceptableLicense = availableLicenses.find { availableLicense =>
          license.replace(" ", "").replace("-", "").toLowerCase == availableLicense.replace(" ", "").replace("-", "").toLowerCase
        }

        maybeAcceptableLicense.fold(Future.failed[String](new Exception(s"License $license is not acceptable on BinTray")))(Future.successful)
      }
    }
  }

  // from: https://bintray.com/docs/api/
  val availableLicenses = Set("AFL-3.0", "AGPL-V3", "Apache-1.0", "Apache-1.1", "Apache-2.0", "APL-1.0", "APSL-2.0", "Artistic-License-2.0", "Attribution", "Bouncy-Castle", "BSD", "BSD 2-Clause", "BSD 3-Clause", "BSL-1.0", "CA-TOSL-1.1", "CC0-1.0", "CDDL-1.0", "Codehaus", "CPAL-1.0", "CPL-1.0", "CPOL-1.02", "CUAOFFICE-1.0", "Day", "Day-Addendum", "ECL2", "Eiffel-2.0", "Entessa-1.0", "EPL-1.0", "EUDATAGRID", "EUPL-1.1", "Fair", "Frameworx-1.0", "Go", "GPL-2.0", "GPL-2.0+CE", "GPL-3.0", "Historical", "HSQLDB", "IBMPL-1.0", "IPAFont-1.0", "ISC", "IU-Extreme-1.1.1", "JA-SIG", "JSON", "JTidy", "LGPL-2.1", "LGPL-3.0", "Lucent-1.02", "MirOS", "MIT", "Motosoto-0.9.1", "Mozilla-1.1", "MPL-2.0", "MS-PL", "MS-RL", "Multics", "NASA-1.3", "NAUMEN", "Nethack", "Nokia-1.0a", "NOSL-3.0", "NTP", "NUnit-2.6.3", "NUnit-Test-Adapter-2.6.3", "OCLC-2.0", "Openfont-1.1", "Opengroup", "OpenSSL", "OSL-3.0", "PHP-3.0", "PostgreSQL", "Public Domain", "Public Domain - SUN", "PythonPL", "PythonSoftFoundation", "QTPL-1.0", "Real-1.0", "RicohPL", "RPL-1.5", "Scala", "SimPL-2.0", "Sleepycat", "SUNPublic-1.0", "Sybase-1.0", "TMate", "Unlicense", "UoI-NCSA", "VovidaPL-1.0", "W3C", "WTFPL", "wxWindows", "Xnet", "ZLIB", "ZPL-2.0")

}

object BinTray {
  def apply(implicit ec: ExecutionContext, ws: WSAPI, config: Configuration) = new BinTray()
}