"""Build EfficientNetV2-B3 (ImageNet-21k) with EMA weights; export the feature backbone to TFLite and the head to .npy."""
import os, sys
os.environ['TF_USE_LEGACY_KERAS'] = '1'
G = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(G, 'automl'))
import numpy as np, tensorflow as tf
import effnetv2_model
ck = sys.argv[1]; out = sys.argv[2]; os.makedirs(out, exist_ok=True)
net = effnetv2_model.EffNetV2Model('efficientnetv2-b3', {'num_classes': 21843})
x = tf.keras.Input(shape=(224, 224, 3))
net(x, training=False)
r = tf.train.load_checkpoint(ck)
keys = set(r.get_variable_to_shape_map())
missing = []
for v in net.variables:
    n = v.name.split(':')[0]
    k = n + '/ExponentialMovingAverage'
    if k not in keys: k = n
    if k not in keys: missing.append(n); continue
    v.assign(r.get_tensor(k))
print('vars', len(net.variables), 'missing', missing[:10], len(missing))
W = net._fc.kernel.numpy(); b = net._fc.bias.numpy()
np.save(os.path.join(out, 'head_W.npy'), W); np.save(os.path.join(out, 'head_b.npy'), b)
# Feature model: RGB 0..255 in, pooled 1536 features out.
inp = tf.keras.Input(shape=(224, 224, 3), batch_size=1, name='image')
h = (inp - 128.0) / 128.0
o = net._stem(h, training=False)
for blk in net._blocks: o = blk(o, training=False, survival_prob=None)
feat = net._head(o, training=False)
fm = tf.keras.Model(inp, feat)
# sanity: full model logits == feature @ W + b
t = np.random.RandomState(0).uniform(0, 255, (1, 224, 224, 3)).astype(np.float32)
lg = net((t - 128.0) / 128.0, training=False).numpy()
f = fm(t).numpy(); print('feat', f.shape, 'max diff', np.abs(f @ W + b - lg).max())
np.save(os.path.join(out, 'probe_feat.npy'), f)
conv = tf.lite.TFLiteConverter.from_keras_model(fm)
open(os.path.join(out, 'b3_21k_feat_fp32.tflite'), 'wb').write(conv.convert())
conv = tf.lite.TFLiteConverter.from_keras_model(fm); conv.optimizations = [tf.lite.Optimize.DEFAULT]; conv.target_spec.supported_types = [tf.float16]
open(os.path.join(out, 'b3_21k_feat_fp16.tflite'), 'wb').write(conv.convert())
conv = tf.lite.TFLiteConverter.from_keras_model(fm); conv.optimizations = [tf.lite.Optimize.DEFAULT]
open(os.path.join(out, 'b3_21k_feat_dyn.tflite'), 'wb').write(conv.convert())
print('ok')
