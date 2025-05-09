package cp.serverPr

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import org.slf4j.LoggerFactory

object Routes {
  private val logger = LoggerFactory.getLogger(getClass)
  private val state = new ServerState()

  val routes: IO[HttpRoutes[IO]] =
   IO{HttpRoutes.of[IO] {

     // React to a "status" request
     case GET -> Root / "status" =>
       Ok(state.toHtml)
         .map(addCORSHeaders)
         .map(_.withContentType(org.http4s.headers.`Content-Type`(MediaType.text.html)))


     // React to a "run-process" request
     case req@GET -> Root / "run-process" =>
       val cmdOpt = req.uri.query.params.get("cmd")
       val userIp = req.remoteAddr.getOrElse("unknown")

       //// printing to the terminal instead of a logging file
       //println(">>> got run-process!")
       //println(s">>> Cmd: ${cmdOpt}")
       //println(s">>> userIP: $userIp")

       cmdOpt match {
         case Some(cmd) =>
           Ok(runProcess(cmd, userIp.toString))
             .map(addCORSHeaders)

         case None =>
           BadRequest("⚠️ Command not provided. Use /run-process?cmd=<your_command>")
             .map(addCORSHeaders)
       }
   }}


  /** Run a given process and collect its output. */
  private def runProcess(cmd: String, userIp: String): String = {
    state.counter += 1
    logger.info(s"🔹 Starting process (${state.counter}) for user $userIp: $cmd")

    // TODO:Run process here. The `Thread.sleep` should be removed.
    Thread.sleep(1000)
    val output: String = s"[${state.counter}] Result from running $cmd for user $userIp"

    output
  }

  /** Add extra headers, required by the client. */
  def addCORSHeaders(response: Response[IO]): Response[IO] = {
    response.putHeaders(
      "Access-Control-Allow-Origin" -> "*",
      "Access-Control-Allow-Methods" -> "GET, POST, PUT, DELETE, OPTIONS",
      "Access-Control-Allow-Headers" -> "Content-Type, Authorization",
      "Access-Control-Allow-Credentials" -> "true"
    )
  }
}


