package auth.datalab.siesta.CommandLineParser

import scopt.{OParser, OParserBuilder}

import scala.language.postfixOps

case class Config(
                   database: String = "s3",
                   mode: String = "siesta",
                   filename: String = "synthetic",
                   log_name: String = "synthetic",
                   delete_all: Boolean = false,
                   delete_previous: Boolean = false,
                   join: Boolean = false,
                   split_every_days: Int = 30,
                   lookback_days: Int = 30,
                   //                   algorithm: String = "indexing",
                   traces: Int = 100,
                   event_types: Int = 10,
                   length_min: Int = 10,
                   length_max: Int = 90,
                   iterations: Int = -1,
                   n: Int = 2,
                   k: Int = -1

                   //                     kwargs: Map[String, String] = Map()
                 )

object ParsingArguments {
  val builder: OParserBuilder[Config] = OParser.builder[Config]
  val parser: OParser[Unit, Config] = {
    import builder._
    OParser.sequence(
      programName("preprocess.jar"),
      head("SIESTA preprocess"),
      opt[String]('d', "database")
        .action((x, c) => c.copy(database = x))
        .valueName("<database>")
        .validate(x => {
          if (x.equals("s3") || x.equals("cassandra")) {
            success
          } else {
            failure("Supported values for <database> are s3 or cassandra")
          }
        })
        .text("Database refers to the database that will be used to store the index"),
      opt[String]('m', "mode")
        .action((x, c) => c.copy(mode = x))
        .valueName("<mode>")
        .validate(x => {
          if (x.equals("positions") || x.equals("timestamps")) {
            success
          } else {
            failure("Value <mode> must be either positions or timestamps")
          }
        })
        .text("Mode is the name of the method used"),
      opt[String]('f', "file")
        .action((x, c) => c.copy(filename = x))
        .valueName("<file>")
        .text("If not set will generate artificially data"),
      opt[String]("logname")
        .action((x, c) => c.copy(log_name = x))
        .valueName("<logname>")
        .text("Specify the name of the index to be created. This is used in case of incremental preprocessing"),
      opt[Unit]("delete_all")
        .action((_, c) => c.copy(delete_all = true))
        .text("cleans all tables in the keyspace"),
      opt[Unit]("delete_prev")
        .action((_, c) => c.copy(delete_previous = true))
        .text("removes all the tables generated by a previous execution of this method"),
      opt[Unit]("join")
        .action((_, c) => c.copy(join = true))
        .text("merges the traces with the already indexed ones"),
      opt[Int]("lookback")
        .action((x, c) => c.copy(lookback_days = x))
        .text("How many days will look back for completions (default=30)")
        .validate(x => {
          if (x > 0) success else failure("Value <lookback> has to be a positive number")
        }),
      opt[Int]('s', "split_every_days")
        .valueName("s")
        .action((x, c) => c.copy(split_every_days = x))
        .text("Split the inverted index every s days (default=30)")
        .validate(x => {
          if (x > 0) success else failure("Value <s> has to be a positive number")
        }),
      opt[Int]("n")
        .valueName("<n>")
        .text("Key size (n-tuples)")
        .validate(x => {
          if (x > 0 && x < 4) success else failure("Value <n> has to be between 1 and 3")
        })
        .action((x, c) => c.copy(n = x)),
      opt[Int]('i', "iterations")
        .valueName("<i>")
        .text("# iterations, if not set it will be determined by the system")
        .validate(x => if (x > 0) success else failure("Value <i> has to be a positive integer"))
        .action((x, c) => c.copy(iterations = x)),
      note(sys.props("line.separator") + "The parameters below are used if the file was not set and data will be randomly generated"),
      opt[Int]('t', "traces")
        .valueName("<#traces>")
        .validate(x => {
          if (x > 0) success else failure("Value <#traces> must be positive")
        })
        .action((x, c) => c.copy(traces = x)),
      opt[Int]('e', "event_types")
        .valueName("<#event_types>")
        .validate(x => {
          if (x > 0) success else failure("Value <#event_types> must be positive")
        })
        .action((x, c) => c.copy(event_types = x)),
      opt[Int]("lmin")
        .valueName("<min length>")
        .action((x, c) => c.copy(length_min = x)),
      opt[Int]("lmax")
        .valueName("<max length>")
        .action((x, c) => c.copy(length_max = x)),
      help("help").text("prints this usage text")
    )
  }

  def parseArguments(args: Array[String]): Option[Config] = {
    OParser.parse(parser, args, Config()) match {
      case Some(config) =>
        Some(config)
      case _ =>
        println("There was an error with the arguments. Use 'help' to display full list of arguments")
        null
    }
  }


}
