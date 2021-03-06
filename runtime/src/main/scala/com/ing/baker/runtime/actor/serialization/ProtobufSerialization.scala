package com.ing.baker.runtime.actor.serialization

import java.security.MessageDigest

import com.ing.baker.petrinet.api._
import com.ing.baker.petrinet.runtime.EventSourcing._
import com.ing.baker.petrinet.runtime.ExceptionStrategy.{BlockTransition, Fatal, RetryWithDelay}
import com.ing.baker.runtime.actor.messages
import com.ing.baker.runtime.actor.messages._
import com.ing.baker.petrinet.runtime.{EventSourcing, Instance}
import ProtobufSerialization._
import com.ing.baker.runtime.actor.messages.FailureStrategy.StrategyType

object ProtobufSerialization {

  /**
    * TODO:
    *
    * This approach is fragile, the identifier function cannot change ever or recovery breaks
    * a more robust alternative is to generate the ids and persist them
    */
  def tokenIdentifier[P[_], C](p: P[C]): Any ⇒ Long = obj ⇒ hashCodeOf[Any](obj)

  def sha256(str: String) = {
    val sha256Digest: MessageDigest = MessageDigest.getInstance("SHA-256")
    BigInt(1, sha256Digest.digest(str.getBytes("UTF-8"))).toLong
  }

  def hashCodeOf[T](e: T): Long = e match {
    case null        => -1
    case str: String => sha256(str)
    case obj         => obj.hashCode()
  }
}

/**
  * This class is responsible for translating the EventSourcing.Event to and from the persistence.messages.Event
  *
  * (which is generated by scalaPB and serializes to protobuf.
  *
  * TODO: allow an ObjectSerializer per Place / Transition ?
  *
  */
class ProtobufSerialization[P[_], T[_, _], S](
                                               serializer: ObjectSerializer)(implicit placeIdentifier: Identifiable[P[_]], transitionIdentifier: Identifiable[T[_, _]]) {

  /**
    * De-serializes a persistence.messages.Event to a EvenSourcing.Event. An Instance is required to 'wire' or 'reference'
    * the message back into context.
    */
  def deserializeEvent(event: AnyRef): Instance[P, T, S] ⇒ EventSourcing.Event = event match {
    case e: messages.Initialized ⇒ deserializeInitialized(e)
    case e: messages.TransitionFired ⇒ deserializeTransitionFired(e)
    case e: messages.TransitionFailed ⇒ deserializeTransitionFailed(e)
  }

  /**
    * Serializes an EventSourcing.Event to a persistence.messages.Event.
    */
  def serializeEvent(e: EventSourcing.Event): Instance[P, T, S] ⇒ AnyRef =
    instance ⇒ e match {
      case e: InitializedEvent[_] ⇒ serializeInitialized(e.asInstanceOf[InitializedEvent[P]])
      case e: TransitionFiredEvent[_, _, _] ⇒ serializeTransitionFired(e.asInstanceOf[TransitionFiredEvent[P, T, Any]])
      case e: TransitionFailedEvent[_, _, _] ⇒ serializeTransitionFailed(e.asInstanceOf[TransitionFailedEvent[P, T, Any]])
    }

  private def missingFieldException(field: String) = throw new IllegalStateException(s"Missing field in serialized data: $field")

  def serializeObject(obj: Any): Option[SerializedData] = {
    (obj != null && !obj.isInstanceOf[Unit]).option {
      serializer.serializeObject(obj.asInstanceOf[AnyRef])
    }
  }

  def deserializeObject(obj: SerializedData): AnyRef = {
    (transformFromProto _).andThen(serializer.deserializeObject)(obj)
  }

  private def deserializeProducedMarking(instance: Instance[P, T, S], produced: Seq[messages.ProducedToken]): Marking[P] = {
    produced.foldLeft(Marking.empty[P]) {
      case (accumulated, messages.ProducedToken(Some(placeId), Some(_), Some(count), data)) ⇒
        val place = instance.process.places.getById(placeId, "place in petrinet").asInstanceOf[P[Any]]
        val value = data.map(deserializeObject).getOrElse(())
        accumulated.add(place, value, count)
      case _ ⇒ throw new IllegalStateException("Missing data in persisted ProducedToken")
    }
  }

  private def serializeProducedMarking(produced: Marking[P]): Seq[messages.ProducedToken] = {
    produced.data.toSeq.flatMap {
      case (place, tokens) ⇒ tokens.toSeq.map {
        case (value, count) ⇒ messages.ProducedToken(
          placeId = Some(placeIdentifier(place).value),
          tokenId = Some(tokenIdentifier(place)(value)),
          count = Some(count),
          tokenData = serializeObject(value)
        )
      }
    }
  }

  private def serializeConsumedMarking(m: Marking[P]): Seq[messages.ConsumedToken] =
    m.data.toSeq.flatMap {
      case (place, tokens) ⇒ tokens.toSeq.map {
        case (value, count) ⇒ messages.ConsumedToken(
          placeId = Some(placeIdentifier(place).value),
          tokenId = Some(tokenIdentifier(place)(value)),
          count = Some(count)
        )
      }
    }

  private def deserializeConsumedMarking(instance: Instance[P, T, S], persisted: Seq[messages.ConsumedToken]): Marking[P] = {
    persisted.foldLeft(Marking.empty[P]) {
      case (accumulated, messages.ConsumedToken(Some(placeId), Some(tokenId), Some(count))) ⇒
        val place = instance.marking.keySet.getById(placeId, "place in marking").asInstanceOf[P[Any]]
        val value = instance.marking(place).keySet.find(e ⇒ tokenIdentifier(place)(e) == tokenId).get
        accumulated.add(place, value, count)
      case _ ⇒ throw new IllegalStateException("Missing data in persisted ConsumedToken")
    }
  }

  private def deserializeInitialized(e: messages.Initialized)(instance: Instance[P, T, S]): InitializedEvent[P] = {
    val initialMarking = deserializeProducedMarking(instance, e.initialMarking)
    // TODO not really safe to return null here, throw exception ?
    val initialState = e.initialState.map(deserializeObject).getOrElse(())
    InitializedEvent(initialMarking, initialState)
  }

  private def serializeInitialized(e: InitializedEvent[P]): messages.Initialized = {
    val initialMarking = serializeProducedMarking(e.marking)
    val initialState = serializeObject(e.state)
    messages.Initialized(initialMarking, initialState)
  }

  private def deserializeTransitionFailed(e: messages.TransitionFailed): Instance[P, T, S] ⇒ TransitionFailedEvent[P, T, Any] = {
    instance ⇒

      val jobId = e.jobId.getOrElse(missingFieldException("job_id"))
      val transitionId = e.transitionId.getOrElse(missingFieldException("transition_id"))
      val timeStarted = e.timeStarted.getOrElse(missingFieldException("time_started"))
      val timeFailed = e.timeFailed.getOrElse(missingFieldException("time_failed"))
      val input = e.inputData.map(deserializeObject).getOrElse(())
      val failureReason = e.failureReason.getOrElse("")
      val consumed = deserializeConsumedMarking(instance, e.consumed)
      val failureStrategy = e.failureStrategy.getOrElse(missingFieldException("time_failed")) match {
        case FailureStrategy(Some(StrategyType.BLOCK_TRANSITION), _) ⇒ BlockTransition
        case FailureStrategy(Some(StrategyType.BLOCK_ALL), _) ⇒ Fatal
        case FailureStrategy(Some(StrategyType.RETRY), Some(delay)) ⇒ RetryWithDelay(delay)
        case other@_ ⇒ throw new IllegalStateException(s"Invalid failure strategy: $other")
      }

      val transition = instance.process.transitions.getById(transitionId, "transition in petrinet")

      TransitionFailedEvent[P, T, Any](jobId, transition, timeStarted, timeFailed, consumed, input, failureReason, failureStrategy)
  }

  private def serializeTransitionFailed(e: TransitionFailedEvent[P, T, Any]): messages.TransitionFailed = {

    val strategy = e.exceptionStrategy match {
      case BlockTransition ⇒ FailureStrategy(Some(StrategyType.BLOCK_TRANSITION))
      case Fatal ⇒ FailureStrategy(Some(StrategyType.BLOCK_ALL))
      case RetryWithDelay(delay) ⇒ FailureStrategy(Some(StrategyType.RETRY), Some(delay))
      case _ => throw new IllegalArgumentException("Unsupported exception strategy")
    }

    messages.TransitionFailed(
      jobId = Some(e.jobId),
      transitionId = Some(transitionIdentifier(e.transition).value),
      timeStarted = Some(e.timeStarted),
      timeFailed = Some(e.timeFailed),
      inputData = serializeObject(e.input),
      failureReason = Some(e.failureReason),
      failureStrategy = Some(strategy),
      consumed = serializeConsumedMarking(e.consume)
    )
  }

  private def serializeTransitionFired(e: TransitionFiredEvent[P, T, Any]): messages.TransitionFired = {

    val consumedTokens = serializeConsumedMarking(e.consumed)
    val producedTokens = serializeProducedMarking(e.produced)

    messages.TransitionFired(
      jobId = Some(e.jobId),
      transitionId = Some(transitionIdentifier(e.transition).value),
      timeStarted = Some(e.timeStarted),
      timeCompleted = Some(e.timeCompleted),
      consumed = consumedTokens,
      produced = producedTokens,
      data = serializeObject(e.output)
    )
  }

  private def deserializeTransitionFired(e: messages.TransitionFired): Instance[P, T, S] ⇒ TransitionFiredEvent[P, T, Any] = instance ⇒ {

    val consumed: Marking[P] = deserializeConsumedMarking(instance, e.consumed)
    val produced: Marking[P] = deserializeProducedMarking(instance, e.produced)

    val output = e.data.map(deserializeObject).orNull

    val transitionId = e.transitionId.getOrElse(missingFieldException("transition_id"))
    val transition = instance.process.transitions.getById(transitionId, "transition in petrinet")
    val jobId = e.jobId.getOrElse(missingFieldException("job_id"))
    val timeStarted = e.timeStarted.getOrElse(missingFieldException("time_started"))
    val timeCompleted = e.timeCompleted.getOrElse(missingFieldException("time_completed"))

    TransitionFiredEvent[P, T, Any](jobId, transition, timeStarted, timeCompleted, consumed, produced, output)
  }
}
