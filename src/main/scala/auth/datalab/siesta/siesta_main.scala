package auth.datalab.siesta

import auth.datalab.siesta.CommandLineParser.{Config, ParsingArguments}
import auth.datalab.siesta.Pipeline.{SiestaPipeline, SiestaStreamingPipeline}
import org.apache.log4j.{Level, Logger}


/**
 * This is the main class. It is responsible to read the command line arguments using the
 * [[CommandLineParser.ParsingArguments]] function. Then based on the defined system, pass the configuration object and
 * executes the corresponding pipeline:
 * - "signatures" => [[Singatures.Signatures]]
 * - "set-containment => [[SetContainment.SetContainment]]
 * - "siesta" -> [[Pipeline.SiestaPipeline]] (default)
 */
object siesta_main {


  def main(args: Array[String]): Unit = {
    val conf: Option[Config] = ParsingArguments.parseArguments(args)

    var config: Config = null
    if (conf.isEmpty) {
      System.exit(2)
    } else {
      config = conf.get
    }

    if (config.system == "streaming") {
      SiestaStreamingPipeline.execute(config)
    } else {
      SiestaPipeline.execute(config)
    }
  }

}
