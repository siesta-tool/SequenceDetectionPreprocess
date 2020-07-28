package auth.datalab.sequenceDetection

import java.sql.Timestamp

import auth.datalab.sequenceDetection.PairExtraction.{Indexing, Parsing, State, TimeCombinations, ZipCombinations}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.DataFrame
import org.apache.spark.storage.StorageLevel

import scala.collection.mutable


object SequenceDetection {
  private var cassandraConnection: CassandraConnection = null
  private var table_date = ""

  def main(args: Array[String]): Unit = {
    //    start by getting the parameters that we will need, what type of file (xes, txt) , filename, type of combinations
    val fileName: String = "testing.txt"
//        val fileName: String = "BPI Challenge 2017.xes"


    val deletePrevious = "1"
    val deleteAll = "1"
    val type_of_algorithm = "indexing"
    val join = 0

    Logger.getLogger("org").setLevel(Level.ERROR)

    //    val format = new SimpleDateFormat("y-M-d")
    //    val today_date: String = format.format(Calendar.getInstance().getTime())
    //    //starting time will be this date for starting
    //    val myDate = today_date.split(" ")(0).split("-")
    //    if (myDate(2).toInt <= 10) table_date = myDate(0) + "_" + myDate(1) + "_1"
    //    else if (myDate(2).toInt <= 20) table_date = myDate(0) + "_" + myDate(1) + "_2"
    //    else table_date = myDate(0) + "_" + myDate(1) + "_3"

    //    var table_temp = "usr_temp_" + table_date
    //    var table_seq = "usr_seq_" + table_date
    //    var table_idx = "usr_idx_" + table_date
    //    var table_count = "usr_count_" + table_date

    var table_name = fileName.split('.')(0).replace(' ', '_')
    var table_temp = table_name + "_temp"
    var table_seq = table_name + "_seq"
    var table_idx = table_name + "_idx"
    var table_count = table_name + "_count"

    val tables: Map[String, String] = Map(
      table_idx -> "event1_name text, event2_name text, sequences list<text>, PRIMARY KEY (event1_name, event2_name)",
      table_temp -> "event1_name text, event2_name text,  sequences list<text>, PRIMARY KEY (event1_name, event2_name)",
      table_count -> "first_field text, sequences_per_field list<text>, PRIMARY KEY (first_field)",
      table_seq -> "sequence_id text, events list<text>, PRIMARY KEY (sequence_id)"
    )

    cassandraConnection = new CassandraConnection()
    cassandraConnection.startSpark()

    if (deleteAll == "1") {
      cassandraConnection.dropAlltables()
    }
    cassandraConnection.createTables(tables)


    println("Finding Combinations ...")
    try {
      val sequencesRDD: RDD[Structs.Sequence] = Utils.readLog(fileName)
//      TODO: if needed to combine with previous existing records
      val combinationsRDD = startCombinationsRDD(sequencesRDD, table_temp, "", join, type_of_algorithm, table_seq, null, 0).persist(StorageLevel.MEMORY_AND_DISK)
//      combinationsRDD.take(10).foreach(println)
      println("Writing combinations RDD to Cassandra ..")
      cassandraConnection.writeTableSequenceIndex(combinationsRDD, table_idx)
      combinationsRDD.unpersist()
      println("Writing sequences RDD to Cassandra ...")
      cassandraConnection.writeTableSeq(sequencesRDD, table_seq)
      sequencesRDD.unpersist()
      cassandraConnection.closeSpark()
    }catch {
      case e:Exception=>{
        println(e.getMessage())
        cassandraConnection.closeSpark()
      }
    }






  }

  def createCombinationsRDD(seqRDD: RDD[Structs.Sequence], type_of_algorithm: String): RDD[Structs.EventIdTimeLists] = {
    type_of_algorithm match {
      case "parsing" => Parsing.extract(seqRDD)
      case "indexing" => Indexing.extract(seqRDD)
      case "state" => State.extract(seqRDD)
      case _ => throw new Exception("Wrong type of algorithm")
    }
  }


  def startCombinationsRDD(seqRDD: RDD[Structs.Sequence], table_temp: String, time: String, join: Int, type_of_algorithm: String, table_name: String, entities: Broadcast[mutable.HashMap[Integer, Integer]], look_back_hours: Int): RDD[Structs.EventIdTimeLists] = {
    var res: RDD[Structs.EventIdTimeLists] = null
    if (join == 0) { // we have no prio knowledge and it will not have next
      res = createCombinationsRDD(seqRDD, type_of_algorithm)
      if (time != "") { // we need to eliminate all the pairs completed before the time
        res=TimeCombinations.timeCombinationsRDD(res,time)
      }
    }else{
      val funnel_time = Timestamp.valueOf(time).getTime - (look_back_hours * 3600 * 1000)
      val funnel_date = new Timestamp(funnel_time)
      val tempTable:DataFrame=cassandraConnection.readTemp(table_temp,funnel_date)
      res=ZipCombinations.zipCombinationsRDD(seqRDD,tempTable,table_name, funnel_date)
    }
    res
  }

  //    if (join == 0) {
  //      val combinations = SparkUtils.createCombinationsRDD(seqRDD)
  //      res = SparkUtils.timeCombinationsRDD(combinations, time)
  //
  //    }else{
  //      val funnel_time = Timestamp.valueOf(time).getTime - (look_back_hours * 3600 * 1000)
  //      val funnel_date = new Timestamp(funnel_time)
  //      val tempTable:DataFrame=cassandraConnection.readTemp(table_temp,funnel_date)
  //      res = SparkUtils.zipCombinationsRDD(seqRDD, tempTable,table_name, funnel_date)
  //        .filter(p => {
  //          val app1 = p.event1.split("_")(0)
  //          val app2 = p.event2.split("_")(0)
  //          if (app1 != app2 && entities.value(app1.toInt) != entities.value(app2.toInt)) false
  //          else true
  //        })


  //    }
  //
  //  }


}