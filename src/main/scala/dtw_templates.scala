package dtw

import collection.mutable.{ArrayBuffer}
import collection.parallel.mutable.{ParArray}
import math.{pow, sqrt}
import breeze.linalg.{DenseVector, DenseMatrix, norm}
import breeze.stats.{mean, stddev}


class TimeSeries(val seq: DenseMatrix[Double], val label: String) {

  def length(): Int = {
    seq.rows
  }
}


class DTWTemplate(val seq: DenseMatrix[Double], val splitDist: Double, val label: String) {

}


object DTWAlgorithms {

  def dtwDist(seq1: DenseMatrix[Double], seq2: DenseMatrix[Double]): Double = {
    val costMatrix = fillMatrix(seq1, seq2)
    costMatrix(costMatrix.rows - 1, costMatrix.cols - 1)
  }

  def fillMatrix(seq1: DenseMatrix[Double], seq2: DenseMatrix[Double]) = {
    val n = seq1.rows
    val m = seq2.rows
    var w = (Array(n, m).max * 0.3).floor.toInt
    w = Array(w, (n-m).abs).max
    var costMatrix = DenseMatrix.tabulate(n, m){case (_, _) => Double.PositiveInfinity}
    costMatrix(0, 0) = 0

    // fill matrix
    (1 until n).foreach { i =>
      (Array(1, i-w).max until Array(m, i+w).min).foreach { j =>
        val cost = this.distance(seq1(i, ::).t, seq2(j, ::).t)
        costMatrix(i, j) = cost + List(costMatrix(i-1, j), costMatrix(i , j-1), costMatrix(i-1, j-1)).min
      }
    }
    costMatrix
  }

  /*
  Keogh's Lower Bound: compare error accummulated at each point in query
  time series against best(worst) points in +- r positions in candidate
  time series
  */
  def lowerBound(q: DenseMatrix[Double], c: DenseMatrix[Double], r: Int=10) = {
    var lbSum = 0.0
    (0 until q.rows).foreach {(idx: Int) =>
      val startIdx: Int = if (idx-r > 0) idx-r else 0
      val endIdx: Int = if (idx+r < q.rows) idx+r else q.rows
      val bounds = (startIdx until endIdx).map {i => norm1(q(i, ::).t)}
      val lower_bound = bounds.min
      val upper_bound = bounds.max

      val point = norm1(c(idx, ::).t)
      if (point > upper_bound) {
        lbSum = lbSum + pow(point-upper_bound, 2)
      }
      else if (point < lower_bound) {
        lbSum = lbSum + pow(point-lower_bound, 2)
      }
    }
    sqrt(lbSum)
  }

  def norm1(v: DenseVector[Double]): Double = {
    sqrt((v * v).reduce {_ + _})
  }

  def distance(vector1: DenseVector[Double], vector2: DenseVector[Double]): Double = {
    norm(vector1 - vector2)
  }
}


class DTWClassifier(iTemplates: Array[DTWTemplate]) {

  val templates = iTemplates

  def predict(sample: TimeSeries): String = {
    var lowestDist: Double = Double.PositiveInfinity
    var bestLabel = ""
    this.templates.foreach { template =>
      val lBound = DTWAlgorithms.lowerBound(sample.seq, template.seq)
      println(lBound < lowestDist)
      if (lBound < lowestDist) {
        val dist = DTWAlgorithms.dtwDist(template.seq, sample.seq)
        if (dist <= template.splitDist && dist <= lowestDist) {
          lowestDist = dist
          bestLabel = template.label
        }
      }
    }
    bestLabel
  }
}


object Main {

  def trainDTW(trainSamples: ArrayBuffer[TimeSeries], trainLabels: Array[String]) = {
    var samplesByLetter = Map[String, ArrayBuffer[TimeSeries]]()
    trainSamples.foreach {sample =>
      if (!samplesByLetter.contains(sample.label)) {
        samplesByLetter = samplesByLetter + (sample.label -> ArrayBuffer())
      }
      samplesByLetter(sample.label).append(sample)
    }

    val templates = trainLabels.map(l => findTemplate(l, samplesByLetter(l)))
    new DTWClassifier(templates)
  }

  def findTemplate(letter: String, samples: ArrayBuffer[TimeSeries]) = {
    val (template, dists) = samples.map({candidate => (candidate, testTemplate(candidate, samples))}
                                       ).minBy(_._2.sum)
    val splitDist = mean(dists) + 2 * stddev(dists)
    val bestTemplate = new DTWTemplate(template.seq, splitDist, letter)
    bestTemplate
  }

  def testTemplate(template: TimeSeries, samples: ArrayBuffer[TimeSeries]) = {
    samples.filter(_ != template).map(sample => DTWAlgorithms.dtwDist(template.seq, sample.seq))
  }

  def testClassifier(dtwClassifier: DTWClassifier, testSamples: ArrayBuffer[TimeSeries]) = {
    val preds = ArrayBuffer[String]()
    val real = ArrayBuffer[String]()
    testSamples.foreach {sample =>
      val pred = dtwClassifier.predict(sample)
      preds.append(pred)
      real.append(sample.label)
    }
    val acc = scorePreds(preds, real)
    println(acc)
    println(s"p: $preds")
    println(s"r: $real")
  }

  def scorePreds(predictions: ArrayBuffer[String], real: ArrayBuffer[String]): Double = {
    var score = 0
    for ( (p, r) <- (predictions zip real)) {
      if (p == r) score += 1
    }
    score / real.length.toDouble
  }
}
