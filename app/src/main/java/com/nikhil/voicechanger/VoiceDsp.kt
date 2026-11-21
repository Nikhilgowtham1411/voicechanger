package com.nikhil.voicechanger

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Streaming TD-PSOLA voice changer with independent pitch and formant control.
 *
 * Pitch is changed by re-spacing pitch-synchronous grains, formants by resampling
 * each grain. That keeps the voice natural (no chipmunk effect).
 * Latency is roughly 30 ms. Feed it mono float samples (-1..1).
 */
class VoiceDsp(private val sr: Int) {

    @Volatile var ratio = 1.5f      // pitch multiplier
    @Volatile var formant = 1.15f   // formant multiplier (>1 = smaller vocal tract)
    @Volatile var gateOn = true
    @Volatile var inGain = 1f

    private val minLag = sr / 400
    private val maxLag = sr / 80
    private val hop = sr / 200
    private val unv = sr / 100
    private val jLen = maxLag

    private val inSize = 1 shl 13
    private val inMask = inSize - 1
    private val inR = FloatArray(inSize)
    private var inPos = 0L

    private val outSize = 1 shl 13
    private val outMask = outSize - 1
    private val outR = FloatArray(outSize)
    private var emitPos = 0L

    // pitch track
    private val pT = FloatArray(256)
    private val pV = BooleanArray(256)
    private var pLast = -1L
    private val hist = FloatArray(3)
    private var histN = 0
    private val seg = FloatArray(jLen + maxLag + 2)
    private val dd = DoubleArray(maxLag + 2)
    private val cm = DoubleArray(maxLag + 2)

    // pitch marks
    private val mk = 256
    private val mPos = LongArray(mk)
    private val mT = FloatArray(mk)
    private val mV = BooleanArray(mk)
    private var mCount = 1L

    private var sPos = 0.0
    private val halfMax = ceil(maxLag / 0.8).toInt() + 2

    // input gate
    private var env = 0f
    private var gate = 1f

    init {
        mPos[0] = 0L
        mT[0] = unv.toFloat()
        mV[0] = false
    }

    private fun x(i: Long): Float = inR[i.toInt() and inMask]

    /** Process [n] samples from [input]; writes result to [out], returns number written. */
    fun process(input: FloatArray, n: Int, out: FloatArray): Int {
        val useGate = gateOn
        val g0 = inGain
        for (i in 0 until n) {
            var v = input[i] * g0
            if (useGate) {
                val a = abs(v)
                env = if (a > env) a else env * 0.9995f
                val target = if (env > 0.012f) 1f else 0.06f
                gate += (target - gate) * (if (target > gate) 0.05f else 0.002f)
                v *= gate
            }
            inR[(inPos and inMask.toLong()).toInt()] = v
            inPos++
        }
        pitchFrames()
        genMarks()
        synth()
        return emit(out)
    }

    // ---------------------------------------------------------------- pitch (YIN)

    private fun pitchFrames() {
        while (true) {
            val k = pLast + 1
            val c = k * hop
            val a = c - jLen / 2
            if (inPos < a + jLen + maxLag + 2) break

            val segLen = jLen + maxLag + 1
            for (i in 0 until segLen) seg[i] = inR[(a + i).toInt() and inMask]

            var e = 0.0
            for (j in 0 until jLen) e += (seg[j] * seg[j]).toDouble()
            val rms = sqrt(e / jLen)

            for (tau in 1..maxLag) {
                var s = 0.0
                for (j in 0 until jLen) {
                    val df = seg[j] - seg[j + tau]
                    s += (df * df).toDouble()
                }
                dd[tau] = s
            }
            var run = 0.0
            for (tau in 1..maxLag) {
                run += dd[tau]
                cm[tau] = if (run > 1e-12) dd[tau] * tau / run else 1.0
            }

            var best = -1
            var tau = minLag
            while (tau <= maxLag - 1) {
                if (cm[tau] < 0.15) {
                    while (tau + 1 <= maxLag - 1 && cm[tau + 1] < cm[tau]) tau++
                    best = tau
                    break
                }
                tau++
            }

            val voiced = best > 0 && rms > 0.004
            var period = 0f
            if (voiced) {
                val t = best
                val den = dd[t - 1] - 2 * dd[t] + dd[t + 1]
                var tf = t.toDouble()
                if (abs(den) > 1e-12) tf += 0.5 * (dd[t - 1] - dd[t + 1]) / den
                // median of last 3
                if (histN == 3) {
                    hist[0] = hist[1]; hist[1] = hist[2]; hist[2] = tf.toFloat()
                } else {
                    hist[histN] = tf.toFloat(); histN++
                }
                period = when (histN) {
                    1 -> hist[0]
                    2 -> (hist[0] + hist[1]) / 2f
                    else -> median3(hist[0], hist[1], hist[2])
                }
            } else {
                histN = 0
            }
            val slot = (k and 255L).toInt()
            pT[slot] = period
            pV[slot] = voiced
            pLast = k
        }
    }

    private fun median3(a: Float, b: Float, c: Float): Float {
        return if (a > b) {
            if (b > c) b else if (a > c) c else a
        } else {
            if (a > c) a else if (b > c) c else b
        }
    }

    // ---------------------------------------------------------------- pitch marks

    private fun ncc(p: Long, q: Long, len: Int): Float {
        val h = len / 2
        var num = 0f
        var e1 = 0f
        var e2 = 0f
        for (j in 0 until len) {
            val a = x(p - h + j)
            val b = x(q - h + j)
            num += a * b
            e1 += a * a
            e2 += b * b
        }
        return num / sqrt(e1 * e2 + 1e-12f)
    }

    private fun genMarks() {
        while (true) {
            val li = ((mCount - 1) % mk).toInt()
            val last = mPos[li]
            val k = (last.toDouble() / hop).roundToLong()
            if (k > pLast) break
            val slot = (k and 255L).toInt()
            val t = pT[slot]
            val voiced = pV[slot]
            val cand: Long
            val newT: Float
            val newV: Boolean
            if (!voiced) {
                cand = last + unv
                if (inPos < cand + unv + 3) break
                newT = unv.toFloat()
                newV = false
            } else {
                val nominal = (last + t).roundToLong()
                val rng = (t / 4f).toInt()
                if (inPos < nominal + rng + t.toInt() + 3) break
                if (mV[li]) {
                    val ti = t.roundToInt()
                    var bestScore = -2f
                    var bestC = nominal
                    var c = nominal - rng
                    while (c <= nominal + rng) {
                        val sc = ncc(c, last, ti)
                        if (sc > bestScore) {
                            bestScore = sc
                            bestC = c
                        }
                        c++
                    }
                    cand = bestC
                } else {
                    cand = nominal
                }
                newT = t
                newV = true
            }
            val ni = (mCount % mk).toInt()
            mPos[ni] = cand
            mT[ni] = newT
            mV[ni] = newV
            mCount++
        }
    }

    // ---------------------------------------------------------------- synthesis

    private fun interp(p: Double): Float {
        val i = floor(p).toLong()
        val fr = (p - i).toFloat()
        val y0 = x(i - 1)
        val y1 = x(i)
        val y2 = x(i + 1)
        val y3 = x(i + 2)
        val a = -0.5f * y0 + 1.5f * y1 - 1.5f * y2 + 0.5f * y3
        val b = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c = -0.5f * y0 + 0.5f * y2
        return ((a * fr + b) * fr + c) * fr + y1
    }

    private fun synth() {
        val r = ratio
        val f = formant.coerceIn(0.8f, 1.8f)
        while (true) {
            val li = ((mCount - 1) % mk).toInt()
            if (mPos[li].toDouble() < sPos) break

            // nearest analysis mark to the synthesis position
            var i = mCount - 1
            var bestI = i
            var bestD = Double.MAX_VALUE
            var steps = 0
            while (steps < mk - 1 && i >= 0) {
                val p = mPos[(i % mk).toInt()].toDouble()
                val d = abs(p - sPos)
                if (d < bestD) {
                    bestD = d
                    bestI = i
                }
                if (p < sPos) break
                i--
                steps++
            }
            val mi = (bestI % mk).toInt()
            val t = mT[mi]
            val voiced = mV[mi]
            val p = mPos[mi]

            val reff = if (voiced) r else 1f
            val spacing = t / reff
            val w = 2f * t / f
            val half = w / 2f
            val gain = 2f * spacing / w
            val base = sPos.roundToLong()
            val hi = ceil(half).toInt()
            for (u in -hi..hi) {
                if (abs(u) > half) continue
                val win = 0.5f * (1f + cos(PI.toFloat() * u / half))
                val idx = (base + u).toInt() and outMask
                outR[idx] += interp(p + u * f.toDouble()) * win * gain
            }
            sPos += spacing
        }
    }

    private fun emit(out: FloatArray): Int {
        val fin = floor(sPos).toLong() - halfMax
        var n = 0
        while (emitPos < fin && n < out.size) {
            val idx = emitPos.toInt() and outMask
            out[n++] = outR[idx]
            outR[idx] = 0f
            emitPos++
        }
        return n
    }
}
