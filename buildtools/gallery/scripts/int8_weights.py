"""fp16-weight TFLite model -> int8 per-channel weights for CONV_2D / DEPTHWISE_CONV_2D (float compute kept:
the weights still go through DEQUANTIZE, which TFLite evaluates once for constant inputs)."""
import sys, collections, numpy as np, flatbuffers
from tensorflow.lite.python import schema_py_generated as sch
src, dst = sys.argv[1:3]
m = sch.ModelT.InitFromObj(sch.Model.GetRootAsModel(open(src, 'rb').read(), 0))
g = m.subgraphs[0]
code = lambda o: m.operatorCodes[o.opcodeIndex].builtinCode
users = collections.defaultdict(list)
for o in g.operators:
    for i in o.inputs: users[i].append(code(o))
done = 0
for o in g.operators:
    if code(o) != sch.BuiltinOperator.DEQUANTIZE: continue
    t = g.tensors[o.inputs[0]]
    u = users[o.outputs[0]]
    if t.type != sch.TensorType.FLOAT16 or len(t.shape) != 4: continue
    # Which input of the consumer is it? Only weights (input 1) of convolutions.
    consumers = [c for c in g.operators if o.outputs[0] in list(c.inputs)]
    if not consumers or any(code(c) not in (sch.BuiltinOperator.CONV_2D, sch.BuiltinOperator.DEPTHWISE_CONV_2D) or list(c.inputs).index(o.outputs[0]) != 1 for c in consumers): continue
    w = np.frombuffer(bytes(m.buffers[t.buffer].data), np.float16).astype(np.float32).reshape(t.shape)
    axis = 3 if code(consumers[0]) == sch.BuiltinOperator.DEPTHWISE_CONV_2D else 0
    red = tuple(i for i in range(4) if i != axis)
    sc = np.abs(w).max(axis=red) / 127.0; sc[sc == 0] = 1e-8
    shape = [1, 1, 1, 1]; shape[axis] = -1
    q = np.round(w / sc.reshape(shape)).clip(-127, 127).astype(np.int8)
    m.buffers[t.buffer].data = np.frombuffer(q.tobytes(), np.uint8)
    t.type = sch.TensorType.INT8
    qp = sch.QuantizationParametersT(); qp.scale = sc.astype(np.float32).tolist(); qp.zeroPoint = [0] * len(sc); qp.quantizedDimension = axis
    t.quantization = qp
    done += 1
b = flatbuffers.Builder(1 << 20); b.Finish(m.Pack(b), file_identifier=b'TFL3')
open(dst, 'wb').write(b.Output())
print('converted', done, 'weight tensors')
