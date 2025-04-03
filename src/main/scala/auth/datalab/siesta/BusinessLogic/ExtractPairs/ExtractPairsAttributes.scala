package auth.datalab.siesta.BusinessLogic.ExtractPairs

import auth.datalab.siesta.BusinessLogic.Model.Structs
import org.apache.log4j.{Level, Logger}
import org.apache.spark.broadcast.Broadcast
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession

import java.sql.Timestamp
import java.time.Duration
import java.time.temporal.ChronoUnit
import scala.collection.mutable.ListBuffer

object ExtractPairsAttributes {

  def extract(singleRDD: RDD[Structs.InvertedSingleAttributes], last_checked: RDD[Structs.LastChecked],
              lookback: Int): (RDD[Structs.PairFullAttributes], RDD[Structs.LastChecked]) = {
    val spark = SparkSession.builder().getOrCreate()

    Logger.getLogger("Pair Extraction")
      .log(Level.INFO, s"Number of input traces ${singleRDD.map(_.id).count()} ")
    Logger.getLogger("Pair Extraction")
      .log(Level.INFO, s"Number of unique input traces ${singleRDD.map(_.id).distinct().count()}")


    val full = if (last_checked == null) {
      singleRDD.groupBy(_.id)
        .map(x => {
          this.calculate_pairs_stnm_attributes(x._2, null, lookback)
        })
    } else {
      singleRDD.groupBy(_.id).leftOuterJoin(last_checked.groupBy(_.id))
        .map(x => {
          val last = x._2._2.orNull
          this.calculate_pairs_stnm_attributes(x._2._1, last, lookback)
        })
    }
    val pairs = full.flatMap(_._1)
    val last_checked_pairs = full.flatMap(_._2)
    Logger.getLogger("Pair Extraction").log(Level.INFO, s"Extracted ${pairs.count()} event pairs")
    Logger.getLogger("Pair Extraction").log(Level.INFO, s"Extracted ${last_checked_pairs.count()} last checked")
    (pairs, last_checked_pairs)

  }

  private def calculate_pairs_stnm_attributes(single: Iterable[Structs.InvertedSingleAttributes], last: Iterable[Structs.LastChecked],
                                              lookback: Int): (List[Structs.PairFullAttributes], List[Structs.LastChecked]) = {
    val trace_id = single.head.id
    val singleMap: Map[String, Iterable[Structs.InvertedSingleAttributes]] = single.groupBy(_.event_name)
    val newLastChecked = new ListBuffer[Structs.PairFullAttributes]()
    val lastMap = if (last != null) last.groupBy(x => (x.eventA, x.eventB)) else null
    val all_events = single.map(_.event_name).toList.distinct
    val combinations = this.findCombinations(all_events)
    val results: ListBuffer[Structs.PairFullAttributes] = new ListBuffer[Structs.PairFullAttributes]()

    combinations.foreach(key => { // For each combination of event pairs
      // Extract timestamp, position, and attributes for the events
      val ts1 = singleMap.getOrElse(key._1, null).map(x => x.times.zip(x.positions.zip(x.attributes))).head
      val ts2 = singleMap.getOrElse(key._2, null).map(x => x.times.zip(x.positions.zip(x.attributes))).head

      val final1 = singleMap.getOrElse(key._1, null).flatMap { x =>
        // Zip times, positions, and attributes
        x.times.zip(x.positions.zip(x.attributes)).map {
          case (timestamp, (position, attributes)) =>
            // Return the tuple (String, Int, Map[String, String])
            (timestamp, position, attributes)
        }
      }.toList

      val final2 = singleMap.getOrElse(key._2, null).flatMap { x =>
        // Zip times, positions, and attributes
        x.times.zip(x.positions.zip(x.attributes)).map {
          case (timestamp, (position, attributes)) =>
            // Return the tuple (String, Int, Map[String, String])
            (timestamp, position, attributes)
        }
      }.toList


      // Get the last checked event for the pair, if it exists
      val last_checked = try {
        lastMap.getOrElse(key, null).head
      } catch {
        case _: Exception => null
      }
      //detects all the occurrences of this event type pair using the ts1 and ts2
      val nres = this.createTuplesAttributes(key._1, key._2, final1, final2, lookback, last_checked, trace_id)
      //if there are any append them and also keep the last timestamp that they occurred
      if (nres.nonEmpty) {
        newLastChecked += nres.last
        results ++= nres
      }
    })
    val l = newLastChecked.map(x => {
      Structs.LastChecked(x.eventA, x.eventB, x.id, timestamp = x.timeB.toString)
    })
    (results.toList, l.toList)
  }

  def createTuplesAttributes(key1: String, key2: String, final1: List[(String, Int, Map[String,String])], final2: List[(String, Int, Map[String,String])],
                             lookback: Int, last_checked: Structs.LastChecked,
                             trace_id: String): List[Structs.PairFullAttributes] = {
    val pairs = new ListBuffer[Structs.PairFullAttributes]
    val lookbackMillis = Duration.ofDays(lookback).toMillis
    var prev: Timestamp = null
    var i=0
    for (ea <- final1) {
      val ea_ts = Timestamp.valueOf(ea._1)
      if ((prev == null || !ea_ts.before(prev)) && //evaluate based on previous
        (last_checked == null || !ea_ts.before(Timestamp.valueOf(last_checked.timestamp)))) { // evaluate based on last checked
        var stop = false
        while (i < final2.size && !stop) { //iterate through what remained of the second list
          val eb = final2(i)
          val eb_ts = Timestamp.valueOf(eb._1)
          if (ea._2 >= eb._2) { // if the event a is not before the event b we remove it
            i+=1
          } else { // if it is we create a new pair and remove it from consideration in the next iteration
            if (eb_ts.getTime - ea_ts.getTime <= lookbackMillis) { //evaluate lookback
              pairs.append(Structs.PairFullAttributes(key1, key2, trace_id, ea_ts, eb_ts, ea._2, eb._2, ea._3, eb._3))
              prev = eb_ts
              i+=1 //remove the event anyway and stop the process because the next events timestamps will be greater
            }
            //in case lookcback is not satisfied it will iterate back and evaluate the next timestamp from the first list
            //as a pair with this event
            stop = true
          }
        }
      }
    }
    pairs.toList
  }

  private def findCombinations(event_types: List[String]): List[(String, String)] = {
    event_types.flatMap(t1 => {
      event_types.map(t2 => {
        (t1, t2)
      })
    }).distinct
  }
}
