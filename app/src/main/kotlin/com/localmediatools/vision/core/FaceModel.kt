package com.localmediatools.vision.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * The face-recognition models the app ships (InsightFace, weights for non-commercial research use
 * only; made by buildtools/gallery/face_model.py), each with the embedding it gives a fixed pattern
 * when converted, so a phone that computes a model wrongly can be told apart ([FaceEngine.recognizerWorks]).
 */
enum class FaceModel(val file: String, private val check: String) {
    /**
     * ResNet-50 "w600k_r50" (buffalo_l) with 8-bit weights, through TensorFlow Lite: the Gallery's
     * people (1.9.0). 44 MB; about 90 MB of memory while it runs (OpenCV would need 370–790 MB).
     */
    R50("face_recognition_r50_w600k_int8.tflite", "4qhsreuoPClnn7GtRSlhkgKr+yrBKFylzJ+IpcgihKDbHwquea5kKcYgCic8JzWkCqIwGCUnvijWp3YkpxDrJzIsUiaAJQgn4iy2JVArjydMJ4IQSCLRrx6pmSiWlL2k6yB/pbWljSToLKQghqVmpVKorSQOK2yu9qSqIBuZLixdqmwoBKztHs0l+6dWpoqj1y42qDicP6uDKwMqGq3pp0We/BkXrPQmeSM9qDuVQRLaKc6rB6Mfo/sqlJA2IveszyY5pKeppCJXJJmrPSt4qoKqZ533Jj2sACtsKzeoQaZIqNyZXapxpr6p6i0nk9QnFazrpLkjcJzgJbIri5w4qtSt3S3Cr+8nhaYBKYaqLalpMNqonZxPLaunXZyPLLyo/awFJ8isvCTArEolP6RUJ+mk0ahKKrYkcBAdIdSpbij3Kx+pFikPI56paiGBFMikXaEinY+jRKjsoUmsU6u9rKml9CBtF2ej3SofrPYsfaRbrLcskKTvqgMtzg73JXIlRSgOKfSjSqzApLMlTST2q64oYip9Ic+mwiWIJPAeN6gAqEAmVyHqrP+mkyTdpjkpZqxvpGIjySESpBAeUiKJqnAIspVcMNYlyqu/LNYh5SaTnkIpmaGQo2EljSlWKzunHqt/G1Km/yM2nG2hGiIdrP+hCSQiKaAtjKRrIR8mQigjLF+q0SFdKOEemaE9Ia+oPinAqSsieKhnLgaTyCbJKIcrZ6U3pgCtVascIokrTSxTILGs0yrOKxCaWqG8pCkdOSLdKx4nJaTQmqsniKn7qssnEqf1LLYlP5jaKk8isq1hqL2hmikhqSgoXqyloH4kniHYJm0tZiTolIep0Rn0LSMmN657q64l0yi0p54oKqgGKiymBib/HWWmlqodrDaiEKOmqRIgia0NKDgl5iJZoA+JiSk6LDKs4qh6qZmsQK/oKhqlmSFRpdIrD643rK2q8qf5L+QjkqX6qxgr/6TKH5+aH6LdG0Sr5ibVJNct+KoWlk+XUx0FJWYtXCl5qEgo1SJ+K5So8ShfJSclASvFp2csZSfspNAYCxeordOlu6LQpYwkKB5RLFKoS6isKd+pPp1ILdaiTSyUru0p16ouqiSoQCOWJEcrmZ0cIyqi8qLnpX+lJyPIJbkcO6KJqcOlvCoRKPQdcyxPJMcreCPvJKCkIp6FKF+iE63OI8ArL6j0qlwrJKaZKA4oOyonKZMmOSiKpzinbCnEKMGmuaxNJ1MsR6abJf8omKnYqLGuDCihKiWYC6Z/p0ikiBt9FkCb7qFUq4spiiQ1rHalgCrBrqWtHKi/q0saFylLLR0c2KWBprclU6Rrrt6dNKdNpa0hxiEUrmWm76yXKGCo2ayVlQ=="),
    /** MobileFaceNet "w600k_mbf" (buffalo_s) with 16-bit weights, through OpenCV, about seven times faster: face blur, which describes every face of every video frame it samples. */
    MBF("face_recognition_mbf_w600k_fp16.onnx", "6Co+LN2sHC4UpXKerqJQIzQitaxTqjOhBKT6I86pJ5/rKWIkfqWHK04r/aQjIDEmWxXJJCCnzB1gowwlsKt1KXAiKiogLJ2WoagapWCh+6QMlROkpylQK+YfyapXKPCsLLDNKXcnpqBJG34kcydBJQileqKrIgusGSOwFkWoI6SYLMGo9aNBrBcrIKH7rKgqv6JeJvytjaxAnH8r96grrRUpTa8KJMktDqxwmjQn/SoQqLApw6NUqOCqO6ZEKIyiOqRmJBKp1CNDKHuqjibQodEsvRZgK/8oRasJKC6kl63XotEkk6F1oDmskSrOKTYo7SdHKfQipSecJSSo859vIBOqnq3jpRwiv5kTLe0sr6DCKgeQxKXdJ/wqdpwbLbkd6KrDpTAk05pzqNsoLaq2Ibwst6Ajq2alkaSWLCkmPisvKQUszapsLX0iViBbm6KjGSimqvum+il0oQcq5xhmpG8tjau1j58WmCetHC6j3CcNqIsgJK7aqr8pVajSLAStpSuoqJ6ppa0gH5+mX6tMnVwnLCFHJCwjGyaTKsOkBSvlJq8tmSydKz8saSvKnFAo4aokJvWntarYLPwgnS0ZJ48lXST6qQwoWqSJJJ8oMiidpCqgsKmGLU8nRx47o8elFJdGqGAcbKmYrLCkG6fajC+uT6e/KLkqDi2jJQmdXBKVKimrWJzPJJijmCKaH6Sp6hwMonwlHSzPn3+mfieNIiAlsiPsoFUpliGDLBkp0h7KouoiDCD+rLgqI53sLf2rAKhPpZcUoiJapzkr0yeuqmQnBKcXpgChDp8vqs0kzSgVrkCnXSptIcanJSw9HVUsfRw4rHuoQis8qvcs66U/ICinqSzSI7euDip8KYqoQKtioygpCqqzKZYp3p2pJWsoH6WxIUwuJqXXLiCrm6f4nC8oqq0/pomuaiikJQ0uFq5EJvqd46XhIvmt4ChvKfmnhSU8IvkYXxbnKUYkYKwOqjEojSjYpH6lNhjQrHWiVSyJpOsr4CR2rB6ocyPnKa6luRlko0AaECabKTeYE58dpIWlqSObox8lEix+qnwq/KYOozuiF6UcpcusxyQhnpUthxY6LXAqE5+bK88qB6jCpo0rGi3mkiIp7SyzmT+uDiqNqgwfGyewKlYmA6MCGeotKSFfrAkrs6bSFg+LPSyYqBustKoNKVyoQyWyrDiTDiYvrG0ss6pSKHqn8KWGpXikHi2FJBOwB6ATKi0pHiHLKPcq1iZvLEErDahyrF4dXSk7LJuhoSnCpwIlWhwZpJMvPSyHrMKpFyUZKVios6iTKp2n16nlF2CgCCkMqo6jUiRbqoCbJ6kvoqArBiq8K7sjM6drq+SnGR6SKcWfHabSqQ==");

    /** The pattern's embedding (face and mirror averaged) when the model was converted. */
    val expected: FloatArray by lazy {
        val raw = java.util.Base64.getDecoder().decode(check)
        FaceEngine.normalize(FloatArray(raw.size / 2) { half((raw[2 * it].toInt() and 255) or ((raw[2 * it + 1].toInt() and 255) shl 8)) })
    }

    companion object {
        /** The self-check picture (and its mirror image): a fixed 112×112 BGR pattern. */
        internal fun checkPattern(): Pair<Mat, Mat> {
            val n = FaceAlign.SIZE
            val px = ByteArray(n * n * 3)
            for (y in 0 until n) for (x in 0 until n) for (c in 0 until 3) px[(y * n + x) * 3 + c] = (((x * 7 + y * 13 + c * 50) xor (x * y)) and 255).toByte()
            val a = Mat(n, n, CvType.CV_8UC3); a.put(0, 0, px)
            val b = Mat(); Core.flip(a, b, 1)
            return a to b
        }

        /** An IEEE 754 half-precision value (16 bits) as a float. */
        internal fun half(h: Int): Float {
            val sign = if (h and 0x8000 != 0) -1f else 1f
            val exp = (h shr 10) and 0x1F; val man = h and 0x3FF
            return sign * when (exp) {
                0 -> man / 1024f * 6.1035156e-5f
                31 -> if (man == 0) Float.POSITIVE_INFINITY else Float.NaN
                else -> (1f + man / 1024f) * Math.scalb(1f, exp - 15)
            }
        }
    }
}
