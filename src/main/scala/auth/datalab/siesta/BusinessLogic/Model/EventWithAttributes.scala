package auth.datalab.siesta.BusinessLogic.Model

class EventWithAttributes(override val trace_id:String,
                          override val timestamp: String,
                          override val event_type: String,
                          override val position: Int,
                          val attributes: Map[String,String]) extends Event(trace_id,timestamp,event_type,position) with EventTrait

