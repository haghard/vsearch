package com.github.haghard.vsearch

import io.github.jbellis.jvector.graph.RandomAccessVectorValues
import io.github.jbellis.jvector.vector.types.VectorFloat

import scala.collection.mutable

final class DynamicVectorValues(val dimension: Int) extends RandomAccessVectorValues {

  private val vectors = new mutable.HashMap[Int, VectorFloat[?]]()

  def put(nodeId: Int, vector: VectorFloat[?]): Unit =
    vectors.put(nodeId, vector)

  override def size(): Int = vectors.size

  override def getVector(i: Int): VectorFloat[?] = vectors.get(i).getOrElse(null)

  override def isValueShared: Boolean = false

  override def copy(): RandomAccessVectorValues = this
}
