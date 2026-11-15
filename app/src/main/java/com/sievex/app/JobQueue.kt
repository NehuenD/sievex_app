package com.sievex.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One recorded burst waiting for ASR + transform. Survives process death in jobs.json. */
data class Job(
    val id: String,
    val wavPath: String,
    val spaceId: String?,
    val attempts: Int,
    /** The format chosen when the burst started; jobs from older files default to a note. */
    val format: String = NoteFormat.NOTE.id
)

/**
 * Durable FIFO of pending bursts. `peek` returns the head so processing stays in capture order;
 * the job is only removed once its note has been filed.
 */
object JobQueue {

    /** After this many failed attempts a job is dropped instead of retried forever. */
    const val MAX_ATTEMPTS = 3

    private val jobs = mutableListOf<Job>()
    private var file: File? = null

    @Synchronized
    fun init(root: File) {
        if (file != null) return
        file = File(root, "jobs.json")
        load()
    }

    @Synchronized
    fun add(job: Job) {
        val i = jobs.indexOfFirst { it.id == job.id }
        if (i >= 0) jobs[i] = job else jobs.add(job)
        save()
    }

    @Synchronized
    fun peek(): Job? = jobs.firstOrNull()

    @Synchronized
    fun remove(id: String) {
        if (jobs.removeAll { it.id == id }) save()
    }

    /** Increments the attempt counter, logs the reason, and returns the new count (0 if unknown). */
    @Synchronized
    fun bumpAttempts(id: String, error: String): Int {
        val i = jobs.indexOfFirst { it.id == id }
        if (i < 0) {
            CaptureLog.add("jobs: bump for unknown job $id - $error")
            return 0
        }
        val next = jobs[i].copy(attempts = jobs[i].attempts + 1)
        jobs[i] = next
        save()
        CaptureLog.add("jobs: ${next.id} attempt ${next.attempts} failed - $error")
        return next.attempts
    }

    @Synchronized
    fun all(): List<Job> = jobs.toList()

    // ---------------------------------------------------------------- persistence

    private fun load() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val arr = JSONArray(f.readText())
            jobs.clear()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val wav = o.optString("wavPath")
                if (id.isBlank() || wav.isBlank()) continue
                val spaceId = o.optString("spaceId").trim()
                jobs.add(
                    Job(
                        id = id,
                        wavPath = wav,
                        spaceId = if (spaceId.isBlank() || spaceId == "null") null else spaceId,
                        attempts = o.optInt("attempts"),
                        format = o.optString("format", NoteFormat.NOTE.id)
                            .takeIf { candidate -> NoteFormat.entries.any { it.id == candidate } }
                            ?: NoteFormat.NOTE.id
                    )
                )
            }
        }.onFailure { CaptureLog.add("jobs: load failed - ${it.message}") }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        runCatching {
            val arr = JSONArray()
            jobs.forEach { j ->
                arr.put(
                    JSONObject().apply {
                        put("id", j.id)
                        put("wavPath", j.wavPath)
                        put("spaceId", j.spaceId ?: "")
                        put("attempts", j.attempts)
                        put("format", j.format)
                    }
                )
            }
            writeAtomic(f, arr.toString())
        }.onFailure { CaptureLog.add("jobs: save failed - ${it.message}") }
    }

    /** Write through a temp file so a crash never leaves half a JSON array behind. */
    private fun writeAtomic(target: File, text: String) {
        val dir = target.parentFile
        if (dir == null) {
            target.writeText(text)
            return
        }
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
