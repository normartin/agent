import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.engine.spec.tempdir

/** Waits for [job] to be over, failing the test rather than hanging forever. */
private fun BackgroundJob.finish(seconds: Long = 15) {
    await(seconds) shouldBe true
}

class BoundedLogTest : FunSpec({

    test("short output comes back whole") {
        val log = BoundedLog(cap = 300)
        log.append("hello ")
        log.append("world")
        log.snapshot() shouldBe "hello world"
    }

    test("a flood keeps the head and the tail and counts what went missing") {
        // The cap has to hold as the output arrives: a background job has no
        // deadline, so nothing else would stop it filling memory. The head is
        // pinned too: a build's first error is worth more than its 500th download.
        val log = BoundedLog(cap = 300, head = 50)
        repeat(1000) { log.append("line $it\n") }

        val snapshot = log.snapshot()
        snapshot.length shouldBeLessThan 450
        snapshot shouldContain "line 0\nline 1\n"  // head, from the very first byte
        snapshot shouldContain "line 999"          // tail, still moving
        snapshot shouldContain "chars elided"
        snapshot shouldNotContain "line 500"
        // The marker sits between two real pieces of output, in order.
        snapshot.indexOf("line 1\n") shouldBeLessThan snapshot.indexOf("chars elided")
        snapshot.indexOf("chars elided") shouldBeLessThan snapshot.indexOf("line 999")
    }

    test("a chunk that straddles the head boundary is split, not lost") {
        val log = BoundedLog(cap = 300, head = 8)
        log.append("abcdefghij")
        log.snapshot() shouldBe "abcdefghij"
        log.append("k")
        log.snapshot() shouldBe "abcdefghijk"
    }

    test("the elided count is exactly what was thrown away") {
        val log = BoundedLog(cap = 10, head = 5)
        log.append("x".repeat(100))
        log.snapshot() shouldBe "xxxxx\n… [85 chars elided] …\nxxxxxxxxxx"
    }
})

class CarriageReturnTest : FunSpec({

    test("a redrawn line keeps only its final frame") {
        collapseCarriageReturns("Progress: 1\rProgress: 2\rProgress: 3\ndone\n") shouldBe "Progress: 3\ndone\n"
    }

    test("windows line endings are just line endings") {
        collapseCarriageReturns("x\r\ny") shouldBe "x\ny"
    }

    test("text without a carriage return is untouched") {
        val text = "plain\nlines\n"
        collapseCarriageReturns(text) shouldBe text
    }

    test("a frame that straddles two chunks still collapses at read time") {
        val log = BoundedLog()
        log.append("Progress: 1\rProg")
        log.append("ress: 2\n")
        collapseCarriageReturns(log.snapshot()) shouldBe "Progress: 2\n"
    }
})

class BackgroundJobsTest : FunSpec({

    val workspace = tempdir()

    test("a job runs to completion and carries stdout, stderr and its exit code") {
        val jobs = JobRegistry(workspace)
        val job = jobs.start("echo hello; echo boom >&2; exit 3")
        job.finish()

        val report = job.report()
        report shouldContain "hello"
        report shouldContain "ERROR OUTPUT:"
        report shouldContain "boom"
        report shouldContain "[Exit Code: 3"
        job.state shouldBe JobState.EXITED
    }

    test("starting returns immediately, before the command is anywhere near done") {
        val jobs = JobRegistry(workspace)
        val started = System.currentTimeMillis()
        val job = jobs.start("sleep 30")

        (System.currentTimeMillis() - started) shouldBeLessThan 3_000L
        job.state shouldBe JobState.RUNNING
        jobs.running().map { it.name } shouldBe listOf(job.name)

        jobs.killAll()
    }

    test("jobs run in the workspace") {
        val jobs = JobRegistry(workspace)
        val job = jobs.start("pwd")
        job.finish()
        job.report() shouldContain workspace.canonicalPath
    }

    test("a job with no name of its own gets one, and names are never reused") {
        val jobs = JobRegistry(workspace)
        jobs.start("true").name shouldBe "job1"
        jobs.start("true").name shouldBe "job2"

        jobs.start("true", "build").name shouldBe "build"
        // A second "build" must not shadow the first, or a delivered result
        // would be attributed to the wrong process.
        jobs.start("true", "build").name shouldBe "build-2"
        jobs.start("true", "build").name shouldBe "build-3"

        // Anything the model sends that is not name-shaped is cleaned up.
        jobs.start("true", "./gradlew build!").name shouldBe "gradlew-build"
        jobs.start("true", "   ").name shouldBe "job3"
    }

    test("a failure to launch throws rather than registering a dead job") {
        val jobs = JobRegistry(java.io.File("/no/such/directory"))
        shouldThrow<Exception> { jobs.start("echo hi") }
        jobs.running().shouldBeEmpty()
    }

    context("finished jobs are handed over exactly once") {

        test("drainFinished yields a job the first time and never again") {
            val jobs = JobRegistry(workspace)
            val job = jobs.start("echo delivered")
            job.finish()

            jobs.drainFinished().map { it.name } shouldBe listOf(job.name)
            // A repeat would read as the job having run twice.
            jobs.drainFinished().shouldBeEmpty()
        }

        test("a running job is not handed over") {
            val jobs = JobRegistry(workspace)
            jobs.start("sleep 30")
            jobs.drainFinished().shouldBeEmpty()
            jobs.killAll()
        }
    }

    context("stopping") {

        test("a stopped job dies promptly and leaves no orphan") {
            val marker = "kotest-job-stop-marker"
            val jobs = JobRegistry(workspace)
            val job = jobs.start("sleep 300 # $marker")
            Thread.sleep(500)

            val killedAt = System.currentTimeMillis()
            job.stop()
            job.finish()

            (System.currentTimeMillis() - killedAt) shouldBeLessThan 5_000L
            job.state shouldBe JobState.KILLED
            job.report() shouldContain "[Killed after"

            awaitNoProcesses(marker)
            processesMatching(marker) shouldBe 0
        }

        test("killAll takes the descendants down with it") {
            val marker = "kotest-job-killall-marker"
            val jobs = JobRegistry(workspace)
            // The background child is the one that would survive a kill of the
            // bash -c parent alone, holding the pipes open forever.
            jobs.start("sleep 300 & sleep 300 # $marker")
            Thread.sleep(500)

            jobs.killAll() shouldBe 1
            awaitNoProcesses(marker)
            processesMatching(marker) shouldBe 0
            jobs.running().shouldBeEmpty()
        }

        test("killAll on a quiet registry is a no-op") {
            JobRegistry(workspace).killAll() shouldBe 0
        }
    }

    context("waiting and peeking") {

        test("output shows what a live job has printed so far") {
            val jobs = JobRegistry(workspace)
            val job = jobs.start("echo first; sleep 30")
            // The point of the chunked drain: a server's log is readable while
            // the server is still up.
            job.await(10) { false } shouldBe false
            job.report() shouldContain "first"
            job.report() shouldContain "[Still running after"

            job.stop()
        }

        test("await returns as soon as the job is over") {
            val jobs = JobRegistry(workspace)
            val job = jobs.start("sleep 1; echo done")

            val started = System.currentTimeMillis()
            job.await(30) { false } shouldBe true
            (System.currentTimeMillis() - started) shouldBeLessThan 10_000L
            job.report() shouldContain "done"
        }

        test("await gives up at its deadline instead of blocking the loop") {
            val jobs = JobRegistry(workspace)
            val job = jobs.start("sleep 30")

            val started = System.currentTimeMillis()
            job.await(1) { false } shouldBe false
            (System.currentTimeMillis() - started) shouldBeLessThan 5_000L
            job.state shouldBe JobState.RUNNING

            job.stop()
        }

        test("await aborts when the task is interrupted") {
            val jobs = JobRegistry(workspace)
            val job = jobs.start("sleep 30")

            val started = System.currentTimeMillis()
            // Ctrl+C has to be felt during a wait, not after it.
            job.await(600) { true } shouldBe false
            (System.currentTimeMillis() - started) shouldBeLessThan 5_000L

            // Interrupting the wait must not disturb the job itself.
            job.state shouldBe JobState.RUNNING
            job.stop()
        }

        test("awaiting a job that is already over returns at once") {
            val jobs = JobRegistry(workspace)
            val job = jobs.start("true")
            job.finish()
            job.await(600) { false } shouldBe true
        }
    }

    test("a job's log is capped, however much it prints") {
        val jobs = JobRegistry(workspace)
        val job = jobs.start("seq 1 200000")
        job.finish(30)

        val report = job.report()
        report.length shouldBeLessThan MAX_JOB_LOG_CHARS * 2
        report shouldContain "chars elided"
        report shouldContain "200000"
        report shouldContain "[Exit Code: 0"
    }

    test("find resolves by name and reports the misses") {
        val jobs = JobRegistry(workspace)
        val job = jobs.start("true", "build")
        jobs.find("build") shouldBe job
        jobs.find("nope") shouldBe null
        jobs.names() shouldContain "build"
        JobRegistry(workspace).names() shouldBe "none"
    }

    test("a finished job stays findable so its output can be asked for again") {
        val jobs = JobRegistry(workspace)
        val job = jobs.start("echo kept", "keeper")
        job.finish()
        jobs.drainFinished()

        jobs.find("keeper") shouldNotBe null
        jobs.find("keeper")!!.report() shouldContain "kept"
    }
})
