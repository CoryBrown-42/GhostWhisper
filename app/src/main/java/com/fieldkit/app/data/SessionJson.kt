package com.fieldkit.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Session <-> JSON using org.json, which ships with Android (no dependency).
 * Reading uses opt* with defaults so files written by an older version of the
 * app still load after fields are added.
 */
object SessionJson {

    fun toJson(s: Session): JSONObject = JSONObject().apply {
        put("version", 1)
        put("id", s.id)
        put("name", s.name)
        put("createdMs", s.createdMs)
        put("notes", s.notes)
        put("peaks", array(s.peaks) { p ->
            JSONObject().put("t", p.timeMs).put("hz", p.frequencyHz).put("db", p.relDb).put("hf", p.inHfBand)
        })
        put("snapshots", array(s.snapshots) { n ->
            JSONObject().put("t", n.timeMs).put("file", n.fileName).put("caption", n.caption)
        })
        put("observations", array(s.observations) { o ->
            JSONObject()
                .put("t", o.timeMs)
                .put("observer", o.observerType.name)
                .put("species", o.species)
                .put("effects", JSONArray(o.effects))
                .put("effectText", o.effectText)
                .put("severity", o.severity)
                .put("duration", o.duration)
                .put("setting", o.setting.name)
                .put("weather", o.weather)
                .put("equipment", o.nearbyEquipment)
                .put("notes", o.notes)
                .put("baseline", o.isBaseline)
                .put("separated", o.animalsSeparated ?: JSONObject.NULL)
                .put("baselineLogged", o.baselineLogged)
                .put("protection", o.hearingProtection.name)
        })
        put("shielding", array(s.shieldingRuns) { r ->
            JSONObject()
                .put("t", r.startedMs)
                .put("baselinePresent", r.baselinePresent)
                .put("baselineNotes", r.baselineNotes)
                .put("faraday", condition(r.faraday))
                .put("returned", r.returnedBetween)
                .put("protection", condition(r.hearingProtection))
                .put("verdictTitle", r.verdictTitle)
                .put("verdictDetail", r.verdictDetail)
        })
        put("transects", array(s.transects) { t ->
            JSONObject()
                .put("t", t.startedMs)
                .put("increment", t.incrementM)
                .put("startNote", t.startNote)
                .put("assessment", t.assessment)
                .put("stops", array(t.stops) { st ->
                    JSONObject()
                        .put("i", st.index)
                        .put("t", st.timeMs)
                        .put("nominal", st.nominalDistanceM)
                        .put("gps", st.gpsDistanceM ?: JSONObject.NULL)
                        .put("gpsAcc", st.gpsAccuracyM ?: JSONObject.NULL)
                        .put("human", st.humanDetects)
                        .put("intensity", st.intensity)
                        .put("animal", st.animal.name)
                        .put("broadDb", st.broadbandDb ?: JSONObject.NULL)
                        .put("hfDb", st.hfBandDb ?: JSONObject.NULL)
                        .put("note", st.note)
                })
        })
        put("magSweeps", array(s.magSweeps) { m ->
            JSONObject()
                .put("t", m.startedMs)
                .put("duration", m.durationMs)
                .put("mean", m.baselineMeanUt)
                .put("sd", m.baselineSdUt)
                .put("threshold", m.thresholdUt)
                .put("min", m.minUt)
                .put("max", m.maxUt)
                .put("interval", m.traceIntervalMs)
                .put("note", m.note)
                .put("anomalies", array(m.anomalies) { a ->
                    JSONObject().put("t", a.timeMs).put("peak", a.peakDeviationUt).put("duration", a.durationMs)
                })
                .put("trace", JSONArray().also { arr -> m.trace.forEach { arr.put(it.toDouble()) } })
        })
    }

    fun fromJson(o: JSONObject): Session = Session(
        id = o.getString("id"),
        name = o.optString("name", "Session"),
        createdMs = o.optLong("createdMs"),
        notes = o.optString("notes", ""),
        peaks = list(o.optJSONArray("peaks")) { p ->
            PeakEvent(p.optLong("t"), p.optDouble("hz", 0.0), p.optDouble("db", 0.0), p.optBoolean("hf"))
        },
        snapshots = list(o.optJSONArray("snapshots")) { n ->
            Snapshot(n.optLong("t"), n.optString("file", ""), n.optString("caption", ""))
        },
        observations = list(o.optJSONArray("observations")) { x ->
            Observation(
                timeMs = x.optLong("t"),
                observerType = enumOr(x.optString("observer"), ObserverType.HUMAN),
                species = x.optString("species", ""),
                effects = strings(x.optJSONArray("effects")),
                effectText = x.optString("effectText", ""),
                severity = x.optInt("severity", 1),
                duration = x.optString("duration", ""),
                setting = enumOr(x.optString("setting"), Setting.INDOOR),
                weather = x.optString("weather", ""),
                nearbyEquipment = x.optString("equipment", ""),
                notes = x.optString("notes", ""),
                isBaseline = x.optBoolean("baseline"),
                animalsSeparated = if (x.isNull("separated")) null else x.optBoolean("separated"),
                baselineLogged = x.optBoolean("baselineLogged"),
                hearingProtection = enumOr(x.optString("protection"), HearingProtection.NONE),
            )
        },
        shieldingRuns = list(o.optJSONArray("shielding")) { r ->
            ShieldingRun(
                startedMs = r.optLong("t"),
                baselinePresent = r.optBoolean("baselinePresent"),
                baselineNotes = r.optString("baselineNotes", ""),
                faraday = condition(r.optJSONObject("faraday")),
                returnedBetween = r.optBoolean("returned"),
                hearingProtection = condition(r.optJSONObject("protection")),
                verdictTitle = r.optString("verdictTitle", ""),
                verdictDetail = r.optString("verdictDetail", ""),
            )
        },
        transects = list(o.optJSONArray("transects")) { t ->
            Transect(
                startedMs = t.optLong("t"),
                incrementM = t.optDouble("increment", 5.0),
                startNote = t.optString("startNote", ""),
                assessment = t.optString("assessment", ""),
                stops = list(t.optJSONArray("stops")) { st ->
                    RangeStop(
                        index = st.optInt("i"),
                        timeMs = st.optLong("t"),
                        nominalDistanceM = st.optDouble("nominal", 0.0),
                        gpsDistanceM = doubleOrNull(st, "gps"),
                        gpsAccuracyM = doubleOrNull(st, "gpsAcc"),
                        humanDetects = st.optBoolean("human"),
                        intensity = st.optInt("intensity"),
                        animal = enumOr(st.optString("animal"), AnimalReaction.NOT_OBSERVED),
                        broadbandDb = doubleOrNull(st, "broadDb"),
                        hfBandDb = doubleOrNull(st, "hfDb"),
                        note = st.optString("note", ""),
                    )
                },
            )
        },
        magSweeps = list(o.optJSONArray("magSweeps")) { m ->
            MagSweep(
                startedMs = m.optLong("t"),
                durationMs = m.optLong("duration"),
                baselineMeanUt = m.optDouble("mean", 0.0),
                baselineSdUt = m.optDouble("sd", 0.0),
                thresholdUt = m.optDouble("threshold", 0.0),
                minUt = m.optDouble("min", 0.0),
                maxUt = m.optDouble("max", 0.0),
                anomalies = list(m.optJSONArray("anomalies")) { a ->
                    MagAnomaly(a.optLong("t"), a.optDouble("peak", 0.0), a.optLong("duration"))
                },
                trace = floats(m.optJSONArray("trace")),
                traceIntervalMs = m.optLong("interval", 100),
                note = m.optString("note", ""),
            )
        },
    )

    private fun condition(c: ConditionResult): JSONObject =
        JSONObject().put("t", c.timeMs).put("result", c.result.name).put("notes", c.notes)

    private fun condition(o: JSONObject?): ConditionResult =
        if (o == null) ConditionResult(0L, EffectResult.PRESENT, "")
        else ConditionResult(o.optLong("t"), enumOr(o.optString("result"), EffectResult.PRESENT), o.optString("notes", ""))

    private fun <T> array(items: List<T>, encode: (T) -> JSONObject): JSONArray =
        JSONArray().also { arr -> items.forEach { arr.put(encode(it)) } }

    private fun <T> list(arr: JSONArray?, decode: (JSONObject) -> T): List<T> {
        if (arr == null) return emptyList()
        val out = ArrayList<T>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            out.add(decode(item))
        }
        return out
    }

    private fun strings(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optString(it, "") }.filter { it.isNotEmpty() }
    }

    private fun floats(arr: JSONArray?): List<Float> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.optDouble(it, 0.0).toFloat() }
    }

    private fun doubleOrNull(o: JSONObject, key: String): Double? =
        if (o.isNull(key)) null else o.optDouble(key).takeUnless { it.isNaN() }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback
}
