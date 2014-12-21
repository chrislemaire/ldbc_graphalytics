package org.tudelft.graphalytics.graphx.stats

import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD
import org.tudelft.graphalytics.GraphFormat
import org.tudelft.graphalytics.graphx.GraphXJob

/**
 * The implementation of the stats (LCC) algorithm on GraphX. Inspired by the TriangleCount implementation bundled
 * with GraphX.
 *
 * @author Tim Hegeman
 */
class LocalClusteringCoefficientJob(graphPath : String, graphFormat : GraphFormat, outputPath : String)
	extends GraphXJob[Double, Int](graphPath, graphFormat, outputPath) {

	/**
	 * Perform the graph computation using job-specific logic.
	 *
	 * @param graph the parsed graph with default vertex and edge values
	 * @return the resulting graph after the computation
	 */
	override def compute(graph: Graph[Boolean, Int]): Graph[Double, Int] = {
		// Construct a set of neighbours per vertex
		val neighbours = graph.collectNeighborIds(EdgeDirection.Out).mapValues(x => x.toSet)
		// Set the neighbour sets as vertex values
		val neighbourGraph = graph.outerJoinVertices(neighbours) {
			(vid, _, neighbourSet) => neighbourSet.getOrElse(Set[VertexId].empty)
		}

		// Edge triplet map function
		def mapFunc = (edge : EdgeTriplet[Set[VertexId], Int]) => {
			val overlap = edge.srcAttr.intersect(edge.dstAttr).size.toLong
			Iterator((edge.srcId, overlap), (edge.dstId, overlap))
		}
		// Message reduce function
		def reduceFunc = (A : Long, B : Long) => A + B

		// Compute the number of edges in each neighbourhood
		val numEdges = neighbourGraph.mapReduceTriplets(mapFunc, reduceFunc,
			Some((neighbourGraph.vertices, EdgeDirection.Either)))

		// Compute the fraction of edges in each vertex's neighbourhood
		neighbourGraph.outerJoinVertices[Long, Double](numEdges) {
			(vid, neighbourSet, edgeCount) =>
				if (neighbourSet.size <= 1)
					1.0
				else
					edgeCount.getOrElse(0L).toDouble / (neighbourSet.size * (neighbourSet.size - 1))
		}
	}

	/**
	 * Convert a graph to the output format of this job.
	 *
	 * @return a RDD of strings (lines of output)
	 */
	override def makeOutput(graph: Graph[Double, Int]): RDD[String] = {
		val aggregated = graph.vertices.map[(Double, Long)](v => (v._2, 1L)).reduce(
			(A: (Double, Long), B: (Double, Long)) =>
				(A._1 + B._1, A._2 + B._2)
		)
		graph.vertices.context.parallelize(Array((aggregated._1 / aggregated._2).toString))
	}

	/**
	 * @return name of the GraphX job
	 */
	override def getAppName: String = "Local Clustering Coefficient"

	/**
	 * @return true iff the input is valid
	 */
	override def hasValidInput: Boolean = true

}
