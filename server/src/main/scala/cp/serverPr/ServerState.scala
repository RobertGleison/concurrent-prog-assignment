package cp.serverPr

class ServerState() {
  // TODO: extend the state of the server

  var counter = 0;

  def toHtml: String =
    s"<p><strong>counter:</strong> $counter</p>"
}
