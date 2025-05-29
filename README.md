lauch.json:

{
  "version": "0.2.0",
  "configurations": [
    {
      "type": "java",
      "request": "launch",
      "name": "Debug HTTP4s Server",
      "mainClass": "cp.serverPr.Main",
      "projectName": "Server",
      "vmArgs": ["-Xms512M", "-Xmx1024M", "-Xss2M"],
      "env": {
        "LOG_LEVEL": "DEBUG"
      }
    }
  ]
}



