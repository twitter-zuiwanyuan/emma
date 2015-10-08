package eu.stratosphere.emma.examples.graphs

import eu.stratosphere.emma.testutil._

import java.io.File

import org.junit.experimental.categories.Category
import org.junit.runner.RunWith

import org.scalatest._
import org.scalatest.junit.JUnitRunner

import scala.io.Source

@Category(Array(classOf[ExampleTest]))
@RunWith(classOf[JUnitRunner])
class BeliefPropagationTest extends FlatSpec with Matchers with BeforeAndAfter {
  // default parameters
  val dir        = "/graphs/belief-prop"
  val path       = tempPath(dir)
  val epsilon    = 1e-9
  val iterations = 100

  before {
    new File(path).mkdirs()
    materializeResource(s"$dir/variables")
    materializeResource(s"$dir/potential")
  }

  after {
    deleteRecursive(new File(path))
  }

  "Belief Propagation" should "calculate unknown marginal probabilities" in withRuntime() { rt =>
    new BeliefPropagation(
      path, s"$path/marginals", epsilon, iterations, rt).run()

    val variables = (for {
      line <- Source.fromFile(s"$path/variables").getLines()
      record = line.split("\t")
      if record(1).toShort == 1
    } yield record(0) -> record(2).toDouble).toMap

    val observed = (for {
      v@(_, p) <- variables
      if p == 1
    } yield v).keySet

    val potential = (for {
      line <- Source.fromFile(s"$path/potential").getLines()
      record = line.split("\t").take(2).toSet
      if record exists observed
      if record exists { !observed(_) }
    } yield record).toSet

    val marginals = (for {
      line <- Source.fromFile(s"$path/marginals").getLines()
      record = line.split("\t")
      if record(1).toShort == 1
    } yield record(0) -> record(2).toDouble).toMap

    // If only one of two often collocated words was observed,
    // the other one is less likely to occur in the text.
    val unlikely = potential count {
      _ find { !observed(_) } match {
        case Some(word) => marginals(word) <= variables(word)
        case None       => false
      }
    }

    unlikely / potential.size.toDouble should equal (1.0 +- 0.05)
  }
}