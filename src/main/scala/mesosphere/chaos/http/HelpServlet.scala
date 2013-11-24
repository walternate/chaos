package mesosphere.chaos.http

import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}
import javax.inject.Inject
import com.google.inject.Injector
import scala.collection.JavaConverters._
import javax.ws.rs._
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer
import scala.io.Source
import net.liftweb.markdown.ActuariusTransformer
import java.lang.reflect.Method
import scala.collection.{SortedSet, mutable}
import scala.Some
import java.net.URLDecoder

/**
 * @author Tobi Knaup
 */

class HelpServlet @Inject()(injector: Injector, container: GuiceContainer) extends HttpServlet {

  // TODO configure path
  val basePath = "/help"
  val pathPattern = s"$basePath/([A-Z]+)(/.+)".r
  lazy val pathMap = makePathMap()

  val htmlHeader =
    """
<!DOCTYPE html>
<html lang="en-us">
  <head>
    <title>Logger Config</title>
    <style>
    body {
      font-family: Helvetica, Arial, "Lucida Grande", sans-serif;
      font-weight: 400;
    }
    </style>
  </head>

  <body>
    """
  val htmlFooter =
    """
  </body>
</html>
    """

  val contentType = "Content-Type"
  val textHtml = "text/html; charset=utf-8"

  override def doGet(req: HttpServletRequest, resp: HttpServletResponse) = {
    req.getRequestURI match {
      case `basePath` => all(req, resp)
      case pathPattern(method, path) => handleMethod(method, path, req, resp)
      case _ => resp.setStatus(HttpServletResponse.SC_NOT_FOUND)
    }
  }

  private def all(req: HttpServletRequest, resp: HttpServletResponse) {
    resp.setStatus(HttpServletResponse.SC_OK)
    resp.addHeader(contentType, textHtml)
    val writer = resp.getWriter
    try {
      writer.print(htmlHeader)
      writer.println("<h2>Help</h2>")
      writer.println("<table>")
      for (key <- pathMap.keySet.to[SortedSet]) {
        val method = pathMap(key)
        writer.println(s"""
      <tr>
        <td>${key._2}</td>
        <td><a href="$basePath/${key._2}${key._1}">${key._1}</a></td>
        <td>${method.getDeclaringClass.getName}#${method.getName}()</td>
      </tr>""")
      }
      writer.println("</table>")
      writer.print(htmlFooter)
    }
    finally {
      writer.close()
    }
  }

  private def handleMethod(httpMethod: String, path: String, req: HttpServletRequest, resp: HttpServletResponse) = {
    val decodedPath = URLDecoder.decode(path, Option(req.getCharacterEncoding).getOrElse("UTF8"))
    val writer = resp.getWriter
    try {
      pathMap.get((decodedPath, httpMethod)) match {
        case Some(methodHandle) => {
          val klass = methodHandle.getDeclaringClass
          val resourceName = s"${klass.getSimpleName}_${methodHandle.getName}.md"

          Option(klass.getResource(resourceName)) match {
            case Some(url) => {
              resp.setStatus(HttpServletResponse.SC_OK)
              resp.addHeader(contentType, textHtml)
              val transformer = new ActuariusTransformer
              val markdown = transformer(Source.fromURL(url).mkString)
              writer.print(htmlHeader)
              writer.print(markdown)
              writer.print(htmlFooter)
            }
            case None => {
              resp.setStatus(HttpServletResponse.SC_NOT_FOUND)
              writer.println(s"No documentation found. Create a file named $resourceName in the resources folder " +
                s"for class ${klass.getSimpleName} to add it.")
            }
          }
        }
        case None => {
          resp.setStatus(HttpServletResponse.SC_NOT_FOUND)
          writer.println(s"No resource defined for $httpMethod $path")
        }
      }
    }
    finally {
      writer.close()
    }
  }

  private def makePathMap(): Map[(String, String), Method] = {
    val pathMap = new mutable.HashMap[(String, String), Method]()

    def handleClass(pathPrefix: String, klass: Class[_]) {
      for (method <- klass.getDeclaredMethods) {
        var httpMethod: Option[String] = None
        var methodPath = ""

        for (ann <- method.getAnnotations) {
          ann match {
            case m: GET => httpMethod = Some("GET")
            case m: POST => httpMethod = Some("POST")
            case m: PUT => httpMethod = Some("PUT")
            case m: DELETE => httpMethod = Some("DELETE")
            case m: HEAD => httpMethod = Some("HEAD")
            case m: OPTIONS => httpMethod = Some("OPTIONS")
            case pathAnn: Path => methodPath = s"/${pathAnn.value}"
            case _ =>
          }
        }

        val path = Option(klass.getAnnotation(classOf[Path])) match {
          case Some(ann) => s"$pathPrefix/${ann.value}$methodPath"
          case None => s"$pathPrefix$methodPath"
        }

        if (httpMethod.isDefined) {
          pathMap((path, httpMethod.get)) = method
        } else if (methodPath.nonEmpty) {
          // Sub-resources have a Path annotation but no HTTP method
          handleClass(path, method.getReturnType)
        }
      }
    }

    for (key <- injector.getAllBindings.keySet.asScala) {
      val klass = key.getTypeLiteral.getRawType
      if (klass.isAnnotationPresent(classOf[Path])) {
        handleClass(getServletContext.getContextPath, klass)
      }
    }
    pathMap.toMap
  }
}