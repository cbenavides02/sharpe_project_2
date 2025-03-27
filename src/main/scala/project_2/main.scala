package project_2

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.functions._
import org.apache.spark.rdd._

object main {

  val seed = new java.util.Date().hashCode
  val rand = new scala.util.Random(seed)

  class hash_function(numBuckets_in: Long) extends Serializable {
    // A 2-universal hash family; numBuckets_in is the number of buckets.
    val p: Long = 2147483587  // A prime around 2^31.
    val a: Long = (rand.nextLong % (p - 1)) + 1  // a ∈ [1, p]
    val b: Long = (rand.nextLong % p)            // b ∈ [0, p]
    val numBuckets: Long = numBuckets_in

    def convert(s: String, ind: Int): Long = {
      if (ind == 0) return 0
      (s(ind - 1).toLong + 256 * convert(s, ind - 1)) % p
    }

    def hash(s: String): Long = {
      ((a * convert(s, s.length) + b) % p) % numBuckets
    }

    def hash(t: Long): Long = {
      ((a * t + b) % p) % numBuckets
    }

    def zeroes(num: Long, remain: Long): Int = {
      if ((num & 1) == 1 || remain == 1) return 0
      1 + zeroes(num >> 1, remain >> 1)
    }

    def zeroes(num: Long): Int = zeroes(num, numBuckets)
  }

  class four_universal_Radamacher_hash_function extends hash_function(2) {
    // A 4-universal hash family; numBuckets_in is fixed to 2.
    override val a: Long = (rand.nextLong % p)
    override val b: Long = (rand.nextLong % p)
    val c: Long = (rand.nextLong % p)
    val d: Long = (rand.nextLong % p)

    override def hash(s: String): Long = {
      val t = convert(s, s.length)
      val t2 = t * t % p
      val t3 = t2 * t % p
      if ((((a * t3 + b * t2 + c * t + b) % p) & 1) == 0) 1 else -1
    }

    override def hash(t: Long): Long = {
      val t2 = t * t % p
      val t3 = t2 * t % p
      if ((((a * t3 + b * t2 + c * t + b) % p) & 1) == 0) 1 else -1
    }
  }

  // BJKST Sketch class: stores the k smallest normalized hash values.
  class BJKSTSketch(val bucketSize: Int) extends Serializable {
    // minSet holds the smallest normalized hash values (each in [0, 1)).
    var minSet: List[Double] = List.empty[Double]

    // Add an element using the provided hash function.
    def add(s: String, h: hash_function): Unit = {
      val r = h.hash(s).toDouble / h.p.toDouble  // Normalize to [0,1).
      // Insert r and keep only the smallest bucketSize values.
      minSet = (r :: minSet).sorted.take(bucketSize)
    }

    // Merge with another sketch.
    def merge(that: BJKSTSketch): BJKSTSketch = {
      val merged = new BJKSTSketch(bucketSize)
      merged.minSet = (this.minSet ++ that.minSet).sorted.take(bucketSize)
      merged
    }

    // Estimate the number of distinct elements.
    def estimate(): Double = {
      if (minSet.size < bucketSize || minSet.isEmpty) 0.0
      else bucketSize / minSet.last
    }
  }

  // Helper function: compute one trial estimate using a BJKST sketch.
  def computeTrialEstimate(x: RDD[String], width: Int): Double = {
    // Create a new hash function for this trial.
    val h = new hash_function(2147483587)
    // Aggregate the k smallest normalized hash values.
    val zero: List[Double] = List.empty[Double]
    def seqOp(acc: List[Double], s: String): List[Double] = {
      val r = h.hash(s).toDouble / h.p.toDouble
      (r :: acc).sorted.take(width)
    }
    def combOp(acc1: List[Double], acc2: List[Double]): List[Double] = {
      (acc1 ++ acc2).sorted.take(width)
    }
    val minSet = x.aggregate(zero)(seqOp, combOp)
    if (minSet.size < width || minSet.isEmpty) 0.0 else width / minSet.last
  }

  // BJKST function: run "trials" independent sketches and return the median estimate.
  def BJKST(x: RDD[String], width: Int, trials: Int): Double = {
    val estimates = (1 to trials).map { _ =>
      computeTrialEstimate(x, width)
    }.filter(_ > 0).toList

    if (estimates.isEmpty) 0.0
    else {
      val sortedEstimates = estimates.sorted
      val n = sortedEstimates.size
      if (n % 2 == 1) sortedEstimates(n / 2)
      else (sortedEstimates(n / 2 - 1) + sortedEstimates(n / 2)) / 2.0
    }
  }

  def tidemark(x: RDD[String], trials: Int): Double = {
    val h = Seq.fill(trials)(new hash_function(2000000000))
    def param0 = (accu1: Seq[Int], accu2: Seq[Int]) => 
      Seq.range(0, trials).map(i => scala.math.max(accu1(i), accu2(i)))
    def param1 = (accu1: Seq[Int], s: String) => 
      Seq.range(0, trials).map(i => scala.math.max(accu1(i), h(i).zeroes(h(i).hash(s))))
    val x3 = x.aggregate(Seq.fill(trials)(0))(param1, param0)
    val ans = x3.map(z => scala.math.pow(2, 0.5 + z)).sorted
    ans(trials / 2)
  }

  def Tug_of_War(x: RDD[String], width: Int, depth: Int): Long = {
    val totalSketches = width * depth
    val hashFuncs: Array[Array[String => Long]] = Array.tabulate(depth, width) { (d, w) =>
      new four_universal_Radamacher_hash_function().hash(_: String)
    }
    val broadcastHashFuncs = x.sparkContext.broadcast(hashFuncs)
    val zero = Array.fill[Long](totalSketches)(0L)
    val sketchSums: Array[Long] = x.aggregate(zero)(
      (acc, s) => {
        val funcs = broadcastHashFuncs.value
        var idx = 0
        for (d <- 0 until depth; w <- 0 until width) {
          acc(idx) += funcs(d)(w)(s)
          idx += 1
        }
        acc
      },
      (acc1, acc2) => {
        for (i <- 0 until totalSketches) {
          acc1(i) += acc2(i)
        }
        acc1
      }
    )
    val squaredSketches = sketchSums.map(sum => sum * sum)
    val rowMeans: Array[Double] = Array.tabulate(depth) { d =>
      val row = squaredSketches.slice(d * width, d * width + width)
      row.sum.toDouble / width
    }
    val sortedMeans = rowMeans.sorted
    val median: Double =
      if (depth % 2 == 1) sortedMeans(depth / 2)
      else (sortedMeans(depth / 2 - 1) + sortedMeans(depth / 2)) / 2.0
    median.toLong
  }

  def exact_F0(x: RDD[String]): Long = x.distinct.count

  def exact_F2(x: RDD[String]): Long = {
    x.map(i => (i, 1L))
     .reduceByKey(_ + _)
     .map { case (_, count) => count * count }
     .reduce(_ + _)
  }

  def main(args: Array[String]): Unit = {
    val spark = SparkSession.builder().appName("Project_2").getOrCreate()

    if (args.length < 2) {
      println("Usage: project_2 input_path option = {BJKST, tidemark, ToW, exactF2, exactF0}")
      sys.exit(1)
    }
    val input_path = args(0)
    val df = spark.read.format("csv").load(input_path)
    val dfrdd = df.rdd.map(row => row.getString(0))
    val startTimeMillis = System.currentTimeMillis()

    if (args(1) == "BJKST") {
      if (args.length != 4) {
        println("Usage: project_2 input_path BJKST #buckets trials")
        sys.exit(1)
      }
      val ans = BJKST(dfrdd, args(2).toInt, args(3).toInt)
      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000

      println("==================================")
      println("BJKST Algorithm. Bucket Size:" + args(2) +
              ". Trials:" + args(3) +
              ". Time elapsed:" + durationSeconds + "s. Estimate: " + ans)
      println("==================================")
    } else if (args(1) == "tidemark") {
      if (args.length != 3) {
        println("Usage: project_2 input_path tidemark trials")
        sys.exit(1)
      }
      val ans = tidemark(dfrdd, args(2).toInt)
      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000

      println("==================================")
      println("Tidemark Algorithm. Trials:" + args(2) +
              ". Time elapsed:" + durationSeconds + "s. Estimate: " + ans)
      println("==================================")
    } else if (args(1) == "ToW") {
      if (args.length != 4) {
        println("Usage: project_2 input_path ToW width depth")
        sys.exit(1)
      }
      val ans = Tug_of_War(dfrdd, args(2).toInt, args(3).toInt)
      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000

      println("==================================")
      println("Tug-of-War F2 Approximation. Width:" + args(2) +
              ". Depth:" + args(3) +
              ". Time elapsed:" + durationSeconds + "s. Estimate: " + ans)
      println("==================================")
    } else if (args(1) == "exactF2") {
      if (args.length != 2) {
        println("Usage: project_2 input_path exactF2")
        sys.exit(1)
      }
      val ans = exact_F2(dfrdd)
      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000

      println("==================================")
      println("Exact F2. Time elapsed:" + durationSeconds + "s. Estimate: " + ans)
      println("==================================")
    } else if (args(1) == "exactF0") {
      if (args.length != 2) {
        println("Usage: project_2 input_path exactF0")
        sys.exit(1)
      }
      val ans = exact_F0(dfrdd)
      val endTimeMillis = System.currentTimeMillis()
      val durationSeconds = (endTimeMillis - startTimeMillis) / 1000

      println("==================================")
      println("Exact F0. Time elapsed:" + durationSeconds + "s. Estimate: " + ans)
      println("==================================")
    } else {
      println("Usage: project_2 input_path option = {BJKST, tidemark, ToW, exactF2, exactF0}")
      sys.exit(1)
    }

    spark.stop()
  }
}
