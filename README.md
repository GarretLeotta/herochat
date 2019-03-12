Herochat - Serverless Voice Chat
================================

Herochat is a peer to peer Voice over Internet Application. You can use it to easily talk to your friends over the internet without depending on an external server. 

Herochat has been tested on Windows 10 computers only.


## Building


~~~~
sbt compile
sbt universal:packageBin
~~~~

Sbt will produce a zip file containing a batch script "main.bat". Run the script to run Herochat.


## Dependencies


* Scopus - Scala interface to the Opus codec
  - https://github.com/davidmweber/scopus
  - My fork:
  - https://github.com/GarretLeotta/scopus

* GHook - Global keyboard & Mouse hooks for Scala
  - https://github.com/GarretLeotta/GHook


## License


MIT License
