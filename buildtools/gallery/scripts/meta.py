import sys, numpy as np
from tensorflow.lite.python import schema_py_generated as s
buf = open(sys.argv[1],'rb').read()
m = s.Model.GetRootAsModel(buf, 0)
for i in range(m.MetadataLength()):
    md = m.Metadata(i)
    name = md.Name().decode()
    b = m.Buffers(md.Buffer())
    data = b.DataAsNumpy()
    off, size = b.Offset(), b.Size()
    if isinstance(data, int) or data is None or (hasattr(data,'size') and data.size==0):
        data = np.frombuffer(buf[off:off+size], np.uint8) if off else np.zeros(0,np.uint8)
    print(name, len(data))
    if name == 'TFLITE_METADATA':
        raw = bytes(data)
        open(sys.argv[2],'wb').write(raw)
        i = raw.find(b'DETECTOR_METADATA'); print('detector metadata at', i)
