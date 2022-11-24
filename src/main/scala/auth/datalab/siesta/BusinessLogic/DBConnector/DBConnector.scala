package auth.datalab.siesta.BusinessLogic.DBConnector

import auth.datalab.siesta.BusinessLogic.Metadata.MetaData
import auth.datalab.siesta.BusinessLogic.Model.Structs
import auth.datalab.siesta.BusinessLogic.Model.Structs.{InvertedSingleFull, LastChecked}
import auth.datalab.siesta.CommandLineParser.Config
import org.apache.spark.rdd.RDD

trait DBConnector {
  /**
   * Depending on the different database, each connector has to initialize the spark context
   */
  def initialize_spark(): Unit

  /**
   * Create the appropriate tables, remove previous ones
   */
  def initialize_db(config: Config):Unit

  /**
   * This method constructs the appropriate metadata based on the already stored in the database and the
   * new presented in the config object
   * @param config contains the configuration passed during execution
   * @return the metadata
   */
  def get_metadata(config:Config): MetaData

  /**
   * Persists metadata
   * @param metaData metadata of the execution and the database
   */
  def write_metadata(metaData: MetaData):Unit


  /**
   * Read data as an rdd from the SeqTable
   * @param metaData Containing all the necessary information for the storing
   * @return In RDD the stored data
   */
  def read_sequence_table(metaData: MetaData):RDD[Structs.Sequence]

  /**
   * This method writes traces to the auxiliary SeqTable. Since RDD will be used as intermediate results it is already persisted
   * and should not be modify that.
   * If states in the metadata, this method should combine the new traces with the previous ones
   * This method should combine the results with previous ones and return the results to the main pipeline
   * Additionally updates metaData object
   * @param sequenceRDD RDD containing the traces
   * @param metaData Containing all the necessary information for the storing
   */
  def write_sequence_table(sequenceRDD:RDD[Structs.Sequence],metaData: MetaData): RDD[Structs.Sequence]

  /**
   * This method is responsible to combine results with the previous stored, in order to support incremental indexing
   * @param newSequences The new sequences to be indexed
   * @param previousSequences The previous sequences that are already indexed
   * @return a combined rdd
   */
  def combine_sequence_table(newSequences:RDD[Structs.Sequence],previousSequences:RDD[Structs.Sequence]):RDD[Structs.Sequence]

  /**
   * This method writes traces to the auxiliary SingleTable. The rdd that comes to this method is not persisted.
   * Database should persist it before store it and not persist it at the end.
   * This method should combine the results with previous ones and return the results to the main pipeline
   * Additionally updates metaData object
   * @param singleRDD Contains the single inverted index
   * @param metaData Containing all the necessary information for the storing
   */
  def write_single_table(singleRDD:RDD[Structs.InvertedSingleFull],metaData: MetaData): RDD[Structs.InvertedSingleFull]

  /**
   * Read data as an rdd from the SingleTable
   * @param metaData Containing all the necessary information for the storing
   * @return In RDD the stored data
   */
  def read_single_table(metaData: MetaData):RDD[Structs.InvertedSingleFull]



  /**
   * Combine new and previous entries in the Single table
   * @param newSingle New events in single table
   * @param previousSingle Previous events stored in single table
   * @return the combined lists
   */
  def combine_single_table(newSingle:RDD[Structs.InvertedSingleFull],previousSingle:RDD[Structs.InvertedSingleFull]):RDD[Structs.InvertedSingleFull]

  /**
   * Returns data from LastChecked Table
   * @param metaData Containing all the necessary information for the storing
   * @return LastChecked records
   */
  def read_last_checked_table(metaData: MetaData):RDD[LastChecked]

  /**
   * Writes new records for last checked back in the database and return the combined records with the
   * @param lastChecked records containing the timestamp of last completion for each different n-tuple
   * @param metaData
   * @return
   */
  def write_last_checked_table(lastChecked: RDD[LastChecked], metaData: MetaData):RDD[Structs.LastChecked]

  /**
   * Combines the new with the previous stored last checked
   * @param newLastChecked new records for the last checked
   * @param previousLastChecked already stored records for the last checked values
   */
  def combine_last_checked_table(newLastChecked:RDD[LastChecked], previousLastChecked:RDD[LastChecked]):RDD[LastChecked]



}
