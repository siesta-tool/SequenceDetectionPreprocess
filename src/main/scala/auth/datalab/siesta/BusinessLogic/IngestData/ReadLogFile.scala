package auth.datalab.siesta.BusinessLogic.IngestData

import auth.datalab.siesta.BusinessLogic.Model.{DetailedEvent, Event, Sequence}
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.deckfour.xes.in.XParserRegistry
import org.deckfour.xes.model.{XLog, XTrace}

import java.io.{File, FileInputStream}
import java.sql.Timestamp
import java.text.SimpleDateFormat
import java.util.Scanner
import scala.collection.convert.ImplicitConversions.`list asScalaBuffer`
//import scala.collection.JavaConversions.asScalaBuffer
import scala.collection.mutable.ArrayBuffer

/**
 * This class defines the various formats that can be ingested in the system
 */
object ReadLogFile {
  /**
   * This class combines all the different parsing methods and chooses the one that corresponds to the extension of the
   * logfile
   *
   * @param fileName  The name of the log file
   * @param separator Defines how the events are separated (can be changed to match new txt format)
   * @return The RDD that contains the parsed traces
   */

  def readLogDetailed(fileName: String, separator: String = ","): RDD[Sequence] = {
    if (fileName.split('.')(1) == "xes") {
      this.readFromXesDetailed(fileName)
    } else if (fileName.split('.')(1) == "withTimestamp") {
      this.readWithTimestampsDetailed(fileName, ",", "/delab/")
    } else {
      throw new Exception("Not recognised file type")
    }
  }

  def readLog(fileName: String, separator: String = ","): RDD[Sequence] = {
    if (fileName.split('.')(1) == "txt") { //there is no time limitations
      this.readFromTxt(fileName, separator)
    } else if (fileName.split('.')(1) == "xes") {
      this.readFromXes(fileName)
    } else if (fileName.split('.')(1) == "withTimestamp") {
      this.readWithTimestamps(fileName, ",", "/delab/")
    } else if (fileName.split('.')(1) == "csv") {
      this.readFromCSV(fileName)
    }else {
      throw new Exception("Not recognised file type")
    }
  }

  /**
   * This is the simplest file extension. Each line contains information for a particular event. The events are separated
   * by the separator. The trace index is the index of the line. Instead of using the timestamp for each event the index
   * of the event in the trace is utilized.
   *
   * @param fileName  The name of the log file
   * @param seperator The separator of the events that belongs in the same trace
   * @return The RDD that contains the parsed traces
   */
  private def readFromTxt(fileName: String, seperator: String): RDD[Sequence] = {
    val spark = SparkSession.builder().getOrCreate()
    spark.sparkContext.textFile(fileName).zipWithIndex map { case (line, index) =>
      val sequence: Array[Event] = line.split(seperator).zipWithIndex map { case (event, inner_index) =>
        new Event(trace_id = index.toString, position = inner_index, event_type = event, timestamp = inner_index.toString)
      }
      new Sequence(sequence.toList, index.toString)
    }
  }

  private def readWithTimestampsDetailed(fileName: String, separator: String, delimiter: String): RDD[Sequence] = {
    val spark = SparkSession.builder().getOrCreate()

    val reader = new Scanner(new File(fileName))
    val ar: ArrayBuffer[Sequence] = new ArrayBuffer[Sequence]()
    while (reader.hasNextLine) {
      val line = reader.nextLine()
      val index = line.split("::")(0)
      val events = line.split("::")(1)
      val sequence = events.split(separator).zipWithIndex.map(event => {
        new DetailedEvent(event_type = event._1.split(delimiter)(0),
          timestamp = event._1.split(delimiter)(1),
          resource = event._1.split(delimiter)(2),
          trace_id = index, position = event._2)
      })
      ar.append(new Sequence(sequence.toList, index))
    }
    val par = spark.sparkContext.parallelize(ar)
    par
  }


  /**
   * Xes files are standard for the Business Process Management. They use an XML format with predefined field names.
   * In order to parse such a file, the [[org.deckfour.xes.in.XParserRegistry]] is utilized.
   *
   * @param fileName The name of the log file
   * @return The RDD that contains the parsed traces
   */
  private def readFromXes(fileName: String): RDD[Sequence] = {
    val spark = SparkSession.builder().getOrCreate()
    val file_Object = new File(fileName)
    var parsed_logs: List[XLog] = null
    val parsers_iterator = XParserRegistry.instance().getAvailable.iterator()
    while (parsers_iterator.hasNext) {
      val p = parsers_iterator.next
      if (p.canParse(file_Object)) {
        parsed_logs = p.parse(new FileInputStream(file_Object)).toList
      }
    }

    //val df = new SimpleDateFormat("MMM d, yyyy HH:mm:ss a") //read this pattern from xes
    //val df3 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
    val df4 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
    val df2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss") // transform it to this patter


    val data = parsed_logs.head.zipWithIndex map { case (trace: XTrace, index: Int) =>
      val case_id = trace.getAttributes.get("concept:name").toString
      val list = trace.zipWithIndex.map(e => {
        val event_name = e._1.getAttributes.get("concept:name").toString
        val timestamp_occurred = e._1.getAttributes.get("time:timestamp").toString
        new Event(timestamp = df2.format(df4.parse(timestamp_occurred)), event_type = event_name, trace_id = case_id, position = e._2)
      }).toList

      new Sequence(list, case_id)
    }
    val par = spark.sparkContext.parallelize(data)
    par
  }

  private def readFromXesDetailed(fileName: String): RDD[Sequence] = {
    val spark = SparkSession.builder().getOrCreate()
    val file_Object = new File(fileName)
    var parsed_logs: List[XLog] = null
    val parsers_iterator = XParserRegistry.instance().getAvailable.iterator()
    while (parsers_iterator.hasNext) {
      val p = parsers_iterator.next
      if (p.canParse(file_Object)) {
        parsed_logs = p.parse(new FileInputStream(file_Object)).toList
      }
    }

    //val df = new SimpleDateFormat("MMM d, yyyy HH:mm:ss a") //read this pattern from xes
    //val df3 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ")
    val df4 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss")
    val df2 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss") // transform it to this patter

    val data = parsed_logs.head.zipWithIndex map { case (trace: XTrace, index: Int) =>
      val case_id = trace.getAttributes.get("concept:name").toString
      val list = trace.map(event => {

        val event_type = event.getAttributes.get("concept:name").toString
        val end_timestamp = event.getAttributes.get("time:timestamp").toString
        val resource = event.getAttributes.get("org:resource").toString
        new DetailedEvent(event_type = event_type, timestamp = df2.format(df4.parse(end_timestamp)), resource = resource, trace_id = case_id,
          position = index)
      }).toList

      new Sequence(list, case_id)
    }
    val par = spark.sparkContext.parallelize(data)
    par
  }


  /**
   * WithTimestamps is a custom file format that was used to evaluate the performance of SIESTA as it can be easily
   * transformed to csv files that can be ingested in ELK stack
   *
   * @param fileName  The name of the log file
   * @param seperator The separator of the events for a specific trace
   * @param delimiter The separator between the event_type and the event timestamp
   * @return The RDD that contains the parsed traces
   */
  private def readWithTimestamps(fileName: String, seperator: String, delimiter: String): RDD[Sequence] = {
    val spark = SparkSession.builder().getOrCreate()

    val reader = new Scanner(new File(fileName))
    val ar: ArrayBuffer[Sequence] = new ArrayBuffer[Sequence]()
    while (reader.hasNextLine) {
      val line = reader.nextLine()
      val index = line.split("::")(0)
      val events = line.split("::")(1)
      val sequence = events.split(seperator).zipWithIndex.map(event => {
        new Event(timestamp = event._1.split(delimiter)(1), event_type = event._1.split(delimiter)(0), trace_id = index,
          position = event._2)
      })
      ar.append(new Sequence(sequence.toList, index))
    }
    val par = spark.sparkContext.parallelize(ar)
    par
  }

  def readFromCSV(fileName: String): RDD[Sequence] = {
    val spark = SparkSession.builder().getOrCreate()
    // Read CSV file into DataFrame
    val df = spark.read.option("header", "true")
      .csv(fileName)
      .select("trace_id", "event_type", "timestamp")

    // Convert DataFrame to RDD and group by trace_id
    val groupedRDD = df.rdd.map(row =>
      (row.getString(0), new Event(timestamp = row.getString(2), event_type = row.getString(1), trace_id = row.getString(0),
        position = 0))
    ).combineByKey(
      (event: Event) => List(event), // Create a list with the first event
      (acc: List[Event], event: Event) => event :: acc, // Add event to the list
      (acc1: List[Event], acc2: List[Event]) => acc1 ++ acc2 // Merge lists from different partitions
    )

    // Sort by timestamp and assign positions
    import spark.implicits._
    val sequencesRDD = groupedRDD.map { case (traceId, events) =>
      val sortedEvents = events.map(x=>(x,Timestamp.valueOf(x.timestamp).getTime)).toList
        .sortBy(_._2).zipWithIndex.map {case (event,index)=>
          new Event(timestamp = event._1.timestamp,event_type = event._1.event_type, trace_id = event._1.trace_id,
            position = index
          )
      }
      new Sequence(sortedEvents, traceId)
    }

    sequencesRDD
  }


}
