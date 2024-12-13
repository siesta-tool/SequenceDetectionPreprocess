package auth.datalab.siesta.BusinessLogic.Metadata

import auth.datalab.siesta.CommandLineParser.Config
import org.apache.spark.sql.{DataFrame, Row, SparkSession}
import org.apache.spark.sql.types.{StringType, StructField, StructType}

import scala.util.Try

/**
 * This class facilitates the initialization and loading of the Metadata object
 */
object SetMetadata {

  /**
   * Create a new metadata object based on the given configuration object (passed from the [[auth.datalab.siesta.siesta_main]].
   * This method is called if there are no previous records indexed, i.e. it is a new log database
   * @param config The configuration file with the command line parameters
   * @return A new Metadata object
   */
  def initialize_metadata(config: Config): MetaData = {
    MetaData(traces = 0, events = 0, pairs = 0L, lookback = config.lookback_days,has_previous_stored = false,
      filename = config.filename, streaming = (config.system == "streaming"),log_name = config.log_name, mode = config.mode, compression = config.compression,
      last_declare_mined = "")
  }

  /**
   * Loads the previously stored metadata from the database into a Metadata object
   * @param metaDataObj The laoded metadata from the Database
   * @return The metadata object
   */
  def load_metadata(metaDataObj:DataFrame):MetaData = {
    metaDataObj.collect().map(x => {
      val last_declare_mined = Try(x.getAs[String]("last_declare_mined")).getOrElse("")
      MetaData(traces = x.getAs("traces"),
        events = x.getAs("events"),
        pairs = x.getAs("pairs"),
        lookback = x.getAs("lookback"),
        has_previous_stored = true,
        filename = x.getAs("filename"),
        streaming = x.getAs("streaming"),
        log_name = x.getAs("log_name"), mode = x.getAs("mode"),
        compression = x.getAs("compression"),
        last_declare_mined = last_declare_mined)}).head
  }

  def load_metadata_delta(metaDataObj:DataFrame):MetaData ={

    def get_key(a: Array[Row], key: String): String = {
      a.filter(y => y.getAs("key") == key).head.getAs[String]("value")
    }

    val a = metaDataObj.collect()
    MetaData(traces = get_key(a,"traces").toLong,
      events = get_key(a,"events").toLong,
      pairs = get_key(a,"pairs").toLong,
      lookback = get_key(a,"lookback").toInt,
      has_previous_stored = get_key(a,"has_previous_stored").toBoolean,
      filename = get_key(a,"filename"),
      streaming = get_key(a,"streaming").toBoolean,
      log_name = get_key(a,"log_name"),
      mode = get_key(a,"mode"),
      compression = get_key(a,"compression"),
      last_declare_mined = get_key(a,"last_declare_mined"))
  }

  def transformToDF(metaData: MetaData):DataFrame ={
    // Define the schema of the DataFrame
    val schema = StructType(Array(
      StructField("key", StringType, nullable = false),
      StructField("value", StringType, nullable = false)
    ))

    // Convert each field of MetaData into a Row
    val rows = Seq(
      Row("traces", metaData.traces.toString),
      Row("events", metaData.events.toString),
      Row("pairs", metaData.pairs.toString),
      Row("lookback", metaData.lookback.toString),
      Row("has_previous_stored", metaData.has_previous_stored.toString),
      Row("filename", metaData.filename),
      Row("streaming", metaData.streaming.toString),
      Row("log_name", metaData.log_name),
      Row("mode", metaData.mode),
      Row("compression", metaData.compression)
    )

    // Create a DataFrame from the list of rows and the schema
    val spark = SparkSession.builder().getOrCreate();
    val rdd = spark.sparkContext.parallelize(rows)
    spark.createDataFrame(rdd, schema)

  }


}
