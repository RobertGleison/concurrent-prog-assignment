package cp.serverPr

import cats.effect.IOApp

object Main extends IOApp.Simple {
  val run = Server.run
}