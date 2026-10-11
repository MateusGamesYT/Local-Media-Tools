package com.localmediatools.vision

import com.localmediatools.vision.core.FaceAlign
import com.localmediatools.vision.core.FaceEmbedder
import com.localmediatools.vision.core.FaceEngine
import com.localmediatools.vision.core.FaceInput
import org.opencv.core.Mat
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer

/**
 * A TensorFlow Lite face model (ResNet-50 with 8-bit weights): one face at a time, as the model was
 * measured and its self-check embedding made (buildtools/gallery/face_model.py).
 */
class TfliteFaceEmbedder(model: MappedByteBuffer, threads: Int) : FaceEmbedder {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads.coerceIn(1, 4)))
    private val input: ByteBuffer = ByteBuffer.allocateDirect(FaceAlign.SIZE * FaceAlign.SIZE * 3 * 4).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(interpreter.getOutputTensor(0).shape().last()) }

    override fun describe(aligned: List<Mat>): Array<FloatArray> = Array(aligned.size) { k ->
        input.rewind()
        input.asFloatBuffer().put(FaceInput.nhwc(aligned[k]))
        interpreter.run(input, output)
        FaceEngine.normalize(output[0].copyOf())
    }

    override fun close() = interpreter.close()
}
