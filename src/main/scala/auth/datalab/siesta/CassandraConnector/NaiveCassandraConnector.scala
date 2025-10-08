package auth.datalab.siesta.CassandraConnector

import auth.datalab.siesta.BusinessLogic.DBConnector.DBConnector
import auth.datalab.siesta.BusinessLogic.Metadata.{MetaData, SetMetadata}
import auth.datalab.siesta.BusinessLogic.Model.Structs.LastChecked
import auth.datalab.siesta.BusinessLogic.Model.{DetailedEvent, Event, EventTrait, Structs}
import auth.datalab.siesta.CommandLineParser.Config
import auth.datalab.siesta.Utils.Utilities
import com.datastax.spark.connector._
import org.apache.log4j.{Level, Logger}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.{DataFrame, SaveMode, SparkSession}

class NaiveCassandraConnector extends DBConnector {

  private val keyspace: String = "siesta"
  private var log_name_prefix: String = _
  private var seq_table: String = _
  private var detailed_table: String = _
  private var meta_table: String = _
  private var single_table: String = _
  private var last_checked_table: String = _
  private var index_table: String = _
  private var count_table: String = _

  private def initializeTableNames(config: Config): Unit = {
    log_name_prefix = config.log_name.replaceAll("[^a-zA-Z0-9_]", "_")
    seq_table = s"${log_name_prefix}_sequence_table"
    detailed_table = s"${log_name_prefix}_detailed_table"
    meta_table = s"${log_name_prefix}_metadata_table"
    single_table = s"${log_name_prefix}_single_table"
    last_checked_table = s"${log_name_prefix}_last_checked_table"
    index_table = s"${log_name_prefix}_index_table"
    count_table = s"${log_name_prefix}_count_table"
  }

  /**
   * Initialize Spark with Cassandra configuration
   */
  def initialize_spark(config: Config): Unit = {
    initializeTableNames(config)
    val cassandraHost = Utilities.readEnvVariable("CASSANDRA_HOST")
    val cassandraPort = Utilities.readEnvVariable("CASSANDRA_PORT")
    val cassandraUser = Utilities.readEnvVariable("CASSANDRA_USER")
    val cassandraPassword = Utilities.readEnvVariable("CASSANDRA_PASSWORD")

    val spark = SparkSession.builder()
      .appName("SIESTA Cassandra Indexing")
      .master("local[*]")
      .config("spark.cassandra.connection.host", cassandraHost)
      .config("spark.cassandra.connection.port", cassandraPort)
      .config("spark.cassandra.auth.username", cassandraUser)
      .config("spark.cassandra.auth.password", cassandraPassword)
      .config("spark.cassandra.connection.keepAliveMS", "30000")
      .config("spark.cassandra.output.consistency.level", "LOCAL_ONE")
      .config("spark.cassandra.input.consistency.level", "LOCAL_ONE")
      .config("spark.sql.extensions", "com.datastax.spark.connector.CassandraSparkExtensions")
      .getOrCreate()
  }

  /**
   * Create keyspace and tables in Cassandra
   */
  def initialize_db(config: Config): Unit = {
    val spark = SparkSession.builder().getOrCreate()
    initializeTableNames(config)

    val connector = com.datastax.spark.connector.cql.CassandraConnector(spark.sparkContext.getConf)
    connector.withSessionDo { session =>
      // Create keyspace
      session.execute(s"""
        CREATE KEYSPACE IF NOT EXISTS $keyspace
        WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 3}
      """)

      session.execute(s"USE $keyspace")

      // Drop tables if delete_previous is set
      if (config.delete_previous) {
        val tables = List(seq_table, detailed_table, meta_table, single_table,
                         last_checked_table, index_table, count_table)
        tables.foreach { table =>
          session.execute(s"DROP TABLE IF EXISTS $table")
        }
      }

      // Create sequence table
      session.execute(s"""
        CREATE TABLE IF NOT EXISTS $seq_table (
          trace_id TEXT,
          event_type TEXT,
          position INT,
          timestamp TEXT,
          PRIMARY KEY (trace_id, position)
        )
      """)

      // Create detailed table
      session.execute(s"""
        CREATE TABLE IF NOT EXISTS $detailed_table (
          trace_id TEXT,
          event_type TEXT,
          position INT,
          start_timestamp TEXT,
          end_timestamp TEXT,
          waiting_time BIGINT,
          resource TEXT,
          PRIMARY KEY (trace_id, position)
        )
      """)

      // Create metadata table
      session.execute(s"""
        CREATE TABLE IF NOT EXISTS $meta_table (
          id TEXT PRIMARY KEY,
          traces BIGINT,
          events BIGINT,
          pairs BIGINT,
          lookback INT,
          has_previous_stored BOOLEAN,
          filename TEXT,
          streaming BOOLEAN,
          log_name TEXT,
          mode TEXT,
          compression TEXT,
          start_ts TEXT,
          last_ts TEXT,
          last_declare_mined TEXT
        )
      """)

      // Create single table (partitioned by event_type for efficient queries)
      session.execute(s"""
        CREATE TABLE IF NOT EXISTS $single_table (
          event_type TEXT,
          trace_id TEXT,
          position INT,
          timestamp TEXT,
          PRIMARY KEY (event_type, trace_id, position)
        )
      """)

      // Create last_checked table
      session.execute(s"""
        CREATE TABLE IF NOT EXISTS $last_checked_table (
          event_a TEXT,
          event_b TEXT,
          trace_id TEXT,
          timestamp TEXT,
          PRIMARY KEY ((event_a, event_b), trace_id)
        )
      """)

      // Create index table (partitioned by eventA for efficient queries)
      session.execute(s"""
        CREATE TABLE IF NOT EXISTS $index_table (
          event_a TEXT,
          event_b TEXT,
          trace_id TEXT,
          timestamp_a TEXT,
          timestamp_b TEXT,
          position_a INT,
          position_b INT,
          PRIMARY KEY (event_a, trace_id, event_b)
        )
      """)

      // Create count table
      session.execute(s"""
        CREATE TABLE IF NOT EXISTS $count_table (
          event_a TEXT,
          event_b TEXT,
          sum_duration BIGINT,
          count INT,
          min_duration BIGINT,
          max_duration BIGINT,
          sum_squares DOUBLE,
          PRIMARY KEY (event_a, event_b)
        )
      """)
    }
  }

  /**
   * Get metadata from Cassandra or create new if doesn't exist
   */
  def get_metadata(config: Config): MetaData = {
    Logger.getLogger("Metadata").log(Level.INFO, s"Getting metadata")
    val start = System.currentTimeMillis()
    val spark = SparkSession.builder().getOrCreate()
    initializeTableNames(config)

    val metaDataObj = try {
      spark.read
        .format("org.apache.spark.sql.cassandra")
        .options(Map("table" -> meta_table, "keyspace" -> keyspace))
        .load()
    } catch {
      case _: Exception => null
    }

    val total = System.currentTimeMillis() - start
    Logger.getLogger("Metadata").log(Level.INFO, s"finished in ${total / 1000} seconds")

    val metaData = if (metaDataObj == null || metaDataObj.count() == 0) {
      SetMetadata.initialize_metadata(config)
    } else {
      val row = metaDataObj.first()
      MetaData(
        traces = row.getAs[Long]("traces"),
        events = row.getAs[Long]("events"),
        pairs = row.getAs[Long]("pairs"),
        lookback = row.getAs[Int]("lookback"),
        has_previous_stored = row.getAs[Boolean]("has_previous_stored"),
        filename = row.getAs[String]("filename"),
        streaming = row.getAs[Boolean]("streaming"),
        log_name = row.getAs[String]("log_name"),
        mode = row.getAs[String]("mode"),
        compression = row.getAs[String]("compression"),
        start_ts = row.getAs[String]("start_ts"),
        last_ts = row.getAs[String]("last_ts"),
        last_declare_mined = row.getAs[String]("last_declare_mined")
      )
    }
    this.write_metadata(metaData)
    metaData
  }

  /**
   * Write metadata to Cassandra
   */
  def write_metadata(metaData: MetaData): Unit = {
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._

    val df = Seq((
      "metadata",
      metaData.traces,
      metaData.events,
      metaData.pairs,
      metaData.lookback,
      metaData.has_previous_stored,
      metaData.filename,
      metaData.streaming,
      metaData.log_name,
      metaData.mode,
      metaData.compression,
      metaData.start_ts,
      metaData.last_ts,
      metaData.last_declare_mined
    )).toDF("id", "traces", "events", "pairs", "lookback", "has_previous_stored",
            "filename", "streaming", "log_name", "mode", "compression",
            "start_ts", "last_ts", "last_declare_mined")

    df.write
      .format("org.apache.spark.sql.cassandra")
      .options(Map("table" -> meta_table, "keyspace" -> keyspace, "confirm.truncate" -> "true"))
      .mode(SaveMode.Overwrite)
      .save()
  }

  /**
   * Read sequence table data
   */
  def read_sequence_table(metaData: MetaData, detailed: Boolean = false): RDD[EventTrait] = {
    val spark = SparkSession.builder().getOrCreate()
    try {
      if (detailed) {
        spark.read
          .format("org.apache.spark.sql.cassandra")
          .options(Map("table" -> detailed_table, "keyspace" -> keyspace))
          .load()
          .rdd.map(row => {
            new DetailedEvent(
              trace_id = row.getAs[String]("trace_id"),
              event_type = row.getAs[String]("event_type"),
              position = row.getAs[Int]("position"),
              start_timestamp = row.getAs[String]("start_timestamp"),
              timestamp = row.getAs[String]("end_timestamp"),
              waiting_time = row.getAs[Long]("waiting_time"),
              resource = row.getAs[String]("resource")
            )
          })
      } else {
        spark.read
          .format("org.apache.spark.sql.cassandra")
          .options(Map("table" -> seq_table, "keyspace" -> keyspace))
          .load()
          .rdd.map(row => {
            new Event(
              trace_id = row.getAs[String]("trace_id"),
              timestamp = row.getAs[String]("timestamp"),
              event_type = row.getAs[String]("event_type"),
              position = row.getAs[Int]("position")
            )
          })
      }
    } catch {
      case _: Exception => null
    }
  }

  /**
   * Write sequence table data
   */
  def write_sequence_table(sequenceRDD: RDD[EventTrait], metaData: MetaData, detailed: Boolean = false): Unit = {
    Logger.getLogger("Sequence Table Write").log(Level.INFO, s"Start writing sequence table")
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._
    val start = System.currentTimeMillis()

    if (detailed) {
      val df = sequenceRDD.filter(_.isInstanceOf[DetailedEvent])
        .map(_.asInstanceOf[DetailedEvent])
        .map(x => (x.trace_id, x.event_type, x.position, x.start_timestamp,
                  x.timestamp, x.waiting_time, x.resource))
        .toDF("trace_id", "event_type", "position", "start_timestamp",
              "end_timestamp", "waiting_time", "resource")

      df.write
        .format("org.apache.spark.sql.cassandra")
        .options(Map("table" -> detailed_table, "keyspace" -> keyspace))
        .mode(SaveMode.Append)
        .save()
    } else {
      val df = sequenceRDD
        .map(x => (x.trace_id, x.event_type, x.position, x.timestamp))
        .toDF("trace_id", "event_type", "position", "timestamp")

      metaData.traces += df.filter(_.getAs[Int]("position") == 0).count()
      metaData.events += df.count()

      df.write
        .format("org.apache.spark.sql.cassandra")
        .options(Map("table" -> seq_table, "keyspace" -> keyspace))
        .mode(SaveMode.Append)
        .save()
    }

    val total = System.currentTimeMillis() - start
    Logger.getLogger("Sequence Table Write").log(Level.INFO, s"finished in ${total / 1000} seconds")
  }

  /**
   * Write single table data
   */
  def write_single_table(sequenceRDD: RDD[EventTrait], metaData: MetaData): Unit = {
    Logger.getLogger("Single Table Write").log(Level.INFO, s"Start writing single table")
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._
    val start = System.currentTimeMillis()

    val df = sequenceRDD
      .map(x => (x.event_type, x.trace_id, x.position, x.timestamp))
      .toDF("event_type", "trace_id", "position", "timestamp")

    df.write
      .format("org.apache.spark.sql.cassandra")
      .options(Map("table" -> single_table, "keyspace" -> keyspace))
      .mode(SaveMode.Append)
      .save()

    val total = System.currentTimeMillis() - start
    Logger.getLogger("Single Table Write").log(Level.INFO, s"finished in ${total / 1000} seconds")
  }

  /**
   * Read single table data
   */
  def read_single_table(metaData: MetaData): RDD[Event] = {
    val spark = SparkSession.builder().getOrCreate()
    try {
      spark.read
        .format("org.apache.spark.sql.cassandra")
        .options(Map("table" -> single_table, "keyspace" -> keyspace))
        .load()
        .rdd.map(row => {
          new Event(
            trace_id = row.getAs[String]("trace_id"),
            timestamp = row.getAs[String]("timestamp"),
            event_type = row.getAs[String]("event_type"),
            position = row.getAs[Int]("position")
          )
        })
    } catch {
      case _: Exception => null
    }
  }

  /**
   * Read last checked table data
   */
  def read_last_checked_table(metaData: MetaData): RDD[LastChecked] = {
    val spark = SparkSession.builder().getOrCreate()
    try {
      spark.read
        .format("org.apache.spark.sql.cassandra")
        .options(Map("table" -> last_checked_table, "keyspace" -> keyspace))
        .load()
        .rdd.map(row => {
          LastChecked(
            eventA = row.getAs[String]("event_a"),
            eventB = row.getAs[String]("event_b"),
            id = row.getAs[String]("trace_id"),
            timestamp = row.getAs[String]("timestamp")
          )
        })
    } catch {
      case _: Exception => null
    }
  }

  /**
   * Write last checked table data
   */
  def write_last_checked_table(lastChecked: RDD[LastChecked], metaData: MetaData): Unit = {
    Logger.getLogger("LastChecked Table Write").log(Level.INFO, s"Start writing LastChecked table")
    val start = System.currentTimeMillis()
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._

    val df = lastChecked
      .map(x => (x.eventA, x.eventB, x.id, x.timestamp))
      .toDF("event_a", "event_b", "trace_id", "timestamp")

    df.write
      .format("org.apache.spark.sql.cassandra")
      .options(Map("table" -> last_checked_table, "keyspace" -> keyspace, "confirm.truncate" -> "true"))
      .mode(SaveMode.Overwrite)
      .save()

    val total = System.currentTimeMillis() - start
    Logger.getLogger("LastChecked Table Write").log(Level.INFO, s"finished in ${total / 1000} seconds")
  }

  /**
   * Write index table data
   */
  def write_index_table(newPairs: RDD[Structs.PairFull], metaData: MetaData): Unit = {
    Logger.getLogger("Index Table Write").log(Level.INFO, s"Start writing Index table")
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._
    val start = System.currentTimeMillis()

    metaData.pairs += newPairs.count()

    val df = if (metaData.mode == "positions") {
      newPairs
        .map(x => (x.eventA, x.eventB, x.id, null, null, x.positionA, x.positionB))
        .toDF("event_a", "event_b", "trace_id", "timestamp_a", "timestamp_b",
              "position_a", "position_b")
    } else {
      newPairs
        .map(x => (x.eventA, x.eventB, x.id, x.timeA.toString, x.timeB.toString, x.positionA, x.positionB))
        .toDF("event_a", "event_b", "trace_id", "timestamp_a", "timestamp_b",
              "position_a", "position_b")
    }

    df.write
      .format("org.apache.spark.sql.cassandra")
      .options(Map("table" -> index_table, "keyspace" -> keyspace))
      .mode(SaveMode.Append)
      .save()

    val total = System.currentTimeMillis() - start
    Logger.getLogger("Index Table Write").log(Level.INFO, s"finished in ${total / 1000} seconds")
  }

  /**
   * Write count table data
   */
  def write_count_table(counts: RDD[Structs.Count], metaData: MetaData): Unit = {
    Logger.getLogger("Count Table Write").log(Level.INFO, s"Start writing Count table")
    val start = System.currentTimeMillis()
    val spark = SparkSession.builder().getOrCreate()
    import spark.implicits._

    val df = counts
      .map(x => (x.eventA, x.eventB, x.sum_duration, x.count, x.min_duration, x.max_duration, x.sum_squares))
      .toDF("event_a", "event_b", "sum_duration", "count", "min_duration", "max_duration", "sum_squares")

    df.write
      .format("org.apache.spark.sql.cassandra")
      .options(Map("table" -> count_table, "keyspace" -> keyspace, "confirm.truncate" -> "true"))
      .mode(SaveMode.Overwrite)
      .save()

    val total = System.currentTimeMillis() - start
    Logger.getLogger("Count Table Write").log(Level.INFO, s"finished in ${total / 1000} seconds")
  }
}
